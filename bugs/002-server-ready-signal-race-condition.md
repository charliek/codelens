# Bug 002: Server Ready Signal Race Condition

## Summary

The CodeLens server prints the `CODELENS_READY` signal before the ClassGraph bytecode scan completes. This causes the CLI to consider the server ready while the scan is still running, resulting in empty or incomplete results for the first request(s) after server startup.

## Severity

**Medium** - Causes confusing behavior on first command after server start, but subsequent commands work correctly.

## Symptoms

When starting a server and immediately running a command, results may be empty:

```bash
$ codelens deps -p ~/projects/ratpack-migration/moonracer
Dependency Analysis Summary
  Total Handlers: 0
  Total Dependencies: 0
  Avg Dependencies/Handler: 0.0
  Max Dependencies: 0
  Circular Dependencies: 0

$ codelens deps -p ~/projects/ratpack-migration/moonracer  # Second attempt
Dependency Analysis Summary
  Total Handlers: 21
  Total Dependencies: 69
  Avg Dependencies/Handler: 3.3
  ...
```

The first command shows 0 handlers, but running the same command again shows the correct data.

## Root Cause

The issue is a race condition between the server startup sequence and the asynchronous ClassGraph scan.

### Server Startup Sequence

In `Application.kt`:
```kotlin
val analysisService = AnalysisService(projectDir, config.classpathFile, config.projectJavaHome)
val ratpackAnalysisService = RatpackAnalysisService(analysisService.getClassGraphProvider())
// ... setup routes ...

server.start(wait = false)

// Print ready signal (CLI watches for this on stdout)
println("CODELENS_READY port=$port host=${config.host} version=0.1.0")
```

In `AnalysisService.kt` constructor:
```kotlin
init {
    // ...
    // Start initial scan in background
    scanExecutor.submit { performScan() }
}
```

### Timeline of Events

```
T+0ms    AnalysisService created
T+1ms    Background scan thread submitted (not started yet)
T+2ms    HTTP server starts listening
T+3ms    CODELENS_READY printed → CLI considers server ready
T+5ms    CLI sends first HTTP request
T+10ms   Background scan thread actually starts running
T+50ms   Classpath resolution begins
T+500ms  ClassGraph scan begins
T+2000ms Scan completes, data available
```

If the CLI request arrives between T+3ms and T+2000ms, the ClassGraphProvider returns empty results because:
1. The `scan()` method hasn't been called yet, OR
2. The scan is in progress but hasn't populated the internal data structures

### Why the CLI Believes Server is Ready

The CLI in `server_service.py` waits for the `CODELENS_READY` pattern:

```python
async def _wait_for_ready(self, process, timeout, log_file, project_path):
    """Wait for server to print CODELENS_READY."""
    ready_pattern = re.compile(r"CODELENS_READY port=(\d+) host=(\S+) version=(\S+)")
```

Once this pattern is detected, the CLI immediately returns and considers the server ready for requests.

## Affected Commands

**All analysis commands are affected** when run as the first command after server startup:

| Command | Impact |
|---------|--------|
| `codelens deps` | Returns 0 handlers, empty foundation classes |
| `codelens deps foundation` | Empty list |
| `codelens deps quickwins` | Empty list |
| `codelens deps graph` | Empty graph |
| `codelens handlers list` | Empty list or partial results |
| `codelens handlers info <fqn>` | "Not found" errors |
| `codelens classes list` | Empty or partial list |
| `codelens classes info <fqn>` | "Not found" errors |
| `codelens promises summary` | Zero counts |
| `codelens modules list` | Empty list |
| `codelens integrations summary` | Zero counts |
| `codelens antipatterns` | Empty results |
| `codelens routes` | Empty results |

Commands that don't query the ClassGraphProvider are NOT affected:
- `codelens status` (reads from ProjectInfo which shows LOADING/READY status)
- `codelens stop`
- `codelens logs`

## Reproduction Steps

1. Ensure no server is running for the target project:
   ```bash
   codelens stop -p ~/projects/ratpack-migration/moonracer
   ```

2. Start fresh and immediately query:
   ```bash
   codelens deps -p ~/projects/ratpack-migration/moonracer
   ```

3. Observe empty results (0 handlers)

4. Run the same command again:
   ```bash
   codelens deps -p ~/projects/ratpack-migration/moonracer
   ```

5. Observe correct results (21 handlers for moonracer)

### Timing Factors

The race condition window depends on:
- **Project size**: Larger projects take longer to scan (wider window)
- **Classpath resolution method**: Gradle Tooling API is slower than classpath file
- **Disk speed**: Affects classpath resolution and bytecode reading
- **System load**: Affects thread scheduling

Typical scan times observed:
- Small project (moonracer): 1-3 seconds
- Medium project (pumbaa): 1-2 seconds

## Current Workaround

Users can work around this by:

1. **Wait and retry**: Simply run the command again
2. **Check status first**: Run `codelens status -p <project>` and wait for `READY` status before querying
3. **Add delay in scripts**: If automating, add a sleep after server start

## Proposed Solutions

### Option A: Wait for Scan Before Ready Signal (Recommended)

Move the `CODELENS_READY` signal to after the initial scan completes:

```kotlin
// In Application.kt
val analysisService = AnalysisService(projectDir, config.classpathFile, config.projectJavaHome)

// Wait for initial scan to complete
analysisService.awaitInitialScan(timeout = 60.seconds)

server.start(wait = false)
println("CODELENS_READY port=$port host=${config.host} version=0.1.0")
```

**Pros:**
- Simple, correct fix
- CLI behavior unchanged
- Server is truly ready when signal is sent

**Cons:**
- Increases perceived startup time (user waits for scan)
- May need timeout handling if scan fails

### Option B: Add Health/Ready Endpoint

Add a `/health` or `/ready` endpoint that returns the scan status:

```kotlin
get("/health") {
    val status = analysisService.getProjectInfo().status
    if (status == ProjectStatus.READY) {
        call.respond(HttpStatusCode.OK, mapOf("status" to "ready"))
    } else {
        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to status.name))
    }
}
```

CLI polls this endpoint after seeing `CODELENS_READY`:

```python
async def _wait_for_ready(self, process, timeout, log_file, project_path):
    # Wait for CODELENS_READY (server is listening)
    # ...

    # Then poll /health until scan is complete
    async with httpx.AsyncClient() as client:
        while True:
            resp = await client.get(f"http://{host}:{port}/health")
            if resp.json()["status"] == "ready":
                break
            await asyncio.sleep(0.1)
```

**Pros:**
- Decouples "server listening" from "scan complete"
- More informative status
- Can show progress to user

**Cons:**
- More complex implementation
- Requires CLI changes
- Additional HTTP requests

### Option C: Lazy Initialization with Blocking

Make the first request block until scan is complete:

```kotlin
class ClassGraphProviderImpl : ClassGraphProvider {
    private val scanComplete = CountDownLatch(1)

    override fun getClass(fqn: String): ClassInfo? {
        scanComplete.await()  // Block until scan done
        return internalGetClass(fqn)
    }
}
```

**Pros:**
- No CLI changes needed
- Transparent to callers

**Cons:**
- First request has unpredictable latency
- May cause HTTP timeouts for slow scans
- Harder to show progress

### Option D: Return "Not Ready" Error

Have endpoints return a specific error when scan is incomplete:

```kotlin
get("/api/v1/ratpack/dependencies") {
    if (analysisService.getProjectInfo().status != ProjectStatus.READY) {
        call.respond(HttpStatusCode.ServiceUnavailable,
            ErrorResponse("Scan in progress, please retry"))
        return@get
    }
    // ... normal handling
}
```

**Pros:**
- Explicit error handling
- Client knows to retry

**Cons:**
- Requires CLI to handle retry logic
- Poor UX without automatic retry

## Recommended Approach

Implement **Option A** as the primary fix:

1. Add `awaitInitialScan()` method to `AnalysisService`
2. Call it in `Application.kt` before printing `CODELENS_READY`
3. Add reasonable timeout (60 seconds) with error handling

Additionally, implement **Option B** as an enhancement:
1. Add `/health` endpoint for monitoring/debugging
2. Useful for CI/CD integrations and container health checks

## Related Files

- `server/app/src/main/kotlin/codelens/server/Application.kt` - Server startup, ready signal
- `server/app/src/main/kotlin/codelens/server/services/AnalysisService.kt` - Scan orchestration
- `server/classgraph/src/main/kotlin/codelens/classgraph/ClassGraphProviderImpl.kt` - Bytecode scanning
- `cli/src/codelens_cli/services/server_service.py` - CLI server management, ready detection
- `cli/src/codelens_cli/commands/common.py` - `ensure_server_running()` helper

## Test Cases for Fix

1. **Fresh start returns correct data**: Stop server, start and immediately query, expect correct results
2. **Large project handling**: Test with project that takes >5 seconds to scan
3. **Timeout handling**: Test behavior when scan exceeds timeout
4. **Error handling**: Test behavior when scan fails (e.g., invalid classpath)
5. **Status endpoint accuracy**: Verify `/health` endpoint reflects actual scan status

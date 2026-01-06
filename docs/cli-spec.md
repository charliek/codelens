# CodeLens CLI Specification

## Overview

The CodeLens CLI is the primary interface for developers (and Claude Code) to analyze Ratpack codebases during migration. It manages server lifecycle transparently and provides a rich, progressive-disclosure interface for analysis queries.

**Design Principles:**
- **Zero friction startup**: Commands auto-start the server if needed
- **Transparent server management**: Users don't think about servers, just queries
- **Progressive disclosure**: Simple output by default, depth on demand
- **Machine-friendly**: JSON output and proper exit codes for automation
- **Claude Code compatible**: No interactive prompts, structured output available

**Tech Stack:**
- Python 3.11+
- Typer (CLI framework)
- Rich (terminal formatting)
- httpx (async HTTP client)
- UV for package management and tool installation

**Supported Platforms:** macOS, Linux

---

## Repository Structure

CodeLens is a mono-repo containing both the Kotlin server and Python CLI:

```
codelens/
├── README.md
├── settings.gradle.kts
├── build.gradle.kts
│
├── server/                          # Kotlin server (Gradle multi-module)
│   ├── codelens-core/
│   ├── codelens-gradle/
│   ├── codelens-classgraph/
│   └── codelens-server/
│       └── build.gradle.kts         # Produces fat JAR
│
├── cli/                             # Python CLI
│   ├── pyproject.toml
│   ├── src/
│   │   └── codelens/
│   │       ├── __init__.py
│   │       ├── main.py              # Typer app entry point
│   │       ├── client.py            # HTTP client for server
│   │       ├── server_manager.py    # Server lifecycle
│   │       ├── state.py             # State management
│   │       ├── output.py            # Rich formatting
│   │       └── commands/
│   │           ├── lifecycle.py     # start, stop, status
│   │           ├── handlers.py      # handlers, handler
│   │           ├── analysis.py      # promises, modules, report
│   │           └── classes.py       # classes, class, deps
│   └── tests/
│
└── gradle/
    └── libs.versions.toml
```

---

## Installation (Development)

During development, the CLI is installed as an editable package using UV:

```bash
# Clone the repo
git clone https://github.com/example/codelens.git
cd codelens

# Install CLI as editable tool
cd cli
uv tool install --editable .

# Verify installation
codelens version
```

This makes `codelens` available globally while allowing live edits to the Python source.

**Prerequisites:**
- Python 3.11+ 
- UV (`curl -LsSf https://astral.sh/uv/install.sh | sh`)
- JDK 21+ (for running the server)
- Gradle 8.x (bundled via wrapper in repo)

### Server Build

The CLI can run the server in two modes:

1. **Development mode**: Invoke Gradle directly (slower startup, but no build step)
2. **JAR mode**: Run pre-built fat JAR (faster startup, requires build)

```bash
# Build the server fat JAR
./gradlew :server:codelens-server:shadowJar

# JAR location
ls server/codelens-server/build/libs/codelens-server-all.jar
```

---

## State Management

### Centralized State Directory

All CodeLens state lives in `~/.cache/codelens/`:

```
~/.cache/codelens/
├── servers/                            # Running server state
│   ├── {project-hash}.json             # One file per project
│   └── ...
├── logs/                               # Server logs
│   ├── {project-hash}.log
│   └── ...
└── server.jar                          # Cached JAR (future: downloaded)
```

**Why centralized?**
- No pollution of target project directories
- Easy to enumerate all running servers (`codelens list`)
- Logs accessible without navigating to each project
- Single location to clean up (`rm -rf ~/.cache/codelens`)

### Server State File

Each running server has a state file keyed by a hash of the project path:

```
~/.cache/codelens/servers/{sha256(projectPath)[:12]}.json
```

```json
{
  "projectPath": "/home/user/work/user-service",
  "projectName": "user-service",
  "pid": 12345,
  "port": 8080,
  "host": "127.0.0.1",
  "startedAt": "2026-01-04T10:30:00Z",
  "lastActivityAt": "2026-01-04T10:45:00Z",
  "idleTimeout": "30m",
  "status": "READY",
  "statusMessage": null,
  "serverMode": "jar",
  "version": "1.0.0"
}
```

**Fields:**
- `serverMode`: How the server was started (`"gradle"` or `"jar"`)
- Other fields same as before

### Project Hash Calculation

```python
import hashlib

def project_hash(project_path: Path) -> str:
    """Generate a short hash for a project path."""
    canonical = str(project_path.resolve())
    return hashlib.sha256(canonical.encode()).hexdigest()[:12]
```

### Log Files

Server stdout/stderr is captured to log files:

```
~/.cache/codelens/logs/{project-hash}.log
```

Useful for debugging startup failures. Logs are rotated/truncated on server restart.

---

## Server Lifecycle Model

### Auto-Start Behavior

Any query command auto-starts the server if not running:

```bash
$ cd ~/work/user-service
$ codelens handlers
Starting CodeLens server...
Resolving dependencies... done (3.2s)
Scanning classes... done (1.1s)

Found 24 handlers in user-service
...
```

Subsequent commands reuse the running server:

```bash
$ codelens promises          # Instant response
$ codelens handler UserHandler
```

### Explicit Lifecycle Commands

```bash
$ codelens start             # Start server (if not running)
$ codelens stop              # Stop server
$ codelens status            # Show server status
$ codelens restart           # Stop + start (useful after config changes)
$ codelens refresh           # Re-scan bytecode (after gradle build)
```

### Auto-Shutdown

Servers automatically shut down after a configurable idle period (default: 30 minutes). This prevents resource waste when working across multiple repos.

The idle timer resets on any CLI command. The server tracks `lastActivityAt` in the discovery file.

### One-Shot Mode

For CI/scripts, skip server persistence:

```bash
$ codelens handlers --once   # Start, query, stop
$ codelens report --once -o report.json
```

---

## Configuration

**Location:** `~/.config/codelens/config.yml`

```yaml
# Server behavior
server:
  mode: auto                 # auto | gradle | jar
                             # auto = use JAR if built, else Gradle
  idle_timeout: 30m          # Auto-shutdown after idle (default: 30m, 0 = disabled)
  port_range:
    start: 8080
    end: 8180
  host: 127.0.0.1            # Bind address

# Output defaults
output:
  format: auto               # auto | table | json (auto = detect TTY)
  color: auto                # auto | always | never
  verbose: false             # Show extra context by default

# Java configuration  
java:
  home: null                 # Override JAVA_HOME for server JVM
  opts: []                   # Additional JVM options

# Development
dev:
  repo_path: null            # Override detected repo path (rarely needed)
```

**Environment Variable Overrides:**
- `CODELENS_SERVER_MODE`: Force `gradle` or `jar` mode
- `CODELENS_IDLE_TIMEOUT`: Override idle timeout
- `CODELENS_OUTPUT_FORMAT`: Force output format
- `CODELENS_REPO_PATH`: Override repo path detection
- `NO_COLOR`: Disable color output (standard env var)

---

## Server Invocation

### Mode Selection

The CLI needs to start the Kotlin server. It supports two modes:

| Mode | When to Use | Startup Time | Requires |
|------|-------------|--------------|----------|
| `gradle` | Development, debugging | ~5-10s | None (builds on demand) |
| `jar` | Normal use, faster startup | ~1-2s | `./gradlew :server:shadowJar` |

**Auto mode** (default) checks if the fat JAR exists and is newer than source files:
- If JAR exists and is fresh → use JAR
- Otherwise → use Gradle

### Repo Path Detection

The CLI needs to find the CodeLens repository (where Gradle and the JAR live). Detection order:

1. `CODELENS_REPO_PATH` environment variable
2. `dev.repo_path` in config file
3. Walk up from the CLI package's `__file__` to find `gradlew`

```python
def find_repo_path() -> Path:
    # Check environment
    if env_path := os.environ.get("CODELENS_REPO_PATH"):
        return Path(env_path)
    
    # Check config
    if config.dev.repo_path:
        return Path(config.dev.repo_path)
    
    # Walk up from CLI source to find gradlew
    current = Path(__file__).resolve().parent
    while current != current.parent:
        if (current / "gradlew").exists():
            return current
        current = current.parent
    
    raise ConfigurationError(
        "Could not find CodeLens repository. "
        "Set CODELENS_REPO_PATH or dev.repo_path in config."
    )
```

### Starting the Server

```python
async def start_server(
    project_path: Path,
    mode: ServerMode,
    port: int | None = None,
    idle_timeout: str = "30m"
) -> ServerInfo:
    repo_path = find_repo_path()
    
    # Prepare state directory
    state_dir = Path.home() / ".cache" / "codelens"
    servers_dir = state_dir / "servers"
    logs_dir = state_dir / "logs"
    servers_dir.mkdir(parents=True, exist_ok=True)
    logs_dir.mkdir(parents=True, exist_ok=True)
    
    # Log file for this project
    proj_hash = project_hash(project_path)
    log_file = logs_dir / f"{proj_hash}.log"
    
    # Build command based on mode
    if mode == ServerMode.GRADLE:
        cmd = [
            str(repo_path / "gradlew"),
            ":server:run",
            f"--args=--project {project_path} --idle-timeout {idle_timeout}"
        ]
        if port:
            cmd[-1] += f" --port {port}"
        cwd = repo_path
    else:  # JAR mode
        jar_path = repo_path / "server" / "build" / "libs" / "codelens-server-all.jar"
        if not jar_path.exists():
            raise ServerError(
                f"Server JAR not found at {jar_path}. "
                "Run `./gradlew :server:shadowJar` or use `--mode gradle`."
            )
        
        java_home = config.java.home or os.environ.get("JAVA_HOME")
        java_cmd = f"{java_home}/bin/java" if java_home else "java"
        
        cmd = [
            java_cmd,
            *config.java.opts,
            "-jar", str(jar_path),
            "--project", str(project_path),
            "--idle-timeout", idle_timeout
        ]
        if port:
            cmd.extend(["--port", str(port)])
        cwd = None
    
    # Start process
    with open(log_file, "w") as log:
        process = subprocess.Popen(
            cmd,
            cwd=cwd,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True  # Detach from terminal
        )
    
    # Wait for server to write its state file (server writes to stdout, we parse)
    # Or: poll /admin/health until ready
    server_info = await wait_for_server_ready(project_path, process.pid, timeout=60)
    
    # Write CLI-side state file
    state_file = servers_dir / f"{proj_hash}.json"
    state_file.write_text(json.dumps({
        "projectPath": str(project_path),
        "projectName": project_path.name,
        "pid": process.pid,
        "port": server_info.port,
        "host": server_info.host,
        "startedAt": datetime.now(UTC).isoformat(),
        "lastActivityAt": datetime.now(UTC).isoformat(),
        "idleTimeout": idle_timeout,
        "status": "READY",
        "serverMode": mode.value,
        "version": server_info.version
    }))
    
    return server_info
```

### Server Startup Protocol

The server needs to communicate its port back to the CLI. Options:

**Option A: Server writes to stdout (recommended)**
```
# Server outputs on successful startup:
CODELENS_READY port=8080 host=127.0.0.1
```

CLI parses the log file for this line.

**Option B: Server writes state file**
Server writes to `~/.cache/codelens/servers/{hash}.json` directly. CLI polls for this file.

**Option C: CLI polls /admin/health**
CLI tries ports in range until it finds the server.

**Recommendation:** Option A - server writes a structured line to stdout, CLI captures and parses it. This is simple and works for both Gradle and JAR modes.

---

## Output Formatting

### TTY Detection

```python
def get_output_format(ctx: typer.Context) -> OutputFormat:
    # Explicit flag wins
    if ctx.params.get("json"):
        return OutputFormat.JSON
    if ctx.params.get("table"):
        return OutputFormat.TABLE
    
    # Config file
    config_format = config.output.format
    if config_format != "auto":
        return OutputFormat(config_format)
    
    # TTY detection
    if sys.stdout.isatty():
        return OutputFormat.TABLE
    else:
        return OutputFormat.JSON
```

### Human Output (Rich Tables)

```
$ codelens handlers

user-service • 24 handlers • Last scanned: 2 minutes ago

Complexity: ■■■■■■■■ LOW (8)  ■■■■■■■■■■ MEDIUM (10)  ■■■■■ HIGH (5)  ■ VERY_HIGH (1)

┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┳━━━━━━━━━━━━┳━━━━━━━━━━━┓
┃ Handler                            ┃ Complexity ┃ Effort    ┃
┡━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━╇━━━━━━━━━━━━╇━━━━━━━━━━━┩
│ c.e.handlers.UserHandler           │ MEDIUM     │ ~4 hours  │
│ c.e.handlers.OrderHandler          │ HIGH       │ ~1 day    │
│ c.e.handlers.HealthHandler         │ LOW        │ ~1 hour   │
│ ...                                │            │           │
└────────────────────────────────────┴────────────┴───────────┘

Tip: Use `codelens handler <name>` for detailed analysis
```

### Machine Output (JSON)

```bash
$ codelens handlers --json
```

```json
{
  "project": "user-service",
  "scannedAt": "2026-01-04T10:30:00Z",
  "summary": {
    "total": 24,
    "byComplexity": {"LOW": 8, "MEDIUM": 10, "HIGH": 5, "VERY_HIGH": 1}
  },
  "handlers": [
    {
      "fqn": "com.example.handlers.UserHandler",
      "simpleName": "UserHandler",
      "complexity": {
        "level": "MEDIUM",
        "score": 3.5,
        "effort": "~4 hours"
      }
    }
  ]
}
```

### Piped Output

When stdout is not a TTY (piped or redirected), output defaults to JSON:

```bash
$ codelens handlers | jq '.handlers[].fqn'
"com.example.handlers.UserHandler"
"com.example.handlers.OrderHandler"
...
```

---

## Command Reference

### Lifecycle Commands

#### `codelens start`

Start the server for the current project.

```bash
$ codelens start [OPTIONS]

Options:
  --port PORT          Use specific port (default: auto-assign)
  --wait / --no-wait   Wait for server to be ready (default: --wait)
  --timeout SECONDS    Startup timeout (default: 60)
  -p, --project PATH   Project directory (default: current directory)
```

**Exit Codes:**
- 0: Server started successfully
- 1: Server failed to start
- 2: Already running (not an error, but distinct)

#### `codelens stop`

Stop the server for the current project.

```bash
$ codelens stop [OPTIONS]

Options:
  --force              Kill process if graceful shutdown fails
  --timeout SECONDS    Graceful shutdown timeout (default: 10)
  -p, --project PATH   Project directory (default: current directory)
```

#### `codelens status`

Show server status and statistics.

```bash
$ codelens status [OPTIONS]

Options:
  --json               Output as JSON
  -p, --project PATH   Project directory
```

**Output:**
```
CodeLens server running

  Project:     user-service
  Path:        /home/user/work/user-service
  Port:        8080
  Status:      READY
  Uptime:      15m 32s
  Idle:        2m 15s (auto-shutdown in 27m 45s)
  
  Classes:     1,847 indexed
  Handlers:    24 detected
  Last scan:   2 minutes ago
```

#### `codelens restart`

Stop and start the server (re-resolves Gradle, rescans).

```bash
$ codelens restart [OPTIONS]
```

#### `codelens refresh`

Re-scan bytecode without full restart. Use after `./gradlew build`.

```bash
$ codelens refresh [OPTIONS]

Options:
  --wait / --no-wait   Wait for refresh to complete (default: --wait)
```

---

### Analysis Commands

#### `codelens handlers`

List Ratpack handlers with complexity assessment.

```bash
$ codelens handlers [OPTIONS]

Options:
  --complexity LEVEL   Filter by complexity (LOW, MEDIUM, HIGH, VERY_HIGH)
  --sort FIELD         Sort by: complexity (default), name, effort
  --limit N            Show only first N handlers
  --details            Include complexity factors
  --json               Output as JSON
  -p, --project PATH   Project directory
```

**Examples:**
```bash
$ codelens handlers --complexity HIGH
$ codelens handlers --sort name --limit 10
$ codelens handlers --details
```

#### `codelens handler <name>`

Detailed analysis of a specific handler.

```bash
$ codelens handler <CLASS_NAME> [OPTIONS]

Arguments:
  CLASS_NAME           Fully qualified or simple class name

Options:
  --json               Output as JSON
  -p, --project PATH   Project directory
```

**Output:**
```
com.example.handlers.UserHandler

  Type:        HANDLER_INTERFACE
  Complexity:  MEDIUM (score: 3.5)
  Effort:      ~4 hours

Complexity Factors:
  • Blocking.get() usage           +0.5
  • Promise operators (4)          +1.2
  • Registry lookups (2)           +1.0
  • External Promise sources       +0.8

Promise Usage:
  • findById() → Blocking.get() → map → then
  • validateUser() → flatMap → onError

Dependencies:
  → UserService (injected)
  → UserRepository (via UserService)
  → ValidationService (injected)

Migration Notes:
  • Replace Blocking.get() with withContext(Dispatchers.IO)
  • Consider converting to suspend function
  • Registry lookups need constructor injection
```

#### `codelens promises`

Analyze Promise usage across the project.

```bash
$ codelens promises [OPTIONS]

Options:
  --class CLASS        Filter to specific class
  --source SOURCE      Filter by source (BLOCKING_GET, EXECUTION_FORK, etc.)
  --json               Output as JSON
  -p, --project PATH   Project directory
```

#### `codelens modules`

List Guice modules and bindings.

```bash
$ codelens modules [OPTIONS]

Options:
  --bindings           Include binding details
  --json               Output as JSON
  -p, --project PATH   Project directory
```

#### `codelens report`

Generate a comprehensive migration report.

```bash
$ codelens report [OPTIONS]

Options:
  --target FRAMEWORK   Target framework (kotlin-coroutines, spring-webflux, micronaut)
  --output, -o FILE    Write to file instead of stdout
  --format FORMAT      Output format (table, json, markdown)
  -p, --project PATH   Project directory
```

**Output (markdown format):**
```markdown
# Migration Report: user-service

Generated: 2026-01-04 10:30:00

## Summary

- **Total Handlers:** 24
- **Estimated Total Effort:** ~3 weeks
- **Target Framework:** Kotlin Coroutines

## By Complexity

### LOW (8 handlers) - ~1 day total
Direct port with minimal changes.

| Handler | Effort |
|---------|--------|
| HealthHandler | ~1 hour |
| ConfigHandler | ~1 hour |
...

### VERY_HIGH (1 handler) - ~1 week
Requires architectural redesign.

| Handler | Blocking Factors |
|---------|------------------|
| BatchProcessingHandler | Execution.fork(), Thread.sleep() |

## Anti-Patterns Detected

- **JDBC without Blocking** (1 class): LegacyReportHandler
- **Thread.sleep()** (1 class): BatchProcessingHandler

## Recommended Order

1. Start with LOW complexity handlers to build patterns
2. Tackle MEDIUM handlers using established patterns
3. HIGH handlers may need architectural discussion
4. VERY_HIGH handlers need dedicated planning
```

---

### Generic Class Analysis

#### `codelens classes`

Search and list classes.

```bash
$ codelens classes [OPTIONS]

Options:
  --package PATTERN    Filter by package (supports wildcards)
  --implements TYPE    Filter by interface
  --extends TYPE       Filter by superclass
  --annotation TYPE    Filter by annotation
  --project-only       Exclude library classes (default: true)
  --json               Output as JSON
  -p, --project PATH   Project directory
```

**Examples:**
```bash
$ codelens classes --package "com.example.handlers.*"
$ codelens classes --implements "ratpack.handling.Handler"
$ codelens classes --annotation "javax.inject.Singleton"
```

#### `codelens class <name>`

Get detailed class information.

```bash
$ codelens class <CLASS_NAME> [OPTIONS]

Arguments:
  CLASS_NAME           Fully qualified or simple class name

Options:
  --json               Output as JSON
  -p, --project PATH   Project directory
```

#### `codelens deps <name>`

Show dependencies for a class.

```bash
$ codelens deps <CLASS_NAME> [OPTIONS]

Arguments:
  CLASS_NAME           Fully qualified or simple class name

Options:
  --direction DIR      incoming, outgoing, or both (default: both)
  --depth N            Traversal depth (default: 1)
  --project-only       Exclude library classes
  --json               Output as JSON
  -p, --project PATH   Project directory
```

---

### Utility Commands

#### `codelens list`

List all running CodeLens servers on this machine.

```bash
$ codelens list [OPTIONS]

Options:
  --json               Output as JSON
```

**Output:**
```
Running CodeLens Servers

┏━━━━━━━━━━━━━━━━━━━━┳━━━━━━━━┳━━━━━━━━━━━━━━┳━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ Project            ┃ Port   ┃ Status       ┃ Path                              ┃
┡━━━━━━━━━━━━━━━━━━━━╇━━━━━━━━╇━━━━━━━━━━━━━━╇━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┩
│ user-service       │ 8080   │ READY        │ ~/work/user-service               │
│ order-service      │ 8081   │ READY        │ ~/work/order-service              │
│ payment-service    │ 8082   │ SCANNING     │ ~/work/payment-service            │
└────────────────────┴────────┴──────────────┴───────────────────────────────────┘
```

#### `codelens version`

Show CLI and server versions.

```bash
$ codelens version

codelens-cli 1.0.0
codelens-server 1.0.0 (running on port 8080)
```

#### `codelens config`

Show or edit configuration.

```bash
$ codelens config show           # Show current config
$ codelens config path           # Show config file path
$ codelens config edit           # Open in $EDITOR
$ codelens config set KEY VALUE  # Set a value
```

---

## Error Handling

### Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | General error |
| 2 | Server not running (for commands that require it with --no-auto-start) |
| 3 | Project not found or invalid (no build.gradle) |
| 4 | Server startup failed |
| 5 | Server communication error |
| 6 | Resource not found (e.g., class doesn't exist) |
| 7 | Timeout |

### Error Output

**Human-readable (TTY):**
```
$ codelens handlers
Error: No Gradle build file found in /home/user/random-dir

  CodeLens requires a project with build.gradle or build.gradle.kts.
  
  Try:
    cd /path/to/your/ratpack/project
    codelens handlers
  
  Or specify explicitly:
    codelens handlers --project /path/to/project
```

**Machine-readable (JSON):**
```json
{
  "error": true,
  "code": 3,
  "type": "ProjectNotFound",
  "message": "No Gradle build file found in /home/user/random-dir",
  "path": "/home/user/random-dir"
}
```

### Common Errors

| Scenario | Message | Suggestion |
|----------|---------|------------|
| No build.gradle | "No Gradle build file found" | "Try `cd /path/to/project` or use `--project`" |
| Server won't start | "Server failed to start: {reason}" | Show stderr from server process |
| Timeout waiting for server | "Server startup timed out after 60s" | "Check Gradle configuration, try `--timeout 120`" |
| Class not found | "Class 'UserHandler' not found" | "Did you mean 'com.example.UserHandler'?" (if close match) |
| Server crashed | "Lost connection to server" | "Run `codelens restart` to recover" |

---

## Server Coordination

### Centralized State Model

Unlike tools that store state in each project directory, CodeLens uses a centralized cache:

```
~/.cache/codelens/
├── servers/
│   ├── a1b2c3d4e5f6.json    # State for project with hash a1b2c3d4e5f6
│   └── ...
└── logs/
    ├── a1b2c3d4e5f6.log     # Logs for same project
    └── ...
```

**Benefits:**
- `codelens list` can enumerate all servers without scanning disk
- No `.codelens/` directories in every project
- Logs centralized for easy debugging
- Simple cleanup: `rm -rf ~/.cache/codelens`

### CLI Discovery Logic

```python
from pathlib import Path
import json
import hashlib
import psutil

def project_hash(project_path: Path) -> str:
    canonical = str(project_path.resolve())
    return hashlib.sha256(canonical.encode()).hexdigest()[:12]

def find_server(project_path: Path) -> ServerInfo | None:
    state_dir = Path.home() / ".cache" / "codelens" / "servers"
    state_file = state_dir / f"{project_hash(project_path)}.json"
    
    if not state_file.exists():
        return None
    
    info = json.loads(state_file.read_text())
    
    # Check if process is still running
    if not psutil.pid_exists(info["pid"]):
        # Stale file, clean up
        state_file.unlink()
        cleanup_log_file(project_path)
        return None
    
    # Verify server is responsive
    try:
        response = httpx.get(
            f"http://{info['host']}:{info['port']}/admin/health",
            timeout=2
        )
        if response.status_code == 200:
            return ServerInfo(**info)
    except httpx.RequestError:
        pass
    
    # Process exists but server not responding
    # Could be starting up - check status field
    if info.get("status") in ("STARTING", "RESOLVING", "SCANNING"):
        return ServerInfo(**info)  # Let caller decide to wait
    
    # Zombie state - process exists but server dead
    # Kill the process and clean up
    try:
        os.kill(info["pid"], signal.SIGTERM)
    except ProcessLookupError:
        pass
    state_file.unlink()
    cleanup_log_file(project_path)
    return None

def list_all_servers() -> list[ServerInfo]:
    """List all running CodeLens servers."""
    state_dir = Path.home() / ".cache" / "codelens" / "servers"
    if not state_dir.exists():
        return []
    
    servers = []
    for state_file in state_dir.glob("*.json"):
        info = json.loads(state_file.read_text())
        
        # Validate still running
        if psutil.pid_exists(info["pid"]):
            servers.append(ServerInfo(**info))
        else:
            # Clean up stale file
            state_file.unlink()
    
    return servers

def cleanup_log_file(project_path: Path) -> None:
    log_file = Path.home() / ".cache" / "codelens" / "logs" / f"{project_hash(project_path)}.log"
    log_file.unlink(missing_ok=True)
```

### Auto-Start Flow

```python
async def ensure_server_running(
    project_path: Path,
    timeout: int = 60,
    mode: ServerMode | None = None
) -> ServerInfo:
    # Check for existing server
    server = find_server(project_path)
    if server and server.status == "READY":
        return server
    
    if server and server.status in ("STARTING", "RESOLVING", "SCANNING"):
        # Already starting, wait for it
        return await wait_for_ready(server, timeout)
    
    # Need to start server
    console.print("Starting CodeLens server...", style="dim")
    
    # Determine mode
    if mode is None:
        mode = determine_server_mode()  # auto logic
    
    server = await start_server(project_path, mode)
    return server

def determine_server_mode() -> ServerMode:
    """Determine whether to use Gradle or JAR mode."""
    config_mode = config.server.mode
    
    if config_mode == "gradle":
        return ServerMode.GRADLE
    if config_mode == "jar":
        return ServerMode.JAR
    
    # Auto mode: prefer JAR if available and fresh
    repo_path = find_repo_path()
    jar_path = repo_path / "server" / "build" / "libs" / "codelens-server-all.jar"
    
    if jar_path.exists():
        # Could add staleness check here (compare JAR mtime to source mtimes)
        return ServerMode.JAR
    
    return ServerMode.GRADLE
```

### Activity Tracking

Every CLI command that talks to the server should ping the activity endpoint:

```python
async def execute_query(server: ServerInfo, path: str) -> dict:
    async with httpx.AsyncClient() as client:
        # Main request
        response = await client.get(f"http://{server.host}:{server.port}{path}")
        
        # Touch activity (fire-and-forget, don't wait)
        asyncio.create_task(
            client.post(f"http://{server.host}:{server.port}/admin/activity")
        )
        
        # Update local state file with new activity time
        update_local_activity(server.projectPath)
        
        return response.json()

def update_local_activity(project_path: Path) -> None:
    """Update lastActivityAt in the local state file."""
    state_file = (
        Path.home() / ".cache" / "codelens" / "servers" / 
        f"{project_hash(project_path)}.json"
    )
    if state_file.exists():
        info = json.loads(state_file.read_text())
        info["lastActivityAt"] = datetime.now(UTC).isoformat()
        state_file.write_text(json.dumps(info, indent=2))
```

This keeps both the server and CLI state in sync for idle timeout calculations.

---

## Server API Requirements

To support this CLI design, the server needs these capabilities:

### Startup Output Protocol

The server must print a structured line to stdout when ready:

```
CODELENS_READY port=8080 host=127.0.0.1 version=1.0.0
```

This allows the CLI to:
1. Know when the server is ready to accept requests
2. Discover which port was assigned (if auto-assigned)
3. Work in both Gradle and JAR modes

### Required Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/admin/health` | GET | Health check (always 200 if up) |
| `/admin/ready` | GET | Readiness (200 if ready, 503 if scanning) |
| `/admin/info` | GET | Server info including idle status |
| `/admin/activity` | POST | Touch activity timestamp |
| `/admin/shutdown` | POST | Graceful shutdown |

### Idle Shutdown

The server tracks its own activity and shuts down after the configured idle timeout. The CLI also tracks activity locally for status display, but the server is the source of truth for shutdown timing.

### No Server-Side State Files

Unlike the original design, the server does **not** write state files. State management is entirely the CLI's responsibility. This simplifies the server and avoids coordination issues between server and CLI writes.

The server only needs to:
1. Print `CODELENS_READY` on startup
2. Respond to API requests
3. Shut itself down on idle timeout or shutdown request
```

---

## Claude Code Integration

### Recommended Usage Pattern

Claude Code should use JSON output for all queries:

```bash
# Claude Code executes:
codelens handlers --json

# Parses response:
{
  "handlers": [...],
  "summary": {...}
}
```

### Key Considerations for Claude Code

1. **Always use `--json`**: Ensures parseable output regardless of config
2. **Handle startup time**: First command may take 5-10 seconds
3. **Check exit codes**: Non-zero means error, parse JSON error object
4. **Use `--project` for clarity**: Don't rely on working directory assumptions
5. **One-shot for single queries**: Use `--once` if only making one query

### Example Claude Code Interaction

```python
# Claude Code analyzing a handler
result = subprocess.run(
    ["codelens", "handler", "com.example.UserHandler", "--json"],
    capture_output=True,
    text=True,
    cwd="/path/to/project"
)

if result.returncode != 0:
    error = json.loads(result.stdout)
    # Handle error
else:
    analysis = json.loads(result.stdout)
    # Use analysis.complexity, analysis.promiseUsages, etc.
```

---

## Implementation Phases

### Phase 2A.1: Core CLI Skeleton
- [ ] Typer app structure with command groups
- [ ] Configuration file loading
- [ ] Server discovery logic
- [ ] Auto-start flow
- [ ] Output formatting (Rich tables + JSON)

### Phase 2A.2: Lifecycle Commands
- [ ] `start`, `stop`, `status`, `restart`, `refresh`
- [ ] `list` (all running servers)
- [ ] Process management (start JAR, wait for ready)

### Phase 2A.3: Analysis Commands
- [ ] `handlers`, `handler <name>`
- [ ] `promises`, `modules`
- [ ] `report`

### Phase 2A.4: Generic Commands
- [ ] `classes`, `class <name>`, `deps <name>`

### Phase 2A.5: Polish
- [ ] Error messages and suggestions
- [ ] Shell completion (bash, zsh, fish)
- [ ] Man page / help improvements

---

## Open Questions

1. ~~**Server JAR distribution**~~: Resolved - CLI invokes Gradle or local JAR from monorepo checkout.

2. **Startup progress**: Should the CLI show Gradle resolution progress, or just a spinner? Could tail the log file for status updates.

3. **Fuzzy matching**: Should `codelens handler UserHandler` find `com.example.handlers.UserHandler`? Probably yes, with disambiguation if multiple matches.

4. **Aliases**: Support short aliases like `codelens h` for `handlers`? Nice for humans, but Claude Code doesn't need them.

5. **Server output protocol**: The server needs to signal readiness to the CLI. Current proposal: server prints `CODELENS_READY port=8080 host=127.0.0.1` to stdout. Alternatives?

6. **Gradle mode output**: In Gradle mode, Gradle's own output mixes with server output. May need to filter or use `--quiet` flag.

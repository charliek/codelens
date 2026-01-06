# CodeLens Phase 1: Foundation & Structural Analysis

## Overview

Phase 1 establishes the core architecture with ClassGraph-based structural analysis, designed for extensibility to source-level analysis in future phases. The deliverable is a **single-project HTTP server** that answers structural questions about a Gradle-based JVM project.

**Context:** This tool supports migration planning for ~55 Ratpack-based repositories spanning Java 8 through Java 17. The architecture must handle this version diversity without requiring changes to target projects.

**Deployment Model:** One CodeLens server process per project directory. Developers working on multiple migrations simultaneously run multiple server instances (on different ports). The CLI manages this lifecycle transparently.

**Timeline estimate:** 3-4 weeks of focused development

---

## Goals & Non-Goals

### In Scope (Phase 1)
- **Single-project server** bound to a directory at startup
- **Python CLI** with Typer + Rich for primary user interface
- Project loading via Gradle Tooling API
- ClassGraph-based structural analysis
- Analysis facade architecture (provider-agnostic query interface)
- REST API for structural queries
- Ratpack-specific structural queries (handler discovery, Promise usage detection)
- In-memory caching with manual refresh
- Stdout-based readiness protocol (`CODELENS_READY`)
- CLI-managed state in `~/.cache/codelens/`
- Auto-start server on query commands
- Idle timeout with auto-shutdown
- JSON output for Claude Code integration

### Out of Scope (Future Phases)
- MCP server wrapper
- Source-level analysis (PSI, Kotlin Analysis API)
- Promise chain semantic analysis
- File watching / automatic refresh
- Multi-project support within a single process
- Persistence layer
- PyPI/standalone distribution

---

## Deployment Model: Single-Project-Per-Process

### Design Philosophy

CodeLens follows a **single-project-per-process** model where each server instance is bound to exactly one target project directory. This is a core architectural decision that simplifies state management and enables a clean CLI workflow.

**Why Single-Project:**
1. **Simplifies state management** — No multi-tenant complexity, no project switching, no shared cache invalidation
2. **Enables dedicated resources** — Full memory/CPU for analyzing one (potentially large) project
3. **Supports parallel workflows** — Users can run multiple CodeLens instances on different ports
4. **Fits developer workflow** — One terminal/IDE per project being migrated
5. **Clean lifecycle** — Server starts → loads project → serves queries → shuts down
6. **CLI-friendly** — The CLI can manage process lifecycle per-directory

**What This Means:**
- The server requires a `--project` path at startup (not configurable after)
- There is no API endpoint to "switch" or "add" projects
- The `/project/refresh` endpoint re-scans the *same* project (after code changes)
- Running analysis on a different project = start a new server process

### Deployment Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Developer Workstation                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Terminal 1 (user-service migration)      Terminal 2 (order-service)   │
│  ┌─────────────────────────────────┐     ┌─────────────────────────────┐│
│  │ $ cd ~/work/user-service        │     │ $ cd ~/work/order-service   ││
│  │ $ codelens start                │     │ $ codelens start            ││
│  │ Server running on :8080         │     │ Server running on :8081     ││
│  │ Project: user-service           │     │ Project: order-service      ││
│  │                                 │     │                             ││
│  │ $ codelens handlers             │     │ $ codelens migration-report ││
│  │ Found 12 handlers...            │     │ Complexity: MEDIUM...       ││
│  └─────────────────────────────────┘     └─────────────────────────────┘│
│                                                                         │
│  Each codelens process:                                                 │
│  - Targets ONE project directory                                        │
│  - Runs on unique port (auto-assigned or specified)                     │
│  - Maintains in-memory scan of that project                             │
│  - Managed by CLI (start/stop/status)                                   │
│  - Writes discovery file for CLI to find it                             │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### Server Startup Contract

The server requires a project path at startup (not configurable via API):

```bash
# Direct server invocation (what CLI will call internally)
java -jar codelens-server.jar \
  --project /path/to/ratpack-app \
  --port 8080 \                    # Optional, auto-assigns if omitted
  --host 127.0.0.1                 # Optional, localhost only by default
```

**Startup Sequence:**
1. Parse command-line arguments, validate project path
2. Validate project directory exists and contains `build.gradle` or `build.gradle.kts`
3. Acquire port (specified or find available in range 8080-8180)
4. Begin Gradle resolution (log progress to stderr)
5. Begin ClassGraph scan (log progress to stderr)
6. Start HTTP server (endpoints return 503 until scan complete)
7. Print `CODELENS_READY port=XXXX host=XXXX version=X.X.X` to stdout
8. Server ready for queries

**Startup Failure:**
- If Gradle resolution fails: print error to stderr, exit with code 1
- If port unavailable: print error to stderr, exit with code 1
- If project path invalid: print error to stderr, exit with code 1

### Server State Management

**State is CLI-managed**: The server does **not** write state files. State management is entirely the CLI's responsibility, stored in `~/.cache/codelens/servers/`. This separation:
- Simplifies the server (no file I/O for state)
- Avoids coordination issues between server and CLI
- Keeps all CLI-specific concerns (port allocation, multi-server tracking) in Python
- Makes server completely stateless (except in-memory analysis cache)

**CLI State File Location:**
```
~/.cache/codelens/servers/{sha256(projectPath)[:12]}.json
```

**State File Contents (written by CLI):**
```json
{
  "pid": 12345,
  "port": 8080,
  "host": "127.0.0.1",
  "projectPath": "/home/user/work/user-service",
  "projectName": "user-service",
  "startedAt": "2026-01-04T10:30:00Z",
  "lastActivityAt": "2026-01-04T10:45:00Z",
  "idleTimeout": "30m",
  "status": "READY",
  "serverMode": "jar",
  "version": "1.0.0",
  "apiVersion": "v1"
}
```

### Server Startup Output Protocol

The server communicates readiness to the CLI via stdout. After HTTP server is ready, server prints:

```
CODELENS_READY port=8080 host=127.0.0.1 version=1.0.0
```

This allows the CLI to:
1. Know when the server is ready to accept requests
2. Discover which port was assigned (for auto-assigned ports)
3. Work in both Gradle and JAR invocation modes

**Implementation:**
```kotlin
fun printReadySignal(port: Int, host: String, version: String) {
    println("CODELENS_READY port=$port host=$host version=$version")
    System.out.flush()  // Ensure CLI sees it immediately
}
```

All logging (Gradle resolution progress, scan progress, etc.) goes to stderr so the CLI can reliably parse stdout for the ready signal.

### Port Management

```kotlin
data class ServerConfig(
    val projectPath: Path,
    val port: Int? = null,           // null = auto-assign
    val host: String = "127.0.0.1",  // localhost only by default
    val portRangeStart: Int = 8080,  // For auto-assignment
    val portRangeEnd: Int = 8180     // Try up to 100 ports
)

fun findAvailablePort(config: ServerConfig): Int {
    if (config.port != null) return config.port
    
    for (port in config.portRangeStart..config.portRangeEnd) {
        if (isPortAvailable(port)) return port
    }
    throw IllegalStateException(
        "No available ports in range ${config.portRangeStart}-${config.portRangeEnd}"
    )
}
```

### Graceful Shutdown

The server supports graceful shutdown via:

1. **SIGTERM/SIGINT** — Standard Unix signals
2. **Shutdown endpoint** — `POST /admin/shutdown` (localhost only)
3. **CLI command** — `codelens stop` sends shutdown request, cleans up state file
4. **Idle timeout** — Auto-shutdown after configurable idle period

```kotlin
fun configureShutdown(server: EmbeddedServer) {
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutting down...")
        // Close ClassGraph resources
        scanResult?.close()
        // Stop server
        server.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)
        // Note: CLI manages state file cleanup
    })
}
```

### Idle Shutdown & Activity Tracking

The server tracks activity and shuts down automatically after a configurable idle period. This prevents resource waste when developers context-switch between repos.

**Configuration:**

```bash
java -jar codelens-server.jar \
  --project /path/to/ratpack-app \
  --idle-timeout 30m          # Default: 30 minutes, 0 = disabled
```

**Activity Tracking:**

Every request to any endpoint updates the `lastActivityAt` timestamp. The CLI can also explicitly call `POST /admin/activity` as a keep-alive.

```kotlin
class ActivityTracker {
    private val lastActivity = AtomicReference(Instant.now())
    
    fun touch() {
        lastActivity.set(Instant.now())
    }
    
    fun getLastActivity(): Instant = lastActivity.get()
    
    fun getIdleDuration(): Duration = Duration.between(lastActivity.get(), Instant.now())
}

class IdleShutdownMonitor(
    private val idleTimeout: Duration,
    private val activityTracker: ActivityTracker,
    private val onIdle: () -> Unit
) {
    fun start() {
        if (idleTimeout.isZero) return  // Disabled
        
        thread(name = "idle-monitor", isDaemon = true) {
            while (true) {
                Thread.sleep(60_000)  // Check every minute
                
                if (activityTracker.getIdleDuration() > idleTimeout) {
                    logger.info("Idle timeout reached, initiating shutdown")
                    onIdle()
                    break
                }
            }
        }
    }
}
```

**Integration with Ktor:**

```kotlin
fun Application.configureActivityTracking(tracker: ActivityTracker) {
    intercept(ApplicationCallPipeline.Monitoring) {
        tracker.touch()
    }
}
```
```

---

## CLI Integration (Context for Server Design)

The CLI is specified in detail in `codelens-cli-spec.md`. This section summarizes what the server must provide.

**See:** `codelens-cli-spec.md` for full CLI specification.

### Key Server Requirements for CLI

| CLI Need | Server Support |
|----------|----------------|
| Know when server is ready | Print `CODELENS_READY port=X host=Y version=Z` to stdout |
| Check if server is ready | `/admin/ready` endpoint |
| Display status info | `/admin/info` endpoint |
| Issue queries | `/api/v1/*` endpoints |
| Keep server alive | `/admin/activity` endpoint |
| Refresh after edits | `/project/refresh` endpoint |
| Stop server gracefully | `/admin/shutdown` endpoint |
| Idle shutdown | Server monitors own idle time and exits |

### CLI-Managed State

The CLI (not server) manages state files in `~/.cache/codelens/`:
- Server state files (`servers/*.json`)
- Server logs (`logs/*.log`)

This keeps the server simple - it just needs to:
1. Print `CODELENS_READY` when ready
2. Respond to API requests
3. Shut down on idle timeout or shutdown request

### Invocation Modes

The CLI can start the server two ways:
- **Gradle mode**: `./gradlew :server:run --args="..."` (development)
- **JAR mode**: `java -jar codelens-server-all.jar ...` (faster startup)

---

## JVM Version Strategy

### Decision: Use JVM 21 for CodeLens

CodeLens will target **JVM 21** (latest LTS), regardless of what JVM versions the ~55 target repositories use (ranging from Java 8 to Java 17).

### Why Coupling Is Minimal

CodeLens doesn't *execute* code from target projects—it only reads bytecode and metadata. This creates very loose coupling:

**ClassGraph (Bytecode Analysis)**

ClassGraph parses `.class` files directly. The JVM class file format is versioned, but **newer JVMs can always read older bytecode**:

| Target Project | Class File Version | Can CodeLens (JVM 21) Read? |
|----------------|-------------------|------------------------------|
| Java 8         | 52                | ✅ Yes                       |
| Java 11        | 55                | ✅ Yes                       |
| Java 17        | 61                | ✅ Yes                       |
| Java 21        | 65                | ✅ Yes                       |

The reverse is NOT true—a JVM 17 runtime cannot read class files compiled for JVM 21. Using the newest JVM maximizes what CodeLens can analyze.

**Gradle Tooling API (Dependency Resolution)**

The Tooling API connects to the target project's Gradle daemon as a *client*. The daemon runs on whatever JVM the target project specifies. The Tooling API handles cross-version communication transparently:

```
┌─────────────────────┐         ┌─────────────────────┐
│     CodeLens        │         │   Target Project    │
│     (JVM 21)        │◄───────►│   Gradle Daemon     │
│                     │ Tooling │   (JVM 8/11/17)     │
│                     │   API   │                     │
└─────────────────────┘         └─────────────────────┘
```

### Compatibility Matrix

| Component | Version | Constraint Notes |
|-----------|---------|------------------|
| **CodeLens JVM** | 21 | Newest LTS for maximum compatibility |
| **Kotlin** | 2.1+ | K2 compiler, targets JVM 21 |
| **Target project JVM** | 8 - 21+ | No constraint from CodeLens |
| **Target project Gradle** | 4.x - 8.x | Tooling API supports wide range |
| **ClassGraph** | 4.8.x | Handles all bytecode versions |

### Build Configuration

```kotlin
// build.gradle.kts
kotlin {
    jvmToolchain(21)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
```

### Benefits of JVM 21

- Modern Kotlin features (context receivers, value classes, etc.)
- Virtual threads available for concurrent scanning operations
- Ability to analyze any project from Java 8 through Java 21
- LTS release with long-term support through 2031

---

## Tech Stack

| Component | Choice | Rationale |
|-----------|--------|-----------|
| **Language** | Kotlin 2.1+ (K2) | Modern language features, excellent Java interop |
| **Runtime** | JVM 21 (LTS) | Reads all bytecode versions 8-21, virtual threads available |
| **Dependency Resolution** | Gradle Tooling API 8.x | Resolves classpaths across Gradle 4.x-8.x target projects |
| **Analysis Engine** | ClassGraph 4.8.x | Fast headless bytecode scanning, inter-class dependencies |
| **Web Framework** | Ktor 3.0+ | Kotlin-native, lightweight, coroutine-based |
| **DI Framework** | Koin 4.0 | Simple, Kotlin-first dependency injection |
| **Serialization** | kotlinx.serialization | Native Kotlin, compile-time safe |
| **Build System** | Gradle 8.x + Version Catalog | Multi-module support, dependency management |
| **CLI (Phase 2)** | Clikt | Kotlin-native CLI framework |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     CodeLens Server Process                     │
│                (One instance per target project)                │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │                    API Layer (Ktor)                       │ │
│  │  /admin  /project  /classes  /dependencies  /ratpack     │ │
│  └─────────────────────────┬─────────────────────────────────┘ │
│                            │                                    │
│  ┌─────────────────────────▼─────────────────────────────────┐ │
│  │                   Query Service Layer                     │ │
│  │  ProjectQueryService, DependencyQueryService, Ratpack*   │ │
│  └─────────────────────────┬─────────────────────────────────┘ │
│                            │                                    │
│  ┌─────────────────────────▼─────────────────────────────────┐ │
│  │                    Analysis Facade                        │ │
│  │  ┌─────────────────────────────────────────────────────┐ │ │
│  │  │         ProjectContext (singleton per process)      │ │ │
│  │  │  - Bound at startup, immutable project path         │ │ │
│  │  │  - Holds provider results                           │ │ │
│  │  │  - Supports refresh (re-scan same project)          │ │ │
│  │  └─────────────────────────────────────────────────────┘ │ │
│  │  ┌─────────────────────────────────────────────────────┐ │ │
│  │  │           AnalysisProvider (interface)              │ │ │
│  │  │  - initialize(projectContext)                       │ │ │
│  │  │  - capabilities(): Set<Capability>                  │ │ │
│  │  │  - query(request): AnalysisResult                   │ │ │
│  │  └─────────────────────────────────────────────────────┘ │ │
│  └─────────────────────────┬─────────────────────────────────┘ │
│                            │                                    │
│  ┌─────────────────────────▼─────────────────────────────────┐ │
│  │                  Analysis Providers                       │ │
│  │  ┌──────────────────────┐  ┌───────────────────────────┐ │ │
│  │  │ ClassGraphProvider   │  │ SourceProvider (Phase 2)  │ │ │
│  │  │ (Phase 1)            │  │ - Kotlin Analysis API     │ │ │
│  │  │ - Structural queries │  │ - Semantic analysis       │ │ │
│  │  └──────────────────────┘  └───────────────────────────┘ │ │
│  └─────────────────────────┬─────────────────────────────────┘ │
│                            │                                    │
│  ┌─────────────────────────▼─────────────────────────────────┐ │
│  │                   Project Resolver                        │ │
│  │  - Gradle Tooling API integration                        │ │
│  │  - Runs ONCE at startup (or on refresh)                  │ │
│  │  - Classpath extraction, source root detection           │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │                   Lifecycle Manager                       │ │
│  │  - Idle timeout monitoring                                │ │
│  │  - Activity tracking                                      │ │
│  │  - Shutdown hooks                                         │ │
│  │  - CODELENS_READY signal on startup                       │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
         │ Outputs                              │ Analyzes
         │ CODELENS_READY                       │
         ▼                                      ▼
┌─────────────────────────┐    ┌─────────────────────────────────┐
│   CLI (Python)          │    │   Target Project Directory      │
│                         │    │   (Ratpack app being migrated)  │
│ ~/.cache/codelens/      │    │                                 │
│ ├── servers/            │    │ /home/user/work/user-service/   │
│ │   └── a1b2c3.json     │    │ ├── build.gradle.kts            │
│ └── logs/               │    │ ├── src/main/kotlin/...         │
│     └── a1b2c3.log      │    │ └── build/classes/...           │
│                         │    │     ↑ ClassGraph scans          │
│ State managed by CLI    │    │                                 │
└─────────────────────────┘    └─────────────────────────────────┘
```

---

## Core Data Models

### Server Configuration

```kotlin
/**
 * Server startup configuration.
 * Project path is REQUIRED and immutable after startup.
 */
data class ServerConfig(
    val projectPath: Path,               // Required, validated at startup
    val port: Int? = null,               // null = auto-assign
    val host: String = "127.0.0.1",      // localhost only by default
    val portRangeStart: Int = 8080,
    val portRangeEnd: Int = 8180,
    val idleTimeout: Duration = Duration.ofMinutes(30)  // 0 = disabled
) {
    init {
        require(projectPath.exists()) { "Project path does not exist: $projectPath" }
        require(projectPath.isDirectory()) { "Project path is not a directory: $projectPath" }
        require(
            projectPath.resolve("build.gradle").exists() ||
            projectPath.resolve("build.gradle.kts").exists()
        ) { "No build.gradle or build.gradle.kts found in $projectPath" }
    }
}
```

**Command-line Arguments:**

```bash
java -jar codelens-server.jar \
  --project /path/to/ratpack-app \    # Required
  --port 8080 \                        # Optional, auto-assigns if omitted
  --host 127.0.0.1 \                   # Optional, localhost by default
  --idle-timeout 30m                   # Optional, 30m default, 0 = disabled
```

### Project & Analysis Context

```kotlin
/**
 * Represents a loaded project. Immutable after creation.
 * One ProjectContext per server process.
 */
data class ProjectContext(
    val projectPath: Path,
    val name: String,
    val classpath: List<Path>,          // Resolved JARs + build outputs
    val sourceRoots: List<Path>,        // For future source analysis
    val modules: List<ModuleInfo>,      // Multi-module awareness
    val loadedAt: Instant,
    val status: ProjectStatus
)

enum class ProjectStatus {
    RESOLVING,      // Gradle resolution in progress
    SCANNING,       // ClassGraph scan in progress  
    READY,          // Available for queries
    ERROR           // Resolution or scan failed
}

data class ModuleInfo(
    val name: String,
    val path: Path,
    val buildOutputs: List<Path>,
    val dependencies: List<Path>
)
```

### Structural Analysis Results

```kotlin
// Core class information
data class ClassInfo(
    val fqn: String,                           // Fully qualified name
    val simpleName: String,
    val packageName: String,
    val kind: ClassKind,
    val modifiers: Set<Modifier>,
    val sourceFile: String?,                   // If available from debug info
    val location: LocationInfo,                // JAR or directory
    val superclass: String?,
    val interfaces: List<String>,
    val annotations: List<AnnotationInfo>,
    val methods: List<MethodInfo>,
    val fields: List<FieldInfo>
)

enum class ClassKind {
    CLASS, INTERFACE, ENUM, ANNOTATION, RECORD, OBJECT
}

data class LocationInfo(
    val type: LocationType,
    val path: String,                          // JAR path or directory
    val isProjectCode: Boolean                 // vs. external dependency
)

enum class LocationType { JAR, DIRECTORY }

data class AnnotationInfo(
    val fqn: String,
    val parameters: Map<String, Any?>          // Annotation parameter values
)

data class MethodInfo(
    val name: String,
    val signature: String,                     // Full signature with params
    val returnType: String,
    val parameters: List<ParameterInfo>,
    val modifiers: Set<Modifier>,
    val annotations: List<AnnotationInfo>
)

data class FieldInfo(
    val name: String,
    val type: String,
    val modifiers: Set<Modifier>,
    val annotations: List<AnnotationInfo>
)
```

### Dependency Analysis

```kotlin
// Reference between classes
data class ClassReference(
    val sourceClass: String,                   // FQN of referencing class
    val targetClass: String,                   // FQN of referenced class
    val referenceTypes: Set<ReferenceType>,    // How it's referenced
    val locations: List<ReferenceLocation>     // Where in the class
)

enum class ReferenceType {
    EXTENDS,                // Direct superclass
    IMPLEMENTS,             // Interface implementation
    FIELD_TYPE,             // Field declaration
    METHOD_PARAMETER,       // Method parameter type
    METHOD_RETURN,          // Return type
    LOCAL_VARIABLE,         // Local variable type
    TYPE_ANNOTATION,        // Used in annotation
    METHOD_CALL,            // Calls method on this type
    CONSTRUCTOR_CALL,       // new X() or X()
    STATIC_REFERENCE,       // Static field/method access
    THROWS,                 // Exception declaration
    GENERIC_BOUND           // Generic type parameter
}

data class ReferenceLocation(
    val referenceType: ReferenceType,
    val memberName: String?                    // Method/field name if applicable
)

// Dependency graph for a class
data class DependencyGraph(
    val rootClass: String,
    val outgoing: List<ClassReference>,        // Classes this class depends on
    val incoming: List<ClassReference>,        // Classes that depend on this
    val depth: Int                             // How deep the graph was traversed
)
```

### Ratpack-Specific Models

```kotlin
// Ratpack handler information
data class RatpackHandlerInfo(
    val classInfo: ClassInfo,
    val handlerType: HandlerType,
    val chainMethods: List<ChainMethodUsage>,  // get(), post(), path() etc.
    val promiseUsages: List<PromiseUsageInfo>,
    val registryAccess: List<RegistryAccessInfo>,
    val blockingUsages: List<BlockingUsageInfo>,
    val migrationComplexity: MigrationComplexity
)

enum class HandlerType {
    HANDLER_INTERFACE,      // Implements ratpack.handling.Handler
    CHAIN_ACTION,           // Implements Action<Chain>
    GROOVY_HANDLER,         // Groovy DSL handler (if detected)
    INLINE_LAMBDA           // Lambda passed to chain methods (detected by context)
}

data class PromiseUsageInfo(
    val className: String,
    val methodName: String,
    val promiseSource: PromiseSource,          // Where the Promise originates
    val operations: List<String>,              // map, flatMap, then, etc.
    val terminalOperation: String?             // How the promise resolves
)

enum class PromiseSource {
    BLOCKING_GET,           // Blocking.get { }
    PROMISE_VALUE,          // Promise.value()
    PROMISE_ASYNC,          // Promise.async { }
    EXTERNAL_SERVICE,       // Returned from injected service
    HTTP_CLIENT,            // HttpClient response
    EXECUTION_FORK,         // Execution.fork()
    UNKNOWN
}

data class BlockingUsageInfo(
    val className: String,
    val methodName: String,
    val blockingType: BlockingType
)

enum class BlockingType {
    BLOCKING_GET,           // Blocking.get { }
    BLOCKING_OP,            // Blocking.op { }
    THREAD_SLEEP,           // Thread.sleep (anti-pattern)
    JDBC_DIRECT,            // Direct JDBC calls outside Blocking
    SYNC_IO                 // Synchronous file I/O
}

data class RegistryAccessInfo(
    val className: String,
    val methodName: String,
    val accessedType: String,                  // Type being pulled from registry
    val accessMethod: String                   // get(), maybeGet(), getAll()
)

// Module/DI analysis
data class RatpackModuleInfo(
    val classInfo: ClassInfo,
    val moduleType: ModuleType,
    val bindings: List<BindingInfo>
)

enum class ModuleType {
    GUICE_MODULE,           // Extends AbstractModule
    RATPACK_MODULE,         // Implements ratpack.guice.ConfigurableModule
    REGISTRY_SPEC           // Direct RegistrySpec configuration
}

data class BindingInfo(
    val boundType: String,                     // Interface/class being bound
    val implementationType: String?,           // Concrete implementation
    val bindingScope: BindingScope,
    val providedBy: String?                    // @Provides method if applicable
)

enum class BindingScope {
    DEFAULT, SINGLETON, REQUEST, EAGER_SINGLETON
}

// Migration complexity assessment
data class MigrationComplexity(
    val score: Double,
    val level: ComplexityLevel,
    val factors: List<ComplexityFactor>,
    val estimatedEffort: String
)

enum class ComplexityLevel { LOW, MEDIUM, HIGH, VERY_HIGH }

data class ComplexityFactor(
    val name: String,
    val contribution: Double,
    val description: String? = null
)
```

---

## Analysis Provider Interface

```kotlin
/**
 * Core abstraction for analysis backends.
 * Phase 1 implements ClassGraphProvider.
 * Phase 2+ can add SourceAnalysisProvider, etc.
 */
interface AnalysisProvider {
    
    /** Unique identifier for this provider */
    val id: String
    
    /** Human-readable name */
    val name: String
    
    /** What types of queries this provider can answer */
    fun capabilities(): Set<AnalysisCapability>
    
    /** Initialize with project classpath. May be slow (scanning). */
    suspend fun initialize(context: ProjectContext): ProviderStatus
    
    /** Check if provider is ready for queries */
    fun status(): ProviderStatus
    
    /** Execute a query. Returns null if this provider can't handle the query type. */
    suspend fun <T : AnalysisResult> query(request: AnalysisRequest<T>): T?
    
    /** Release resources */
    fun close()
}

enum class AnalysisCapability {
    // Structural (ClassGraph can do all of these)
    CLASS_LOOKUP,           // Find class by FQN
    CLASS_SEARCH,           // Search classes by pattern/criteria
    HIERARCHY,              // Superclass/interface relationships
    IMPLEMENTATIONS,        // Find implementors of interface
    INCOMING_REFERENCES,    // What references this class
    OUTGOING_REFERENCES,    // What this class references
    ANNOTATION_SEARCH,      // Find classes with annotation
    METHOD_SEARCH,          // Find methods by signature/annotation
    
    // Ratpack-specific structural
    RATPACK_HANDLERS,       // Find Handler implementations
    RATPACK_MODULES,        // Find Guice modules
    RATPACK_PROMISE_USAGE,  // Detect Promise type usage (structural only)
    RATPACK_BLOCKING_USAGE, // Detect Blocking.* usage
    
    // Source-level (Phase 2+)
    SOURCE_CONTENT,         // Get actual source code
    PROMISE_CHAIN_ANALYSIS, // Semantic Promise chain analysis
    LAMBDA_BODY_ANALYSIS,   // What happens inside lambdas
    CONTROL_FLOW            // Control flow within methods
}

sealed class ProviderStatus {
    object Uninitialized : ProviderStatus()
    object Initializing : ProviderStatus()
    data class Ready(val stats: ProviderStats) : ProviderStatus()
    data class Error(val message: String, val cause: Throwable?) : ProviderStatus()
}

data class ProviderStats(
    val classesIndexed: Int,
    val initializationTimeMs: Long,
    val memoryUsageMb: Int?
)
```

### Query Request/Response Pattern

```kotlin
/**
 * Type-safe query pattern.
 * Each query type defines its parameters and expected result type.
 */
sealed class AnalysisRequest<T : AnalysisResult> {
    abstract val capability: AnalysisCapability
}

sealed class AnalysisResult

// Example queries
data class ClassLookupRequest(
    val fqn: String
) : AnalysisRequest<ClassLookupResult>() {
    override val capability = AnalysisCapability.CLASS_LOOKUP
}

data class ClassLookupResult(
    val classInfo: ClassInfo?
) : AnalysisResult()

data class ImplementationsRequest(
    val interfaceFqn: String,
    val includeAbstract: Boolean = false,
    val projectOnly: Boolean = true
) : AnalysisRequest<ImplementationsResult>() {
    override val capability = AnalysisCapability.IMPLEMENTATIONS
}

data class ImplementationsResult(
    val interfaceFqn: String,
    val implementations: List<ClassInfo>
) : AnalysisResult()

data class ReferencesRequest(
    val classFqn: String,
    val direction: ReferenceDirection,
    val referenceTypes: Set<ReferenceType>? = null,
    val projectOnly: Boolean = true,
    val maxDepth: Int = 1
) : AnalysisRequest<ReferencesResult>() {
    override val capability = when (direction) {
        ReferenceDirection.INCOMING -> AnalysisCapability.INCOMING_REFERENCES
        ReferenceDirection.OUTGOING -> AnalysisCapability.OUTGOING_REFERENCES
    }
}

enum class ReferenceDirection { INCOMING, OUTGOING }

data class ReferencesResult(
    val classFqn: String,
    val direction: ReferenceDirection,
    val references: List<ClassReference>,
    val depth: Int
) : AnalysisResult()

// Ratpack-specific queries
data class RatpackHandlersRequest(
    val projectOnly: Boolean = true
) : AnalysisRequest<RatpackHandlersResult>() {
    override val capability = AnalysisCapability.RATPACK_HANDLERS
}

data class RatpackHandlersResult(
    val handlers: List<RatpackHandlerInfo>
) : AnalysisResult()

data class RatpackPromiseUsageRequest(
    val className: String? = null,
    val projectOnly: Boolean = true
) : AnalysisRequest<RatpackPromiseUsageResult>() {
    override val capability = AnalysisCapability.RATPACK_PROMISE_USAGE
}

data class RatpackPromiseUsageResult(
    val usages: List<PromiseUsageInfo>,
    val summary: PromiseUsageSummary
) : AnalysisResult()

data class PromiseUsageSummary(
    val totalClasses: Int,
    val totalUsages: Int,
    val bySource: Map<PromiseSource, Int>,
    val classesWithBlocking: Int
)
```

---

## Analysis Facade

```kotlin
/**
 * Coordinates queries across providers.
 * Single instance per server process, bound to one project.
 */
class AnalysisFacade(
    private val projectResolver: ProjectResolver,
    private val providers: List<AnalysisProvider>
) : Closeable {
    private var projectContext: ProjectContext? = null
    private val providerStates = ConcurrentHashMap<String, ProviderStatus>()
    private val status = AtomicReference(FacadeStatus.UNINITIALIZED)
    
    enum class FacadeStatus {
        UNINITIALIZED, LOADING, READY, ERROR
    }
    
    /**
     * Load the project. Called once at server startup.
     * Can be called again for refresh (re-scans same project).
     */
    suspend fun loadProject(projectPath: Path): ProjectContext {
        status.set(FacadeStatus.LOADING)
        
        try {
            // 1. Resolve project structure and classpath
            val context = projectResolver.resolve(projectPath)
            
            // 2. Initialize providers (potentially in parallel)
            coroutineScope {
                providers.map { provider ->
                    async {
                        providerStates[provider.id] = ProviderStatus.Initializing
                        providerStates[provider.id] = provider.initialize(context)
                    }
                }.awaitAll()
            }
            
            projectContext = context
            status.set(FacadeStatus.READY)
            return context
        } catch (e: Exception) {
            status.set(FacadeStatus.ERROR)
            throw e
        }
    }
    
    /**
     * Execute a query, routing to appropriate provider(s).
     * Throws if not ready.
     */
    suspend fun <T : AnalysisResult> query(request: AnalysisRequest<T>): T {
        check(status.get() == FacadeStatus.READY) { 
            "Analysis not ready. Current status: ${status.get()}" 
        }
        
        val provider = providers.find { 
            request.capability in it.capabilities() && 
            it.status() is ProviderStatus.Ready 
        } ?: throw UnsupportedOperationException(
            "No provider available for ${request.capability}"
        )
        
        return provider.query(request) 
            ?: throw IllegalStateException("Provider returned null for supported capability")
    }
    
    /**
     * Refresh analysis (re-scan after code changes).
     * Re-scans the SAME project path (single-project model).
     */
    suspend fun refresh(): ProjectContext {
        val path = projectContext?.projectPath 
            ?: throw IllegalStateException("No project loaded")
        return loadProject(path)
    }
    
    fun getProjectContext(): ProjectContext? = projectContext
    fun getStatus(): FacadeStatus = status.get()
    fun isReady(): Boolean = status.get() == FacadeStatus.READY
    
    fun availableCapabilities(): Map<AnalysisCapability, List<String>> {
        return providers
            .filter { it.status() is ProviderStatus.Ready }
            .flatMap { provider -> 
                provider.capabilities().map { cap -> cap to provider.id }
            }
            .groupBy({ it.first }, { it.second })
    }
    
    fun providerStatuses(): Map<String, ProviderStatus> = providerStates.toMap()
    
    override fun close() {
        providers.forEach { it.close() }
        projectContext = null
        status.set(FacadeStatus.UNINITIALIZED)
    }
}
```

---

## REST API Design

Base URL: `http://localhost:{port}/api/v1`

### Admin & Lifecycle (for CLI integration)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/admin/health` | GET | Health check (always returns 200 if server is up) |
| `/admin/ready` | GET | Readiness check (200 if scan complete, 503 if scanning) |
| `/admin/info` | GET | Server info (version, uptime, project path, idle time) |
| `/admin/activity` | POST | Touch activity timestamp (resets idle timer) |
| `/admin/shutdown` | POST | Graceful shutdown (localhost only) |

**GET /admin/health**
```json
{
    "status": "UP",
    "timestamp": "2026-01-04T10:30:00Z"
}
```

**GET /admin/ready**
```json
// When ready (200 OK)
{
    "ready": true,
    "status": "READY",
    "project": "user-service"
}

// When scanning (503 Service Unavailable)
{
    "ready": false,
    "status": "SCANNING",
    "project": "user-service",
    "progress": "Resolving dependencies..."
}
```

**GET /admin/info**
```json
{
    "version": "1.0.0",
    "apiVersion": "v1",
    "projectPath": "/home/user/work/user-service",
    "projectName": "user-service",
    "port": 8080,
    "host": "127.0.0.1",
    "pid": 12345,
    "startedAt": "2026-01-04T10:30:00Z",
    "uptime": "5m 32s",
    "lastActivityAt": "2026-01-04T10:35:00Z",
    "idleDuration": "0m 28s",
    "idleTimeout": "30m",
    "idleShutdownAt": "2026-01-04T11:05:00Z",
    "status": "READY",
    "providers": {
        "classgraph": {
            "status": "READY",
            "classesIndexed": 1847,
            "initTimeMs": 3420
        }
    }
}
```

**POST /admin/activity**
```json
// Request: empty body

// Response (200 OK)
{
    "lastActivityAt": "2026-01-04T10:35:28Z"
}
```

### Project Status (Read-Only, Single Project)

Since the project is bound at startup, these endpoints are read-only status checks.

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/project` | GET | Get current project status and stats |
| `/project/refresh` | POST | Re-scan the project (after code changes) |
| `/project/capabilities` | GET | List available analysis capabilities |

**GET /project**
```json
{
    "status": "READY",
    "project": {
        "name": "user-service",
        "path": "/home/user/work/user-service",
        "modules": [
            {
                "name": "app",
                "classCount": 234,
                "handlerCount": 12
            },
            {
                "name": "core",
                "classCount": 89,
                "handlerCount": 0
            }
        ],
        "totalClasses": 323,
        "projectClasses": 323,
        "libraryClasses": 1524,
        "loadedAt": "2026-01-04T10:30:00Z",
        "lastRefreshed": "2026-01-04T10:30:00Z"
    },
    "ratpackSummary": {
        "handlers": 24,
        "modules": 8,
        "promiseUsages": 156,
        "blockingCalls": 89
    }
}
```

**POST /project/refresh**
```json
// Request: empty body

// Response (during refresh, returns 202 Accepted)
{
    "status": "SCANNING",
    "message": "Refresh in progress..."
}

// Response (when complete, returns 200 OK)
{
    "status": "READY",
    "refreshedAt": "2026-01-04T10:35:00Z",
    "duration": "0.8s",
    "changes": {
        "classesAdded": 2,
        "classesRemoved": 0,
        "classesModified": 5
    }
}
```

### Class Queries

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/classes` | GET | List/search classes |
| `/classes/{fqn}` | GET | Get specific class details |
| `/classes/{fqn}/dependencies` | GET | Get dependency graph |

**GET /classes?package=com.example&kind=CLASS&projectOnly=true**
```json
{
    "classes": [
        {
            "fqn": "com.example.UserHandler",
            "simpleName": "UserHandler",
            "packageName": "com.example",
            "kind": "CLASS",
            "isProjectCode": true
        }
    ],
    "total": 45,
    "filtered": 12
}
```

**GET /classes/com.example.UserHandler**
```json
{
    "fqn": "com.example.UserHandler",
    "simpleName": "UserHandler",
    "packageName": "com.example",
    "kind": "CLASS",
    "modifiers": ["PUBLIC"],
    "superclass": "java.lang.Object",
    "interfaces": ["ratpack.handling.Handler"],
    "annotations": [
        { "fqn": "javax.inject.Singleton", "parameters": {} }
    ],
    "methods": [
        {
            "name": "handle",
            "signature": "handle(ratpack.handling.Context)",
            "returnType": "void",
            "modifiers": ["PUBLIC"],
            "annotations": []
        }
    ],
    "fields": [
        {
            "name": "userService",
            "type": "com.example.UserService",
            "modifiers": ["PRIVATE", "FINAL"]
        }
    ],
    "location": {
        "type": "DIRECTORY",
        "path": "build/classes/kotlin/main",
        "isProjectCode": true
    }
}
```

**GET /classes/com.example.UserHandler/dependencies?direction=outgoing&depth=2**
```json
{
    "rootClass": "com.example.UserHandler",
    "direction": "OUTGOING",
    "depth": 2,
    "references": [
        {
            "sourceClass": "com.example.UserHandler",
            "targetClass": "com.example.UserService",
            "referenceTypes": ["FIELD_TYPE", "METHOD_CALL"],
            "isProjectCode": true
        },
        {
            "sourceClass": "com.example.UserHandler",
            "targetClass": "ratpack.handling.Context",
            "referenceTypes": ["METHOD_PARAMETER"],
            "isProjectCode": false
        }
    ],
    "summary": {
        "totalReferences": 12,
        "projectReferences": 8,
        "libraryReferences": 4
    }
}
```

### Ratpack-Specific Queries

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/ratpack/handlers` | GET | List all handlers with complexity |
| `/ratpack/handlers/{fqn}` | GET | Detailed handler analysis |
| `/ratpack/modules` | GET | List Guice modules and bindings |
| `/ratpack/promises` | GET | Promise usage summary |
| `/ratpack/promises/{className}` | GET | Promise usage in specific class |
| `/ratpack/migration-report` | GET | Migration planning report |

**GET /ratpack/handlers?complexity=HIGH**
```json
{
    "handlers": [
        {
            "fqn": "com.example.OrderHandler",
            "handlerType": "HANDLER_INTERFACE",
            "complexity": {
                "score": 5.5,
                "level": "HIGH",
                "factors": [
                    { "name": "Execution.fork()", "contribution": 3.0 },
                    { "name": "Promise operators", "contribution": 1.5 },
                    { "name": "Registry lookups", "contribution": 1.0 }
                ],
                "estimatedEffort": "~1 day"
            }
        }
    ],
    "summary": {
        "total": 24,
        "byComplexity": {
            "LOW": 8,
            "MEDIUM": 10,
            "HIGH": 5,
            "VERY_HIGH": 1
        }
    }
}
```

**GET /ratpack/migration-report?target=kotlin-coroutines**
```json
{
    "project": "user-service",
    "targetFramework": "kotlin-coroutines",
    "generatedAt": "2026-01-04T10:30:00Z",
    "summary": {
        "totalHandlers": 24,
        "estimatedTotalEffort": "~3 weeks",
        "recommendedOrder": "complexity-asc"
    },
    "byComplexity": {
        "LOW": {
            "count": 8,
            "handlers": ["HealthHandler", "ConfigHandler", "..."],
            "estimatedEffort": "~1 day",
            "recommendation": "Start here. Direct port with minimal changes."
        },
        "MEDIUM": {
            "count": 10,
            "handlers": ["UserHandler", "OrderHandler", "..."],
            "estimatedEffort": "~5 days",
            "recommendation": "Replace Blocking.get with withContext(Dispatchers.IO)"
        },
        "HIGH": {
            "count": 5,
            "handlers": ["PaymentHandler", "ReportHandler", "..."],
            "estimatedEffort": "~1 week",
            "recommendation": "Promise chains need careful unwinding. Consider parallel async{}"
        },
        "VERY_HIGH": {
            "count": 1,
            "handlers": ["BatchProcessingHandler"],
            "estimatedEffort": "~1 week",
            "recommendation": "Contains Execution.fork(). Needs architectural redesign."
        }
    },
    "antiPatterns": [
        {
            "type": "JDBC_WITHOUT_BLOCKING",
            "classes": ["LegacyReportHandler"],
            "recommendation": "Wrap in Blocking.get before migration, or convert to R2DBC"
        },
        {
            "type": "THREAD_SLEEP",
            "classes": ["BatchProcessingHandler"],
            "recommendation": "Replace with delay() in coroutines"
        }
    ]
}
```

---

## Project Structure (Monorepo)

```
codelens/
├── README.md
├── pyproject.toml                      # Python CLI package (uv/pip)
├── uv.lock
│
├── cli/                                # Python CLI source
│   ├── __init__.py
│   ├── main.py                         # Typer app entry point
│   ├── commands/
│   │   ├── __init__.py
│   │   ├── lifecycle.py                # start, stop, status, restart, refresh
│   │   ├── handlers.py                 # handlers, handler
│   │   ├── analysis.py                 # promises, modules, report
│   │   └── classes.py                  # classes, class, deps
│   ├── server/
│   │   ├── __init__.py
│   │   ├── manager.py                  # Server lifecycle management
│   │   ├── discovery.py                # Find running servers
│   │   └── client.py                   # HTTP client for server API
│   ├── output/
│   │   ├── __init__.py
│   │   ├── formatter.py                # TTY detection, format selection
│   │   └── tables.py                   # Rich table rendering
│   └── config.py                       # Configuration loading
│
├── build.gradle.kts                    # Root Gradle build
├── settings.gradle.kts                 # Module definitions + version catalog
├── gradle/
│   └── libs.versions.toml              # Dependency versions
├── gradlew
│
├── core/                               # Kotlin: Core models and interfaces
│   ├── build.gradle.kts
│   └── src/main/kotlin/
│       └── com/example/codelens/
│           ├── model/                  # Data classes (ClassInfo, etc.)
│           ├── api/                    # AnalysisProvider, AnalysisRequest
│           └── util/                   # Shared utilities
│
├── gradle-resolver/                    # Kotlin: Gradle Tooling API integration
│   ├── build.gradle.kts
│   └── src/main/kotlin/
│       └── com/example/codelens/gradle/
│           ├── ProjectResolver.kt
│           └── GradleToolingClient.kt
│
├── classgraph-provider/                # Kotlin: ClassGraph analysis provider
│   ├── build.gradle.kts
│   └── src/main/kotlin/
│       └── com/example/codelens/classgraph/
│           ├── ClassGraphProvider.kt
│           └── RatpackDetectors.kt
│
└── server/                             # Kotlin: HTTP server (Ktor)
    ├── build.gradle.kts                # Includes shadowJar for fat JAR
    └── src/main/kotlin/
        └── com/example/codelens/server/
            ├── Application.kt          # Entry point, CODELENS_READY signal
            ├── ServerConfig.kt
            ├── LifecycleManager.kt     # Idle monitoring, shutdown
            ├── routes/
            │   ├── AdminRoutes.kt
            │   ├── ProjectRoutes.kt
            │   ├── ClassRoutes.kt
            │   └── RatpackRoutes.kt
            └── services/
                ├── QueryService.kt
                └── MigrationReportService.kt
```

### Key Files

**`pyproject.toml`** (Python CLI):
```toml
[project]
name = "codelens"
version = "0.1.0"
description = "Ratpack migration analysis tool"
requires-python = ">=3.11"
dependencies = [
    "typer>=0.9.0",
    "rich>=13.0.0",
    "httpx>=0.25.0",
    "pyyaml>=6.0",
]

[project.scripts]
codelens = "cli.main:app"

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"
```

**`settings.gradle.kts`** (Kotlin modules):
```kotlin
rootProject.name = "codelens"

include(":core")
include(":gradle-resolver")
include(":classgraph-provider")
include(":server")
```

---

## Implementation Milestones

### Milestone 1: Project Skeleton & Server Basics (Week 1)

**Deliverables:**
- [ ] Monorepo setup: Gradle for Kotlin, pyproject.toml for Python CLI
- [ ] Ktor server with admin endpoints (`/health`, `/ready`, `/info`, `/activity`, `/shutdown`)
- [ ] `ServerConfig` parsing from command line (including `--idle-timeout`)
- [ ] `CODELENS_READY` output protocol
- [ ] Graceful shutdown handling
- [ ] Idle timeout monitoring
- [ ] Basic DI setup with Koin

**Acceptance Criteria:**
```bash
# Can start server via Gradle
./gradlew :server:run --args="--project ~/work/test-ratpack-app"
# Outputs: CODELENS_READY port=8080 host=127.0.0.1 version=1.0.0

# Admin endpoints respond
curl localhost:8080/admin/health  # Returns 200
curl localhost:8080/admin/info    # Returns server info with idle status

# Graceful shutdown
curl -X POST localhost:8080/admin/shutdown
# Server exits cleanly
```

### Milestone 2: CLI Core & Server Management (Week 1-2)

**Deliverables:**
- [ ] Python CLI skeleton with Typer
- [ ] Configuration loading (`~/.config/codelens/config.yml`)
- [ ] Server discovery in `~/.cache/codelens/servers/`
- [ ] `codelens start` / `stop` / `status` commands
- [ ] Auto-start on query commands
- [ ] `codelens list` (all running servers)
- [ ] Output formatting (Rich tables + JSON)

**Acceptance Criteria:**
```bash
# Install CLI
uv tool install --editable .

# Auto-start works
cd ~/work/test-ratpack-app
codelens status
# Outputs: Starting server... Server running on port 8080

# List shows running servers
codelens list
# Shows table of running servers

# JSON output works
codelens status --json
# Returns JSON
```

### Milestone 3: Gradle Resolution (Week 2)

**Deliverables:**
- [ ] Gradle Tooling API client
- [ ] Classpath extraction (compile + runtime dependencies)
- [ ] Multi-module project support
- [ ] Source root detection
- [ ] Error handling for Gradle failures

**Acceptance Criteria:**
```bash
# Server resolves project
codelens status
# Shows: Classes indexed: 1,847
```

### Milestone 4: ClassGraph Provider & Basic Queries (Week 2-3)

**Deliverables:**
- [ ] ClassGraphProvider implementation
- [ ] `/classes` endpoints (list, search, get by FQN)
- [ ] `/classes/{fqn}/dependencies` endpoint
- [ ] Project vs library code differentiation
- [ ] CLI commands: `codelens classes`, `codelens class`, `codelens deps`

**Acceptance Criteria:**
```bash
# Can query classes via CLI
codelens classes --package "com.example.*"
codelens class com.example.UserHandler
codelens deps com.example.UserHandler --direction outgoing
```

### Milestone 5: Ratpack Detection (Week 3)

**Deliverables:**
- [ ] Handler detection (`/ratpack/handlers`)
- [ ] Module detection (`/ratpack/modules`)
- [ ] Promise usage detection (`/ratpack/promises`)
- [ ] Blocking usage detection
- [ ] Complexity scoring
- [ ] CLI commands: `codelens handlers`, `codelens handler`, `codelens promises`, `codelens modules`

**Acceptance Criteria:**
```bash
# Can find handlers with complexity
codelens handlers
codelens handlers --complexity HIGH
codelens handler UserHandler --json
```

### Milestone 6: Migration Report & Polish (Week 3-4)

**Deliverables:**
- [ ] `/ratpack/migration-report` endpoint
- [ ] Anti-pattern detection
- [ ] `/project/refresh` endpoint
- [ ] CLI: `codelens report`, `codelens refresh`
- [ ] Documentation (README, CLI help)
- [ ] Error handling polish

**Acceptance Criteria:**
```bash
# Full migration report
codelens report --target kotlin-coroutines
codelens report --target kotlin-coroutines -o report.md --format markdown

# Refresh after code changes
./gradlew build
codelens refresh
```

---

## Ratpack Detection Heuristics (ClassGraph-Based)

### Type Constants

```kotlin
object RatpackTypes {
    const val HANDLER = "ratpack.handling.Handler"
    const val CONTEXT = "ratpack.handling.Context"
    const val PROMISE = "ratpack.exec.Promise"
    const val BLOCKING = "ratpack.exec.Blocking"
    const val EXECUTION = "ratpack.exec.Execution"
    const val CHAIN = "ratpack.handling.Chain"
    const val ACTION = "ratpack.func.Action"
    const val GUICE_MODULE = "com.google.inject.Module"
    const val GUICE_ABSTRACT_MODULE = "com.google.inject.AbstractModule"
    const val CONFIGURABLE_MODULE = "ratpack.guice.ConfigurableModule"
}
```

### Handler Detection

```kotlin
fun detectHandlers(scanResult: ScanResult): List<RatpackHandlerInfo> {
    val handlers = mutableListOf<RatpackHandlerInfo>()
    
    // 1. Classes implementing Handler interface
    scanResult.getClassesImplementing(RatpackTypes.HANDLER)
        .filter { it.isPublic && !it.isAbstract }
        .forEach { classInfo ->
            handlers.add(RatpackHandlerInfo(
                classInfo = toClassInfo(classInfo),
                handlerType = HandlerType.HANDLER_INTERFACE,
                promiseUsages = detectPromiseUsage(classInfo),
                registryAccess = detectRegistryAccess(classInfo),
                blockingUsages = detectBlockingUsage(classInfo),
                migrationComplexity = calculateComplexity(classInfo)
            ))
        }
    
    // 2. Classes implementing Action<Chain>
    scanResult.getClassesImplementing(RatpackTypes.ACTION)
        .filter { classInfo ->
            classInfo.typeSignature?.superinterfaceSignatures?.any { sig ->
                sig.toString().contains("Action<") && 
                sig.toString().contains("Chain")
            } == true
        }
        .forEach { classInfo ->
            handlers.add(RatpackHandlerInfo(
                classInfo = toClassInfo(classInfo),
                handlerType = HandlerType.CHAIN_ACTION,
                // ... same detection logic
            ))
        }
    
    return handlers
}
```

### Complexity Calculation

```kotlin
fun calculateMigrationComplexity(handler: RatpackHandlerInfo): MigrationComplexity {
    var score = 1.0  // Base score
    val factors = mutableListOf<ComplexityFactor>()
    
    // Promise usage adds complexity
    handler.promiseUsages.forEach { usage ->
        when (usage.promiseSource) {
            PromiseSource.BLOCKING_GET -> {
                score += 0.5
                factors.add(ComplexityFactor("Blocking.get()", 0.5))
            }
            PromiseSource.EXECUTION_FORK -> {
                score += 3.0
                factors.add(ComplexityFactor("Execution.fork()", 3.0))
            }
            PromiseSource.PROMISE_ASYNC -> {
                score += 2.0
                factors.add(ComplexityFactor("Promise.async()", 2.0))
            }
            else -> {
                score += 0.5
                factors.add(ComplexityFactor("External Promise", 0.5))
            }
        }
    }
    
    // Unwrapped blocking calls (anti-pattern)
    handler.blockingUsages
        .filter { it.blockingType == BlockingType.JDBC_DIRECT }
        .forEach {
            score += 1.5
            factors.add(ComplexityFactor("Unwrapped JDBC", 1.5))
        }
    
    // Registry access adds complexity (dynamic DI)
    if (handler.registryAccess.isNotEmpty()) {
        val registryScore = 0.5 * handler.registryAccess.size
        score += registryScore
        factors.add(ComplexityFactor("Registry lookups", registryScore))
    }
    
    val level = when {
        score <= 2.0 -> ComplexityLevel.LOW
        score <= 4.0 -> ComplexityLevel.MEDIUM
        score <= 7.0 -> ComplexityLevel.HIGH
        else -> ComplexityLevel.VERY_HIGH
    }
    
    return MigrationComplexity(
        score = score,
        level = level,
        factors = factors,
        estimatedEffort = estimateEffort(level)
    )
}

fun estimateEffort(level: ComplexityLevel): String {
    return when (level) {
        ComplexityLevel.LOW -> "~1 hour"
        ComplexityLevel.MEDIUM -> "~4 hours"
        ComplexityLevel.HIGH -> "~1 day"
        ComplexityLevel.VERY_HIGH -> "~1 week"
    }
}
```

---

## Success Criteria

**Functional:**
- [ ] Server starts with project path, creates discovery file
- [ ] Server shuts down gracefully, cleans up discovery file
- [ ] Can scan a real Ratpack project (use test fixture + real SmartThings repo)
- [ ] Class queries return accurate structural data
- [ ] Handler detection finds all Handler implementations
- [ ] Complexity scoring produces actionable categorization
- [ ] Migration report provides useful planning data

**Non-Functional:**
- [ ] Startup time < 10s for typical project (~500 classes)
- [ ] Query response time < 100ms for cached data
- [ ] Memory usage reasonable (< 512MB for typical project)

**Usability:**
- [ ] CLI provides good UX with Rich tables
- [ ] Error messages are actionable with suggestions
- [ ] `--json` output is parseable by Claude Code

---

## Phase 2 Preview (Out of Scope for Phase 1)

### Phase 2A: Source Analysis Provider
- **Source Analysis Provider:** JavaParser or Kotlin Analysis API
- **Promise Chain Analysis:** Full chain traversal with complexity scoring
- **Lambda Body Inspection:** What happens inside Promise operators
- Method-level analysis (currently limited to class-level)

### Phase 2B: MCP Server (Deferred)
- MCP tool definitions wrapping CLI or HTTP API
- Optimized response formatting for LLM consumption
- Integration with Claude Code for migration assistance

### Phase 2C: Advanced Features
- File watching / automatic refresh on source changes
- Migration code generation hints
- OpenRewrite recipe generation
- Fleet-wide analysis (orchestrate analysis across all 55 repos)
- Shell completion (bash, zsh, fish)
- Distribution via PyPI or standalone binary

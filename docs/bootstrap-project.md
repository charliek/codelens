# CodeLens Bootstrap Plan

## Project Overview

CodeLens is a developer tool for analyzing Ratpack-based JVM codebases to assist with migration planning. It consists of two components:

1. **Server** (Kotlin/Ktor): Runs in the background, loads a target project's bytecode using ClassGraph, and serves analysis queries via HTTP REST API
2. **CLI** (Python/Typer): User-facing command-line interface that manages server lifecycle and presents analysis results

This plan bootstraps the project structure with end-to-end connectivity between CLI and server, using stub implementations that will be filled in with real analysis logic later.

---

## Architecture Summary

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Developer Machine                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   Terminal                           Background Process                     │
│   ┌─────────────────────┐           ┌─────────────────────────────────┐    │
│   │  $ codelens status  │           │  CodeLens Server (Kotlin/Ktor)  │    │
│   │                     │──HTTP────▶│  - Loads target project         │    │
│   │  Python CLI         │◀─────────│  - Serves /api/v1/* endpoints   │    │
│   │  - Manages server   │           │  - Auto-shuts down when idle    │    │
│   │  - Formats output   │           └─────────────────────────────────┘    │
│   └─────────────────────┘                         │                         │
│            │                                      │ Analyzes                │
│            │                                      ▼                         │
│            │                    ┌─────────────────────────────────┐        │
│            │                    │  Target Ratpack Project         │        │
│   ┌────────▼────────┐          │  ~/work/user-service/           │        │
│   │ ~/.cache/codelens│          │  - build.gradle.kts             │        │
│   │ └─ servers/*.json│          │  - build/classes/...            │        │
│   │ └─ logs/*.log    │          └─────────────────────────────────┘        │
│   └─────────────────┘                                                       │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Repository Structure (Target State)

```
codelens/
├── README.md
├── .gitignore
│
├── settings.gradle.kts              # Gradle multi-module config
├── build.gradle.kts                 # Root build (shared config)
├── gradle/
│   ├── wrapper/                     # Gradle wrapper
│   └── libs.versions.toml           # Version catalog
├── gradlew
├── gradlew.bat
│
├── server/                          # Kotlin server modules
│   ├── core/                        # Shared models and interfaces
│   │   ├── build.gradle.kts
│   │   └── src/main/kotlin/
│   │       └── codelens/core/
│   │           ├── model/           # Data classes (ClassInfo, HandlerInfo, etc.)
│   │           └── api/             # AnalysisProvider interface
│   │
│   ├── classgraph/                  # ClassGraph-based analysis (stub for now)
│   │   ├── build.gradle.kts
│   │   └── src/main/kotlin/
│   │       └── codelens/classgraph/
│   │           └── ClassGraphProvider.kt
│   │
│   └── app/                         # HTTP server application
│       ├── build.gradle.kts         # Produces fat JAR via shadowJar
│       └── src/main/kotlin/
│           └── codelens/server/
│               ├── Application.kt   # Main entry point
│               ├── Config.kt        # CLI argument parsing
│               ├── routes/          # Ktor route definitions
│               │   ├── AdminRoutes.kt
│               │   └── ProjectRoutes.kt
│               └── services/
│                   └── AnalysisService.kt
│
├── cli/                             # Python CLI
│   ├── pyproject.toml               # UV/Python project config
│   ├── src/
│   │   └── codelens_cli/
│   │       ├── __init__.py
│   │       ├── main.py              # Typer app entry point
│   │       ├── config.py            # Configuration loading
│   │       ├── state.py             # State file management
│   │       ├── client.py            # HTTP client for server API
│   │       ├── server.py            # Server process management
│   │       ├── output.py            # Rich formatting utilities
│   │       └── commands/
│   │           ├── __init__.py
│   │           ├── lifecycle.py     # start, stop, status, restart
│   │           └── project.py       # project info (stub)
│   └── tests/
│       └── test_basic.py
│
└── test-fixtures/                   # Sample Ratpack project for testing
    └── sample-ratpack-app/
        ├── build.gradle.kts
        └── src/main/kotlin/...
```

---

## Phase 0: Bootstrap Scope

This phase establishes end-to-end connectivity. After completion:

1. ✅ `codelens start` starts the Kotlin server for a project directory
2. ✅ `codelens status` shows server status (running/stopped, port, uptime)
3. ✅ `codelens stop` gracefully stops the server
4. ✅ `codelens project` calls a stub endpoint and displays response
5. ✅ Server auto-shuts down after idle timeout
6. ✅ Multiple servers can run for different projects

### What's Stubbed (for later phases)

- ClassGraph integration (returns mock data)
- Gradle Tooling API integration (server accepts any directory)
- Ratpack-specific analysis endpoints
- Real complexity scoring

---

## Technology Stack

### Server (Kotlin)

| Component | Choice | Version |
|-----------|--------|---------|
| Language | Kotlin | 2.0+ |
| Build | Gradle + Kotlin DSL | 8.x |
| HTTP Framework | Ktor | 3.0+ |
| Serialization | kotlinx.serialization | 1.6+ |
| CLI Parsing | kotlinx-cli | 0.3+ |
| JVM Target | 21 | LTS |

### CLI (Python)

| Component | Choice | Version |
|-----------|--------|---------|
| Language | Python | 3.11+ |
| Package Manager | UV | latest |
| CLI Framework | Typer | 0.12+ |
| Terminal UI | Rich | 13+ |
| HTTP Client | httpx | 0.27+ |
| Config | PyYAML | 6+ |

---

## Detailed Implementation Plan

### Step 1: Initialize Repository Structure

Create the mono-repo with Gradle wrapper and Python project.

**Files to create:**

```
codelens/
├── .gitignore
├── README.md
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
└── cli/pyproject.toml
```

**.gitignore:**
```gitignore
# Gradle
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar

# Kotlin
*.class
*.jar
*.war
*.ear

# Python
__pycache__/
*.py[cod]
*$py.class
.venv/
*.egg-info/
dist/

# IDE
.idea/
*.iml
.vscode/

# OS
.DS_Store
Thumbs.db

# CodeLens state (development)
.codelens/
```

**settings.gradle.kts:**
```kotlin
rootProject.name = "codelens"

include("server:core")
include("server:classgraph")
include("server:app")
```

**build.gradle.kts (root):**
```kotlin
plugins {
    kotlin("jvm") version libs.versions.kotlin apply false
    kotlin("plugin.serialization") version libs.versions.kotlin apply false
}

allprojects {
    group = "dev.codelens"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "21"
            freeCompilerArgs = listOf("-Xjsr305=strict")
        }
    }
}
```

**gradle/libs.versions.toml:**
```toml
[versions]
kotlin = "2.0.21"
ktor = "3.0.2"
kotlinx-serialization = "1.7.3"
kotlinx-cli = "0.3.6"
logback = "1.5.12"
classgraph = "4.8.177"

[libraries]
ktor-server-core = { module = "io.ktor:ktor-server-core", version.ref = "ktor" }
ktor-server-netty = { module = "io.ktor:ktor-server-netty", version.ref = "ktor" }
ktor-server-content-negotiation = { module = "io.ktor:ktor-server-content-negotiation", version.ref = "ktor" }
ktor-serialization-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-server-status-pages = { module = "io.ktor:ktor-server-status-pages", version.ref = "ktor" }

kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
kotlinx-cli = { module = "org.jetbrains.kotlinx:kotlinx-cli", version.ref = "kotlinx-cli" }

logback-classic = { module = "ch.qos.logback:logback-classic", version.ref = "logback" }
classgraph = { module = "io.github.classgraph:classgraph", version.ref = "classgraph" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
shadow = { id = "com.github.johnrengelman.shadow", version = "8.1.1" }
```

**cli/pyproject.toml:**
```toml
[project]
name = "codelens-cli"
version = "0.1.0"
description = "CLI for CodeLens - Ratpack migration analysis tool"
readme = "README.md"
requires-python = ">=3.11"
dependencies = [
    "typer>=0.12.0",
    "rich>=13.0.0",
    "httpx>=0.27.0",
    "pyyaml>=6.0",
]

[project.scripts]
codelens = "codelens_cli.main:app"

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["src/codelens_cli"]
```

### Step 2: Implement Server Core Module

Shared models and interfaces.

**server/core/build.gradle.kts:**
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)
}
```

**server/core/src/main/kotlin/codelens/core/model/Models.kt:**
```kotlin
package codelens.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ProjectInfo(
    val name: String,
    val path: String,
    val status: ProjectStatus,
    val classCount: Int? = null,
    val handlerCount: Int? = null,
    val scannedAt: String? = null
)

@Serializable
enum class ProjectStatus {
    LOADING,
    READY,
    ERROR
}

@Serializable
data class ServerInfo(
    val version: String,
    val apiVersion: String,
    val projectPath: String,
    val projectName: String,
    val port: Int,
    val host: String,
    val status: String,
    val startedAt: String,
    val uptime: String,
    val lastActivityAt: String,
    val idleDuration: String,
    val idleTimeout: String
)

@Serializable
data class HealthResponse(
    val status: String,
    val timestamp: String
)

@Serializable
data class ReadyResponse(
    val ready: Boolean,
    val status: String,
    val project: String
)

@Serializable
data class ErrorResponse(
    val error: Boolean = true,
    val code: Int,
    val type: String,
    val message: String
)
```

### Step 3: Implement Server App Module

The HTTP server with Ktor.

**server/app/build.gradle.kts:**
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    application
}

application {
    mainClass.set("codelens.server.ApplicationKt")
}

dependencies {
    implementation(project(":server:core"))
    implementation(project(":server:classgraph"))
    
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.status.pages)
    
    implementation(libs.kotlinx.cli)
    implementation(libs.logback.classic)
}

tasks.shadowJar {
    archiveBaseName.set("codelens-server")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
}
```

**server/app/src/main/kotlin/codelens/server/Application.kt:**
```kotlin
package codelens.server

import codelens.server.routes.adminRoutes
import codelens.server.routes.projectRoutes
import codelens.server.services.AnalysisService
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import kotlinx.cli.*
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val config = parseArgs(args)
    
    // Validate project path
    val projectDir = File(config.projectPath)
    if (!projectDir.exists() || !projectDir.isDirectory) {
        System.err.println("Error: Project path does not exist: ${config.projectPath}")
        exitProcess(1)
    }
    
    val hasBuildFile = projectDir.resolve("build.gradle").exists() ||
                       projectDir.resolve("build.gradle.kts").exists()
    if (!hasBuildFile) {
        System.err.println("Error: No build.gradle or build.gradle.kts found in ${config.projectPath}")
        exitProcess(1)
    }
    
    val analysisService = AnalysisService(projectDir)
    val activityTracker = ActivityTracker()
    
    // Find available port
    val port = config.port ?: findAvailablePort(config.portRangeStart, config.portRangeEnd)
    
    val server = embeddedServer(Netty, port = port, host = config.host) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                encodeDefaults = true
            })
        }
        
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respond(
                    HttpStatusCode.InternalServerError,
                    codelens.core.model.ErrorResponse(
                        code = 500,
                        type = cause::class.simpleName ?: "Unknown",
                        message = cause.message ?: "Internal server error"
                    )
                )
            }
        }
        
        // Track activity on every request
        intercept(ApplicationCallPipeline.Monitoring) {
            activityTracker.touch()
        }
        
        routing {
            adminRoutes(analysisService, activityTracker, config)
            projectRoutes(analysisService)
        }
    }
    
    // Start idle shutdown monitor
    if (config.idleTimeoutMinutes > 0) {
        startIdleMonitor(activityTracker, Duration.ofMinutes(config.idleTimeoutMinutes.toLong())) {
            System.err.println("Idle timeout reached, shutting down...")
            server.stop(1000, 5000)
            exitProcess(0)
        }
    }
    
    // Add shutdown hook
    Runtime.getRuntime().addShutdownHook(Thread {
        System.err.println("Shutting down...")
        server.stop(1000, 5000)
    })
    
    // Start server
    server.start(wait = false)
    
    // Print ready signal (CLI watches for this on stdout)
    println("CODELENS_READY port=$port host=${config.host} version=0.1.0")
    System.out.flush()
    
    // Block main thread
    Thread.currentThread().join()
}

data class ServerConfig(
    val projectPath: String,
    val port: Int?,
    val host: String,
    val portRangeStart: Int,
    val portRangeEnd: Int,
    val idleTimeoutMinutes: Int
)

fun parseArgs(args: Array<String>): ServerConfig {
    val parser = ArgParser("codelens-server")
    
    val projectPath by parser.option(
        ArgType.String,
        shortName = "p",
        fullName = "project",
        description = "Path to target project directory"
    ).required()
    
    val port by parser.option(
        ArgType.Int,
        fullName = "port",
        description = "Port to listen on (auto-assigns if not specified)"
    )
    
    val host by parser.option(
        ArgType.String,
        fullName = "host",
        description = "Host to bind to"
    ).default("127.0.0.1")
    
    val idleTimeout by parser.option(
        ArgType.String,
        fullName = "idle-timeout",
        description = "Idle timeout (e.g., 30m, 1h, 0 to disable)"
    ).default("30m")
    
    parser.parse(args)
    
    return ServerConfig(
        projectPath = projectPath,
        port = port,
        host = host,
        portRangeStart = 8080,
        portRangeEnd = 8180,
        idleTimeoutMinutes = parseTimeoutMinutes(idleTimeout)
    )
}

fun parseTimeoutMinutes(timeout: String): Int {
    if (timeout == "0") return 0
    val value = timeout.dropLast(1).toIntOrNull() ?: return 30
    return when (timeout.last()) {
        'm' -> value
        'h' -> value * 60
        else -> 30
    }
}

fun findAvailablePort(start: Int, end: Int): Int {
    for (port in start..end) {
        try {
            java.net.ServerSocket(port).use { return port }
        } catch (e: Exception) {
            continue
        }
    }
    throw IllegalStateException("No available ports in range $start-$end")
}

class ActivityTracker {
    private val lastActivity = AtomicReference(Instant.now())
    private val startedAt = Instant.now()
    
    fun touch() {
        lastActivity.set(Instant.now())
    }
    
    fun getLastActivity(): Instant = lastActivity.get()
    fun getStartedAt(): Instant = startedAt
    fun getIdleDuration(): Duration = Duration.between(lastActivity.get(), Instant.now())
    fun getUptime(): Duration = Duration.between(startedAt, Instant.now())
}

fun startIdleMonitor(tracker: ActivityTracker, timeout: Duration, onIdle: () -> Unit) {
    thread(name = "idle-monitor", isDaemon = true) {
        while (true) {
            Thread.sleep(60_000) // Check every minute
            if (tracker.getIdleDuration() > timeout) {
                onIdle()
                break
            }
        }
    }
}
```

**server/app/src/main/kotlin/codelens/server/routes/AdminRoutes.kt:**
```kotlin
package codelens.server.routes

import codelens.core.model.*
import codelens.server.ActivityTracker
import codelens.server.ServerConfig
import codelens.server.services.AnalysisService
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import java.time.format.DateTimeFormatter

fun Route.adminRoutes(
    analysisService: AnalysisService,
    activityTracker: ActivityTracker,
    config: ServerConfig
) {
    route("/admin") {
        get("/health") {
            call.respond(HealthResponse(
                status = "UP",
                timestamp = Instant.now().toString()
            ))
        }
        
        get("/ready") {
            val projectInfo = analysisService.getProjectInfo()
            val isReady = projectInfo.status == ProjectStatus.READY
            
            if (isReady) {
                call.respond(ReadyResponse(
                    ready = true,
                    status = "READY",
                    project = projectInfo.name
                ))
            } else {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ReadyResponse(
                        ready = false,
                        status = projectInfo.status.name,
                        project = projectInfo.name
                    )
                )
            }
        }
        
        get("/info") {
            val projectInfo = analysisService.getProjectInfo()
            val uptime = activityTracker.getUptime()
            val idle = activityTracker.getIdleDuration()
            
            call.respond(ServerInfo(
                version = "0.1.0",
                apiVersion = "v1",
                projectPath = config.projectPath,
                projectName = projectInfo.name,
                port = call.request.local.serverPort,
                host = config.host,
                status = projectInfo.status.name,
                startedAt = activityTracker.getStartedAt().toString(),
                uptime = formatDuration(uptime),
                lastActivityAt = activityTracker.getLastActivity().toString(),
                idleDuration = formatDuration(idle),
                idleTimeout = "${config.idleTimeoutMinutes}m"
            ))
        }
        
        post("/activity") {
            activityTracker.touch()
            call.respond(mapOf("lastActivityAt" to Instant.now().toString()))
        }
        
        post("/shutdown") {
            // Verify request is from localhost
            val remoteHost = call.request.local.remoteHost
            if (remoteHost != "127.0.0.1" && remoteHost != "localhost" && remoteHost != "0:0:0:0:0:0:0:1") {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse(
                    code = 403,
                    type = "Forbidden",
                    message = "Shutdown only allowed from localhost"
                ))
                return@post
            }
            
            call.respond(mapOf("status" to "shutting_down"))
            
            // Shutdown in background thread
            Thread {
                Thread.sleep(100) // Let response complete
                System.exit(0)
            }.start()
        }
    }
}

private fun formatDuration(duration: java.time.Duration): String {
    val hours = duration.toHours()
    val minutes = duration.toMinutesPart()
    val seconds = duration.toSecondsPart()
    
    return when {
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
```

**server/app/src/main/kotlin/codelens/server/routes/ProjectRoutes.kt:**
```kotlin
package codelens.server.routes

import codelens.server.services.AnalysisService
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.projectRoutes(analysisService: AnalysisService) {
    route("/api/v1") {
        get("/project") {
            call.respond(analysisService.getProjectInfo())
        }
        
        post("/project/refresh") {
            analysisService.refresh()
            call.respond(analysisService.getProjectInfo())
        }
    }
}
```

**server/app/src/main/kotlin/codelens/server/services/AnalysisService.kt:**
```kotlin
package codelens.server.services

import codelens.core.model.ProjectInfo
import codelens.core.model.ProjectStatus
import java.io.File
import java.time.Instant

class AnalysisService(private val projectDir: File) {
    private var projectInfo: ProjectInfo
    private var scannedAt: Instant? = null
    
    init {
        projectInfo = ProjectInfo(
            name = projectDir.name,
            path = projectDir.absolutePath,
            status = ProjectStatus.LOADING
        )
        
        // Simulate initial scan (stub - will be ClassGraph later)
        Thread {
            Thread.sleep(500) // Simulate brief loading
            scannedAt = Instant.now()
            projectInfo = projectInfo.copy(
                status = ProjectStatus.READY,
                classCount = 42,  // Stub values
                handlerCount = 3,
                scannedAt = scannedAt.toString()
            )
        }.start()
    }
    
    fun getProjectInfo(): ProjectInfo = projectInfo
    
    fun refresh() {
        projectInfo = projectInfo.copy(status = ProjectStatus.LOADING)
        Thread.sleep(200) // Simulate rescan
        scannedAt = Instant.now()
        projectInfo = projectInfo.copy(
            status = ProjectStatus.READY,
            scannedAt = scannedAt.toString()
        )
    }
}
```

**server/app/src/main/resources/logback.xml:**
```xml
<configuration>
    <appender name="STDERR" class="ch.qos.logback.core.ConsoleAppender">
        <target>System.err</target>
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="STDERR"/>
    </root>
    
    <!-- Suppress noisy loggers -->
    <logger name="io.netty" level="WARN"/>
    <logger name="org.gradle" level="WARN"/>
</configuration>
```

### Step 4: Implement Server ClassGraph Module (Stub)

**server/classgraph/build.gradle.kts:**
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":server:core"))
    implementation(libs.classgraph)
}
```

**server/classgraph/src/main/kotlin/codelens/classgraph/ClassGraphProvider.kt:**
```kotlin
package codelens.classgraph

/**
 * Stub ClassGraph provider - will be implemented in a later phase.
 * 
 * This will eventually:
 * - Scan project bytecode using ClassGraph
 * - Detect Ratpack handlers
 * - Analyze Promise usage
 * - Calculate migration complexity
 */
class ClassGraphProvider {
    // TODO: Implement in Phase 1
}
```

### Step 5: Implement Python CLI

**cli/src/codelens_cli/__init__.py:**
```python
"""CodeLens CLI - Ratpack migration analysis tool."""

__version__ = "0.1.0"
```

**cli/src/codelens_cli/main.py:**
```python
"""Main entry point for the CodeLens CLI."""

import typer
from rich.console import Console

from codelens_cli.commands import lifecycle, project

app = typer.Typer(
    name="codelens",
    help="Analyze Ratpack codebases for migration planning.",
    no_args_is_help=True,
)
console = Console()

# Register command groups
app.add_typer(lifecycle.app, name="")  # Lifecycle commands at root level
app.command(name="project")(project.project_info)


@app.command()
def version():
    """Show version information."""
    from codelens_cli import __version__
    from codelens_cli.server import find_server
    from codelens_cli.config import get_project_path
    
    console.print(f"codelens-cli {__version__}")
    
    # Try to get server version if running
    try:
        project_path = get_project_path(None)
        server = find_server(project_path)
        if server:
            console.print(f"codelens-server {server.get('version', 'unknown')} (running on port {server['port']})")
    except Exception:
        pass


if __name__ == "__main__":
    app()
```

**cli/src/codelens_cli/config.py:**
```python
"""Configuration management for CodeLens CLI."""

import os
from pathlib import Path
from typing import Any

import yaml

DEFAULT_CONFIG = {
    "server": {
        "mode": "auto",
        "idle_timeout": "30m",
        "port_range": {"start": 8080, "end": 8180},
        "host": "127.0.0.1",
    },
    "output": {
        "format": "auto",
        "color": "auto",
    },
    "java": {
        "home": None,
        "opts": [],
    },
}


def get_config_path() -> Path:
    """Get path to config file."""
    return Path.home() / ".config" / "codelens" / "config.yml"


def load_config() -> dict[str, Any]:
    """Load configuration from file, with defaults."""
    config = DEFAULT_CONFIG.copy()
    config_path = get_config_path()
    
    if config_path.exists():
        with open(config_path) as f:
            user_config = yaml.safe_load(f) or {}
            _deep_merge(config, user_config)
    
    # Environment variable overrides
    if mode := os.environ.get("CODELENS_SERVER_MODE"):
        config["server"]["mode"] = mode
    if timeout := os.environ.get("CODELENS_IDLE_TIMEOUT"):
        config["server"]["idle_timeout"] = timeout
    
    return config


def _deep_merge(base: dict, override: dict) -> None:
    """Deep merge override into base."""
    for key, value in override.items():
        if key in base and isinstance(base[key], dict) and isinstance(value, dict):
            _deep_merge(base[key], value)
        else:
            base[key] = value


def get_cache_dir() -> Path:
    """Get cache directory for CodeLens state."""
    cache_dir = Path.home() / ".cache" / "codelens"
    cache_dir.mkdir(parents=True, exist_ok=True)
    return cache_dir


def get_project_path(project: str | None) -> Path:
    """Get project path from argument or current directory."""
    if project:
        path = Path(project).resolve()
    else:
        path = Path.cwd()
    
    # Validate it's a Gradle project
    if not path.exists():
        raise typer.Exit(code=3)
    
    has_build_file = (path / "build.gradle").exists() or (path / "build.gradle.kts").exists()
    if not has_build_file:
        from rich.console import Console
        console = Console(stderr=True)
        console.print(f"[red]Error:[/red] No build.gradle or build.gradle.kts found in {path}")
        console.print("\nCodeLens requires a Gradle project directory.")
        console.print(f"\nTry: [cyan]cd /path/to/your/project[/cyan]")
        raise typer.Exit(code=3)
    
    return path


def find_repo_path() -> Path:
    """Find the CodeLens repository root."""
    # Check environment variable
    if env_path := os.environ.get("CODELENS_REPO_PATH"):
        return Path(env_path)
    
    # Walk up from this file to find gradlew
    current = Path(__file__).resolve().parent
    for _ in range(10):  # Max 10 levels up
        if (current / "gradlew").exists() and (current / "settings.gradle.kts").exists():
            return current
        parent = current.parent
        if parent == current:
            break
        current = parent
    
    raise RuntimeError(
        "Could not find CodeLens repository. "
        "Set CODELENS_REPO_PATH environment variable."
    )
```

**cli/src/codelens_cli/state.py:**
```python
"""State management for running CodeLens servers."""

import hashlib
import json
import os
import signal
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from codelens_cli.config import get_cache_dir


def project_hash(project_path: Path) -> str:
    """Generate a short hash for a project path."""
    canonical = str(project_path.resolve())
    return hashlib.sha256(canonical.encode()).hexdigest()[:12]


def get_state_dir() -> Path:
    """Get directory for server state files."""
    state_dir = get_cache_dir() / "servers"
    state_dir.mkdir(parents=True, exist_ok=True)
    return state_dir


def get_logs_dir() -> Path:
    """Get directory for server log files."""
    logs_dir = get_cache_dir() / "logs"
    logs_dir.mkdir(parents=True, exist_ok=True)
    return logs_dir


def get_state_file(project_path: Path) -> Path:
    """Get state file path for a project."""
    return get_state_dir() / f"{project_hash(project_path)}.json"


def get_log_file(project_path: Path) -> Path:
    """Get log file path for a project."""
    return get_logs_dir() / f"{project_hash(project_path)}.log"


def save_server_state(
    project_path: Path,
    pid: int,
    port: int,
    host: str,
    server_mode: str,
    idle_timeout: str,
) -> None:
    """Save server state to file."""
    state = {
        "pid": pid,
        "port": port,
        "host": host,
        "projectPath": str(project_path),
        "projectName": project_path.name,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "lastActivityAt": datetime.now(timezone.utc).isoformat(),
        "idleTimeout": idle_timeout,
        "status": "STARTING",
        "serverMode": server_mode,
        "version": "0.1.0",
    }
    
    state_file = get_state_file(project_path)
    state_file.write_text(json.dumps(state, indent=2))


def update_server_status(project_path: Path, status: str) -> None:
    """Update server status in state file."""
    state_file = get_state_file(project_path)
    if state_file.exists():
        state = json.loads(state_file.read_text())
        state["status"] = status
        state["lastActivityAt"] = datetime.now(timezone.utc).isoformat()
        state_file.write_text(json.dumps(state, indent=2))


def load_server_state(project_path: Path) -> dict[str, Any] | None:
    """Load server state from file."""
    state_file = get_state_file(project_path)
    if not state_file.exists():
        return None
    
    try:
        return json.loads(state_file.read_text())
    except json.JSONDecodeError:
        return None


def delete_server_state(project_path: Path) -> None:
    """Delete server state file."""
    state_file = get_state_file(project_path)
    state_file.unlink(missing_ok=True)


def is_process_running(pid: int) -> bool:
    """Check if a process is running."""
    try:
        os.kill(pid, 0)
        return True
    except (OSError, ProcessLookupError):
        return False


def list_all_servers() -> list[dict[str, Any]]:
    """List all server state files, validating each."""
    state_dir = get_state_dir()
    if not state_dir.exists():
        return []
    
    servers = []
    for state_file in state_dir.glob("*.json"):
        try:
            state = json.loads(state_file.read_text())
            if is_process_running(state["pid"]):
                servers.append(state)
            else:
                # Clean up stale file
                state_file.unlink()
        except (json.JSONDecodeError, KeyError):
            state_file.unlink()
    
    return servers
```

**cli/src/codelens_cli/server.py:**
```python
"""Server process management."""

import asyncio
import os
import re
import signal
import subprocess
import sys
from pathlib import Path

from rich.console import Console

from codelens_cli.config import find_repo_path, load_config
from codelens_cli.state import (
    delete_server_state,
    get_log_file,
    is_process_running,
    load_server_state,
    save_server_state,
    update_server_status,
)

console = Console(stderr=True)


def find_server(project_path: Path) -> dict | None:
    """Find running server for a project."""
    state = load_server_state(project_path)
    if state is None:
        return None
    
    if not is_process_running(state["pid"]):
        delete_server_state(project_path)
        return None
    
    return state


def determine_server_mode(config: dict) -> str:
    """Determine whether to use gradle or jar mode."""
    mode = config["server"]["mode"]
    if mode in ("gradle", "jar"):
        return mode
    
    # Auto mode: check if JAR exists
    try:
        repo_path = find_repo_path()
        jar_path = repo_path / "server" / "app" / "build" / "libs" / "codelens-server-all.jar"
        if jar_path.exists():
            return "jar"
    except RuntimeError:
        pass
    
    return "gradle"


def allocate_port(config: dict) -> int:
    """Find an available port."""
    import socket
    
    start = config["server"]["port_range"]["start"]
    end = config["server"]["port_range"]["end"]
    
    for port in range(start, end + 1):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                s.bind(("127.0.0.1", port))
                return port
        except OSError:
            continue
    
    raise RuntimeError(f"No available ports in range {start}-{end}")


async def start_server(
    project_path: Path,
    mode: str | None = None,
    port: int | None = None,
    timeout: int = 60,
) -> dict:
    """Start the CodeLens server for a project."""
    config = load_config()
    
    # Check if already running
    existing = find_server(project_path)
    if existing and existing.get("status") == "READY":
        return existing
    
    # Determine mode and port
    mode = mode or determine_server_mode(config)
    port = port or allocate_port(config)
    idle_timeout = config["server"]["idle_timeout"]
    host = config["server"]["host"]
    
    repo_path = find_repo_path()
    log_file = get_log_file(project_path)
    
    # Build command
    if mode == "gradle":
        cmd = [
            str(repo_path / "gradlew"),
            ":server:app:run",
            f"--args=--project {project_path} --port {port} --idle-timeout {idle_timeout}",
        ]
        cwd = repo_path
    else:
        jar_path = repo_path / "server" / "app" / "build" / "libs" / "codelens-server-all.jar"
        if not jar_path.exists():
            console.print(f"[red]Error:[/red] Server JAR not found at {jar_path}")
            console.print("\nBuild it with: [cyan]./gradlew :server:app:shadowJar[/cyan]")
            console.print("Or use: [cyan]codelens start --mode gradle[/cyan]")
            raise SystemExit(4)
        
        java_home = config["java"]["home"] or os.environ.get("JAVA_HOME")
        java_cmd = f"{java_home}/bin/java" if java_home else "java"
        
        cmd = [
            java_cmd,
            *config["java"]["opts"],
            "-jar", str(jar_path),
            "--project", str(project_path),
            "--port", str(port),
            "--idle-timeout", idle_timeout,
        ]
        cwd = None
    
    # Start process
    with open(log_file, "w") as log:
        process = subprocess.Popen(
            cmd,
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=log,
            start_new_session=True,
            text=True,
        )
    
    # Save initial state
    save_server_state(project_path, process.pid, port, host, mode, idle_timeout)
    
    # Wait for ready signal
    try:
        ready_info = await wait_for_ready(process, timeout)
        update_server_status(project_path, "READY")
        
        state = load_server_state(project_path)
        state["port"] = ready_info["port"]  # Use actual port from server
        return state
        
    except TimeoutError:
        process.terminate()
        delete_server_state(project_path)
        raise


async def wait_for_ready(process: subprocess.Popen, timeout: int) -> dict:
    """Wait for server to print CODELENS_READY."""
    ready_pattern = re.compile(r"CODELENS_READY port=(\d+) host=(\S+) version=(\S+)")
    
    loop = asyncio.get_event_loop()
    start_time = loop.time()
    
    while loop.time() - start_time < timeout:
        # Check if process died
        if process.poll() is not None:
            raise RuntimeError(f"Server process exited with code {process.returncode}")
        
        # Try to read a line (non-blocking would be better, but this works)
        try:
            line = process.stdout.readline()
            if line:
                match = ready_pattern.search(line)
                if match:
                    return {
                        "port": int(match.group(1)),
                        "host": match.group(2),
                        "version": match.group(3),
                    }
        except Exception:
            pass
        
        await asyncio.sleep(0.1)
    
    raise TimeoutError(f"Server did not become ready within {timeout}s")


def stop_server(project_path: Path, force: bool = False) -> bool:
    """Stop the server for a project."""
    state = find_server(project_path)
    if state is None:
        return False
    
    pid = state["pid"]
    
    # Try graceful shutdown via API first
    if not force:
        try:
            import httpx
            response = httpx.post(
                f"http://{state['host']}:{state['port']}/admin/shutdown",
                timeout=5,
            )
            if response.status_code == 200:
                # Wait for process to exit
                for _ in range(50):  # 5 seconds
                    if not is_process_running(pid):
                        break
                    import time
                    time.sleep(0.1)
        except Exception:
            pass
    
    # Force kill if still running
    if is_process_running(pid):
        try:
            os.kill(pid, signal.SIGTERM)
            import time
            time.sleep(0.5)
            if is_process_running(pid):
                os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    
    delete_server_state(project_path)
    return True
```

**cli/src/codelens_cli/client.py:**
```python
"""HTTP client for CodeLens server API."""

from typing import Any

import httpx

from codelens_cli.server import find_server


class CodeLensClient:
    """Client for communicating with a CodeLens server."""
    
    def __init__(self, host: str, port: int, timeout: float = 30.0):
        self.base_url = f"http://{host}:{port}"
        self.timeout = timeout
    
    def _get(self, path: str) -> dict[str, Any]:
        """Make a GET request."""
        with httpx.Client(timeout=self.timeout) as client:
            response = client.get(f"{self.base_url}{path}")
            response.raise_for_status()
            return response.json()
    
    def _post(self, path: str, data: dict | None = None) -> dict[str, Any]:
        """Make a POST request."""
        with httpx.Client(timeout=self.timeout) as client:
            response = client.post(f"{self.base_url}{path}", json=data)
            response.raise_for_status()
            return response.json()
    
    def health(self) -> dict[str, Any]:
        """Check server health."""
        return self._get("/admin/health")
    
    def ready(self) -> dict[str, Any]:
        """Check if server is ready."""
        return self._get("/admin/ready")
    
    def info(self) -> dict[str, Any]:
        """Get server info."""
        return self._get("/admin/info")
    
    def project(self) -> dict[str, Any]:
        """Get project info."""
        return self._get("/api/v1/project")
    
    def refresh(self) -> dict[str, Any]:
        """Refresh project scan."""
        return self._post("/api/v1/project/refresh")
    
    def touch_activity(self) -> None:
        """Touch activity to reset idle timer."""
        try:
            self._post("/admin/activity")
        except Exception:
            pass  # Best effort
```

**cli/src/codelens_cli/output.py:**
```python
"""Output formatting utilities."""

import json
import sys
from typing import Any

from rich.console import Console
from rich.table import Table


def is_tty() -> bool:
    """Check if stdout is a TTY."""
    return sys.stdout.isatty()


def get_console() -> Console:
    """Get a Rich console for output."""
    return Console()


def print_json(data: Any) -> None:
    """Print data as JSON."""
    print(json.dumps(data, indent=2, default=str))


def print_server_status(server: dict, console: Console | None = None) -> None:
    """Print server status in a nice format."""
    console = console or get_console()
    
    status_color = {
        "READY": "green",
        "STARTING": "yellow",
        "LOADING": "yellow",
        "ERROR": "red",
    }.get(server.get("status", ""), "white")
    
    console.print(f"\n[bold]CodeLens Server[/bold]")
    console.print()
    
    table = Table(show_header=False, box=None, padding=(0, 2))
    table.add_column("Key", style="dim")
    table.add_column("Value")
    
    table.add_row("Project:", server.get("projectName", "unknown"))
    table.add_row("Path:", server.get("projectPath", "unknown"))
    table.add_row("Status:", f"[{status_color}]{server.get('status', 'unknown')}[/]")
    table.add_row("Port:", str(server.get("port", "unknown")))
    table.add_row("Mode:", server.get("serverMode", "unknown"))
    
    if uptime := server.get("uptime"):
        table.add_row("Uptime:", uptime)
    if idle := server.get("idleDuration"):
        table.add_row("Idle:", idle)
    if timeout := server.get("idleTimeout"):
        table.add_row("Idle timeout:", timeout)
    
    console.print(table)
    console.print()


def print_project_info(project: dict, console: Console | None = None) -> None:
    """Print project info in a nice format."""
    console = console or get_console()
    
    status_color = {
        "READY": "green",
        "LOADING": "yellow",
        "ERROR": "red",
    }.get(project.get("status", ""), "white")
    
    console.print(f"\n[bold]{project.get('name', 'unknown')}[/bold]")
    console.print()
    
    table = Table(show_header=False, box=None, padding=(0, 2))
    table.add_column("Key", style="dim")
    table.add_column("Value")
    
    table.add_row("Path:", project.get("path", "unknown"))
    table.add_row("Status:", f"[{status_color}]{project.get('status', 'unknown')}[/]")
    
    if class_count := project.get("classCount"):
        table.add_row("Classes:", str(class_count))
    if handler_count := project.get("handlerCount"):
        table.add_row("Handlers:", str(handler_count))
    if scanned := project.get("scannedAt"):
        table.add_row("Scanned:", scanned)
    
    console.print(table)
    console.print()
```

**cli/src/codelens_cli/commands/__init__.py:**
```python
"""CLI commands for CodeLens."""
```

**cli/src/codelens_cli/commands/lifecycle.py:**
```python
"""Server lifecycle commands: start, stop, status, restart, refresh."""

import asyncio
from pathlib import Path
from typing import Optional

import typer
from rich.console import Console

from codelens_cli.client import CodeLensClient
from codelens_cli.config import get_project_path
from codelens_cli.output import is_tty, print_json, print_server_status
from codelens_cli.server import find_server, start_server, stop_server

app = typer.Typer()
console = Console()
err_console = Console(stderr=True)


@app.command()
def start(
    project: Optional[str] = typer.Option(None, "--project", "-p", help="Project directory"),
    port: Optional[int] = typer.Option(None, "--port", help="Port to use"),
    mode: Optional[str] = typer.Option(None, "--mode", help="Server mode: gradle or jar"),
    timeout: int = typer.Option(60, "--timeout", help="Startup timeout in seconds"),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
):
    """Start the CodeLens server for a project."""
    project_path = get_project_path(project)
    
    # Check if already running
    existing = find_server(project_path)
    if existing and existing.get("status") == "READY":
        if json_output or not is_tty():
            print_json(existing)
        else:
            console.print(f"[yellow]Server already running for {project_path.name}[/yellow]")
            print_server_status(existing, console)
        return
    
    if not json_output and is_tty():
        err_console.print(f"Starting CodeLens server for [cyan]{project_path.name}[/cyan]...")
    
    try:
        server = asyncio.run(start_server(project_path, mode=mode, port=port, timeout=timeout))
        
        if json_output or not is_tty():
            print_json(server)
        else:
            console.print(f"[green]✓[/green] Server ready")
            print_server_status(server, console)
            
    except TimeoutError:
        err_console.print(f"[red]Error:[/red] Server did not start within {timeout}s")
        err_console.print(f"\nCheck logs: [cyan]~/.cache/codelens/logs/[/cyan]")
        raise typer.Exit(7)
    except Exception as e:
        err_console.print(f"[red]Error:[/red] {e}")
        raise typer.Exit(4)


@app.command()
def stop(
    project: Optional[str] = typer.Option(None, "--project", "-p", help="Project directory"),
    force: bool = typer.Option(False, "--force", help="Force kill if graceful shutdown fails"),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
):
    """Stop the CodeLens server for a project."""
    project_path = get_project_path(project)
    
    stopped = stop_server(project_path, force=force)
    
    result = {"stopped": stopped, "project": str(project_path)}
    
    if json_output or not is_tty():
        print_json(result)
    else:
        if stopped:
            console.print(f"[green]✓[/green] Server stopped")
        else:
            console.print(f"[yellow]No server running for {project_path.name}[/yellow]")


@app.command()
def status(
    project: Optional[str] = typer.Option(None, "--project", "-p", help="Project directory"),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
):
    """Show server status for a project."""
    project_path = get_project_path(project)
    
    server = find_server(project_path)
    
    if server is None:
        if json_output or not is_tty():
            print_json({"running": False, "project": str(project_path)})
        else:
            console.print(f"[yellow]No server running for {project_path.name}[/yellow]")
            console.print(f"\nStart with: [cyan]codelens start[/cyan]")
        return
    
    # Get live info from server
    try:
        client = CodeLensClient(server["host"], server["port"])
        info = client.info()
        server.update(info)
    except Exception:
        pass  # Use cached state
    
    if json_output or not is_tty():
        print_json(server)
    else:
        print_server_status(server, console)


@app.command()
def restart(
    project: Optional[str] = typer.Option(None, "--project", "-p", help="Project directory"),
    mode: Optional[str] = typer.Option(None, "--mode", help="Server mode: gradle or jar"),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
):
    """Restart the CodeLens server for a project."""
    project_path = get_project_path(project)
    
    if not json_output and is_tty():
        err_console.print("Restarting server...")
    
    stop_server(project_path)
    
    try:
        server = asyncio.run(start_server(project_path, mode=mode))
        
        if json_output or not is_tty():
            print_json(server)
        else:
            console.print(f"[green]✓[/green] Server restarted")
            print_server_status(server, console)
            
    except Exception as e:
        err_console.print(f"[red]Error:[/red] {e}")
        raise typer.Exit(4)


@app.command()
def refresh(
    project: Optional[str] = typer.Option(None, "--project", "-p", help="Project directory"),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
):
    """Refresh the project scan (after code changes)."""
    project_path = get_project_path(project)
    
    server = find_server(project_path)
    if server is None:
        err_console.print(f"[red]Error:[/red] No server running for {project_path.name}")
        err_console.print(f"\nStart with: [cyan]codelens start[/cyan]")
        raise typer.Exit(2)
    
    if not json_output and is_tty():
        err_console.print("Refreshing...")
    
    try:
        client = CodeLensClient(server["host"], server["port"])
        result = client.refresh()
        
        if json_output or not is_tty():
            print_json(result)
        else:
            console.print(f"[green]✓[/green] Refreshed")
            
    except Exception as e:
        err_console.print(f"[red]Error:[/red] {e}")
        raise typer.Exit(5)


@app.command(name="list")
def list_servers(
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
):
    """List all running CodeLens servers."""
    from rich.table import Table
    from codelens_cli.state import list_all_servers
    
    servers = list_all_servers()
    
    if json_output or not is_tty():
        print_json({"servers": servers})
        return
    
    if not servers:
        console.print("[yellow]No CodeLens servers running[/yellow]")
        return
    
    table = Table(title="Running CodeLens Servers")
    table.add_column("Project", style="cyan")
    table.add_column("Port")
    table.add_column("Status")
    table.add_column("Mode")
    table.add_column("Path", style="dim")
    
    for server in servers:
        status_style = {
            "READY": "green",
            "STARTING": "yellow",
        }.get(server.get("status", ""), "white")
        
        table.add_row(
            server.get("projectName", "unknown"),
            str(server.get("port", "?")),
            f"[{status_style}]{server.get('status', 'unknown')}[/]",
            server.get("serverMode", "?"),
            server.get("projectPath", "unknown"),
        )
    
    console.print(table)
```

**cli/src/codelens_cli/commands/project.py:**
```python
"""Project analysis commands."""

import asyncio
from pathlib import Path
from typing import Optional

import typer
from rich.console import Console

from codelens_cli.client import CodeLensClient
from codelens_cli.config import get_project_path
from codelens_cli.output import is_tty, print_json, print_project_info
from codelens_cli.server import find_server, start_server

console = Console()
err_console = Console(stderr=True)


def project_info(
    project: Optional[str] = typer.Option(None, "--project", "-p", help="Project directory"),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
    once: bool = typer.Option(False, "--once", help="Start server, query, then stop"),
):
    """Show project information."""
    project_path = get_project_path(project)
    
    # Ensure server is running (auto-start)
    server = find_server(project_path)
    if server is None or server.get("status") != "READY":
        if not json_output and is_tty():
            err_console.print(f"Starting server for [cyan]{project_path.name}[/cyan]...")
        
        try:
            server = asyncio.run(start_server(project_path))
        except Exception as e:
            err_console.print(f"[red]Error:[/red] {e}")
            raise typer.Exit(4)
    
    # Query project info
    try:
        client = CodeLensClient(server["host"], server["port"])
        project_data = client.project()
        
        if json_output or not is_tty():
            print_json(project_data)
        else:
            print_project_info(project_data, console)
            
    except Exception as e:
        err_console.print(f"[red]Error:[/red] {e}")
        raise typer.Exit(5)
    finally:
        if once:
            from codelens_cli.server import stop_server
            stop_server(project_path)
```

### Step 6: Create Test Fixture

A minimal project to test against.

**test-fixtures/sample-ratpack-app/build.gradle.kts:**
```kotlin
plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

dependencies {
    // Minimal dependencies for a Ratpack-like project
    implementation(kotlin("stdlib"))
}
```

**test-fixtures/sample-ratpack-app/src/main/kotlin/sample/App.kt:**
```kotlin
package sample

fun main() {
    println("Sample Ratpack App")
}
```

---

## Acceptance Criteria

After completing this bootstrap phase, the following should work:

### 1. Server Builds and Runs

```bash
# From repo root
./gradlew :server:app:shadowJar

# Produces:
# server/app/build/libs/codelens-server-all.jar

# Can run directly:
java -jar server/app/build/libs/codelens-server-all.jar \
  --project ./test-fixtures/sample-ratpack-app

# Should print:
# CODELENS_READY port=8080 host=127.0.0.1 version=0.1.0
```

### 2. CLI Installs and Runs

```bash
# From repo root
cd cli
uv tool install --editable .

# Verify installation
codelens version
# codelens-cli 0.1.0

# From test fixture directory
cd ../test-fixtures/sample-ratpack-app
codelens status
# No server running for sample-ratpack-app
```

### 3. End-to-End Lifecycle

```bash
cd test-fixtures/sample-ratpack-app

# Start server
codelens start
# Starting CodeLens server for sample-ratpack-app...
# ✓ Server ready
# (shows status table)

# Check status
codelens status
# (shows status table with uptime, port, etc.)

# Get project info (calls API)
codelens project
# (shows project info with stub class count)

# List all servers
codelens list
# (shows table of running servers)

# Stop server
codelens stop
# ✓ Server stopped
```

### 4. Auto-Start Works

```bash
cd test-fixtures/sample-ratpack-app

# No server running
codelens stop  # ensure stopped

# Project command auto-starts
codelens project
# Starting server for sample-ratpack-app...
# (shows project info)

# Server now running
codelens status
# (shows running status)
```

### 5. JSON Output Works

```bash
codelens status --json
# {"running": true, "port": 8080, ...}

codelens project --json | jq '.name'
# "sample-ratpack-app"
```

### 6. Multiple Projects

```bash
# Terminal 1
cd ~/work/project-a
codelens start
# Running on port 8080

# Terminal 2  
cd ~/work/project-b
codelens start
# Running on port 8081

# Either terminal
codelens list
# Shows both servers
```

### 7. Idle Shutdown

```bash
codelens start --timeout 30  # For faster testing

# Wait 30+ minutes with no commands

# Server should have stopped
codelens status
# No server running
```

---

## Implementation Order

1. **Repository scaffold** - Create all directories and config files
2. **Server core module** - Models and shared types
3. **Server app module** - Ktor application with stub endpoints
4. **Build and test server** - Verify JAR builds and runs
5. **CLI scaffold** - Python project structure
6. **CLI lifecycle commands** - start, stop, status
7. **CLI-to-server integration** - Verify end-to-end
8. **Test fixture** - Sample project for testing
9. **Polish** - Error messages, help text, edge cases

---

## Notes for Implementation

### Kotlin/Gradle

- Use `./gradlew` wrapper (will download Gradle if needed)
- All logging goes to stderr, only `CODELENS_READY` to stdout
- Shadow JAR includes all dependencies
- JVM 21 target

### Python/UV

- Use `uv tool install --editable .` for development
- Never use `pip` or `uv pip`
- Entry point is `codelens = "codelens_cli.main:app"`
- Async operations use `asyncio.run()` at command level

### State Management

- All state in `~/.cache/codelens/`
- State files keyed by SHA256 hash of project path
- Clean up stale state files when process not running

### Error Handling

- Clear error messages with actionable suggestions
- Proper exit codes (see CLI spec)
- JSON error format for machine consumption
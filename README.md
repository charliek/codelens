# CodeLens

A developer tool for analyzing Ratpack-based JVM codebases to assist with migration planning.

## Overview

CodeLens consists of two components:

1. **Server** (Kotlin/Ktor): Runs in the background, loads a target project's bytecode using ClassGraph, and serves analysis queries via HTTP REST API
2. **CLI** (Python/Typer): User-facing command-line interface that manages server lifecycle and presents analysis results

## Architecture

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

## Repository Structure

```
codelens/
├── README.md
├── .gitignore
├── version.txt                      # Single source of truth for version
│
├── docs/
│   ├── api.md                       # API endpoint documentation
│   └── cli.md                       # CLI command documentation
│
├── .github/
│   └── workflows/
│       └── build.yml                # CI/CD pipeline
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
│   │           └── model/           # Data classes (ProjectInfo, ServerInfo, etc.)
│   │
│   ├── classgraph/                  # ClassGraph-based analysis (stub for now)
│   │   ├── build.gradle.kts
│   │   └── src/main/kotlin/
│   │       └── codelens/classgraph/
│   │           └── ClassGraphProvider.kt
│   │
│   ├── ktlint/                      # ktlint-based linting (warm server)
│   │   ├── build.gradle.kts
│   │   └── src/main/kotlin/
│   │       └── codelens/ktlint/
│   │           ├── KtlintProvider.kt
│   │           └── KtlintProviderImpl.kt
│   │
│   └── app/                         # HTTP server application
│       ├── build.gradle.kts         # Produces fat JAR via shadowJar
│       └── src/
│           ├── main/kotlin/
│           │   └── codelens/server/
│           │       ├── Application.kt       # Main entry point
│           │       ├── config/              # Configuration
│           │       │   ├── ServerConfig.kt
│           │       │   └── ArgumentParser.kt
│           │       ├── monitoring/          # Activity tracking
│           │       │   ├── ActivityTracker.kt
│           │       │   └── IdleMonitor.kt
│           │       ├── routes/              # Ktor route definitions
│           │       │   ├── AdminRoutes.kt
│           │       │   └── ProjectRoutes.kt
│           │       └── services/
│           │           └── AnalysisService.kt
│           └── test/kotlin/                 # Server tests
│
├── cli/                             # Python CLI
│   ├── pyproject.toml               # UV/Python project config
│   ├── tests/                       # CLI tests
│   │   ├── conftest.py
│   │   └── test_models.py
│   └── src/
│       └── codelens_cli/
│           ├── __init__.py
│           ├── main.py              # Typer app entry point
│           ├── client.py            # HTTP client for server API
│           ├── container.py         # Dependency injection container
│           ├── errors.py            # Exit codes and exceptions
│           ├── models.py            # Pydantic models
│           ├── output.py            # Rich formatting utilities
│           ├── settings.py          # Pydantic Settings configuration
│           ├── commands/
│           │   ├── __init__.py
│           │   ├── lifecycle.py     # start, stop, status, restart, list
│           │   ├── project.py       # project info
│           │   ├── classes.py       # class analysis commands
│           │   ├── annotations.py   # annotation commands
│           │   ├── methods.py       # method search commands
│           │   └── lint.py          # lint check, lint format
│           ├── repositories/
│           │   └── server_state_repository.py  # State persistence
│           └── services/
│               ├── __init__.py
│               ├── project_service.py   # Project operations
│               └── server_service.py    # Server lifecycle management
│
└── test-fixtures/                   # Sample Ratpack project for testing
    └── sample-ratpack-app/
        ├── build.gradle.kts
        └── src/main/kotlin/sample/App.kt
```

## Technology Stack

### Server (Kotlin)

| Component | Choice | Version |
|-----------|--------|---------|
| Language | Kotlin | 2.0.21 |
| Build | Gradle + Kotlin DSL | 8.x |
| HTTP Framework | Ktor | 3.0.2 |
| Serialization | kotlinx.serialization | 1.7.3 |
| CLI Parsing | kotlinx-cli | 0.3.6 |
| JVM Target | 21 | LTS |

### CLI (Python)

| Component | Choice | Version |
|-----------|--------|---------|
| Language | Python | 3.13+ |
| Package Manager | UV | latest |
| CLI Framework | Typer | 0.12+ |
| Terminal UI | Rich | 13+ |
| HTTP Client | httpx | 0.27+ |
| Config | Pydantic Settings | 2.0+ |

## Building

### Build the Server

```bash
# Build the shadow JAR (includes all dependencies)
./gradlew :server:app:shadowJar

# Output: server/app/build/libs/codelens-server-all.jar
```

### Install the CLI

```bash
cd cli

# Install with uv (recommended for development)
uv tool install --editable .

# Or install system-wide with pip
pip install -e .
```

## Usage

### Basic Workflow

```bash
# Navigate to your Ratpack project
cd ~/work/user-service

# Start the server (auto-starts on first command)
codelens start
# Starting CodeLens server for user-service...
# ✓ Server ready
#
# CodeLens Server
#
# Project:       user-service
# Path:          /home/user/work/user-service
# Status:        READY
# Port:          8080
# Mode:          gradle
# Uptime:        5s
# Idle:          0s
# Idle timeout:  30m

# Check server status
codelens status

# Get project info (calls API endpoint)
codelens project
#
# user-service
#
# Path:     /home/user/work/user-service
# Status:   READY
# Classes:  42
# Handlers: 3
# Scanned:  2026-01-05T12:34:56.789Z

# List all running servers
codelens list
# ┌──────────────┬──────┬────────┬────────┬─────────────────────────────┐
# │ Project      │ Port │ Status │ Mode   │ Path                        │
# ├──────────────┼──────┼────────┼────────┼─────────────────────────────┤
# │ user-service │ 8080 │ READY  │ gradle │ /home/user/work/user-service│
# └──────────────┴──────┴────────┴────────┴─────────────────────────────┘

# Refresh project scan (after code changes)
codelens refresh

# Stop the server
codelens stop
# ✓ Server stopped
```

### Auto-Start

The CLI automatically starts the server when needed:

```bash
cd ~/work/user-service

# No server running yet
codelens status
# No server running for user-service
#
# Start with: codelens start

# Project command auto-starts the server
codelens project
# Starting server for user-service...
# (shows project info)

# Server is now running
codelens status
# (shows running status)
```

### Multiple Projects

You can run servers for multiple projects simultaneously:

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

### JSON Output

All commands support `--json` for machine-readable output:

```bash
codelens status --json
# {"running": true, "port": 8080, ...}

codelens project --json | jq '.name'
# "user-service"
```

### Quick Command Reference

For complete documentation, see [docs/cli.md](docs/cli.md).

#### Server Lifecycle

```bash
codelens start                # Start server for current project
codelens stop                 # Stop server
codelens status               # Show server status
codelens list                 # List all running servers
codelens refresh              # Refresh after code changes
```

#### Class Analysis

```bash
# List and search classes
codelens classes list                                    # List all project classes
codelens classes list --package "com.example.api.*"      # Filter by package
codelens classes list --implements ratpack.handling.Handler  # Find implementations
codelens classes show com.example.UserHandler            # Show class details

# Analyze relationships
codelens classes implementations ratpack.handling.Handler  # Find all implementations
codelens classes hierarchy com.example.UserHandler         # Show class hierarchy
codelens classes dependencies com.example.UserHandler      # Show dependencies

# Statistics
codelens classes stats                                   # Show scan statistics
```

#### Annotation & Method Search

```bash
# Find annotation usages
codelens annotations usages javax.inject.Singleton       # Find @Singleton classes

# Search methods
codelens methods search --return-type ratpack.exec.Promise  # Find Promise methods
codelens methods search --name "get*"                       # Search by name pattern
```

#### Linting & Formatting

```bash
codelens lint check                    # Check all Kotlin files for style issues
codelens lint check src/Main.kt        # Check a single file
codelens lint format                   # Format all Kotlin files
codelens lint format --dry-run         # Preview formatting changes
```

#### Ratpack-Specific Analysis

```bash
# Handler analysis
codelens handlers list                          # List all Ratpack handlers
codelens handlers show com.example.MyHandler    # Show handler details
codelens handlers list --tier HIGH              # Filter by complexity tier

# Promise usage analysis
codelens promises summary                       # Project-wide Promise usage
codelens promises show com.example.MyHandler    # Promise usage for a class

# Migration planning
codelens migration summary                      # Complexity summary
codelens migration complexity com.example.MyHandler  # Class complexity
codelens migration order                        # Suggested migration order

# Guice module analysis
codelens modules list                           # List Guice modules
codelens modules show com.example.MyModule      # Module bindings

# External service integrations
codelens integrations list                      # List all integrations
codelens integrations show com.example.MyHandler  # Integrations for a class
codelens integrations find HTTP_CLIENT          # Find classes by type
```

#### Source Code Retrieval

```bash
codelens source show com.example.MyHandler        # View class source code
codelens source method com.example.MyHandler handle  # View method source
```

#### Common Options

All commands support:
- `--project`, `-p` - Specify project directory
- `--json` - Output as JSON
- `--include-libraries`, `-L` - Include library classes (analysis commands)

## Configuration

Configuration is managed via environment variables using Pydantic Settings.

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `CODELENS_SERVER_MODE` | `auto` | Server mode: `auto`, `gradle`, or `jar` |
| `CODELENS_SERVER_IDLE_TIMEOUT` | `30m` | Auto-shutdown timeout |
| `CODELENS_SERVER_HOST` | `127.0.0.1` | Server bind address |
| `CODELENS_SERVER_PORT_RANGE_START` | `8080` | Port range start |
| `CODELENS_SERVER_PORT_RANGE_END` | `8180` | Port range end |
| `CODELENS_JAVA_HOME` | (system) | JAVA_HOME override |
| `CODELENS_REPO_PATH` | (auto-detect) | Path to CodeLens repository |

### Example

```bash
# Use JAR mode with custom timeout
export CODELENS_SERVER_MODE=jar
export CODELENS_SERVER_IDLE_TIMEOUT=1h

codelens start
```

## State Management

All state is stored in `~/.cache/codelens/`:

```
~/.cache/codelens/
├── servers/              # Server state files (JSON)
│   └── {hash}.json       # Keyed by SHA256 hash of project path
└── logs/                 # Server log files
    └── {hash}.log
```

State files are automatically cleaned up when processes exit.

## API Endpoints

The server exposes a REST API. For complete documentation, see [docs/api.md](docs/api.md).

### Quick Reference

| Endpoint | Description |
|----------|-------------|
| `GET /admin/health` | Health check |
| `GET /admin/ready` | Readiness check |
| `GET /admin/info` | Server information |
| `GET /api/v1/project` | Project information |
| `POST /api/v1/project/refresh` | Refresh scan |
| `GET /api/v1/stats` | Scan statistics |
| `GET /api/v1/classes` | List/search classes |
| `GET /api/v1/classes/{fqn}` | Class details |
| `GET /api/v1/implementations/{fqn}` | Find implementations |
| `GET /api/v1/hierarchy/{fqn}` | Class hierarchy |
| `GET /api/v1/dependencies/{fqn}` | Class dependencies |
| `GET /api/v1/annotations/usages/{fqn}` | Annotation usages |
| `GET /api/v1/methods` | Search methods |
| `POST /api/v1/ktlint/lint/file` | Lint single file |
| `POST /api/v1/ktlint/lint/project` | Lint project |
| `POST /api/v1/ktlint/format/file` | Format single file |
| `POST /api/v1/ktlint/format/project` | Format project |
| `GET /api/v1/source/{fqn}` | Get class source code |
| `GET /api/v1/source/{fqn}/method/{name}` | Get method source code |
| `GET /api/v1/ratpack/handlers` | List Ratpack handlers |
| `GET /api/v1/ratpack/handlers/{fqn}` | Handler details |
| `GET /api/v1/ratpack/promises` | Promise usage summary |
| `GET /api/v1/ratpack/promises/{fqn}` | Class Promise usage |
| `GET /api/v1/ratpack/complexity` | Complexity summary |
| `GET /api/v1/ratpack/complexity/{fqn}` | Class complexity |
| `GET /api/v1/ratpack/migration-order` | Migration order |
| `GET /api/v1/ratpack/modules` | List Guice modules |
| `GET /api/v1/ratpack/modules/{fqn}` | Module details |
| `GET /api/v1/ratpack/integrations` | Integration summary |
| `GET /api/v1/ratpack/integrations/{fqn}` | Class integrations |

## Development

### Running the Server Directly

```bash
# Using Gradle
./gradlew :server:app:run --args="--project /path/to/project"

# Using JAR
java -jar server/app/build/libs/codelens-server-all.jar \
  --project /path/to/project \
  --port 8080 \
  --idle-timeout 30m
```

### Running the CLI in Development

```bash
cd cli

# Install in editable mode
uv tool install --editable .

# Or run directly with Python
export CODELENS_REPO_PATH=/path/to/codelens
python -m codelens_cli.main --help
```

### Testing with the Sample Project

```bash
cd test-fixtures/sample-ratpack-app

# Start server for sample project
codelens start

# Check status
codelens status

# Get project info
codelens project

# Stop server
codelens stop
```

## Current Status

**Phase 2B Complete** - Full Ratpack migration analysis:

✅ Full server implementation with Ktor
✅ Complete CLI with lifecycle management
✅ ClassGraph bytecode scanning
✅ Gradle Tooling API for classpath resolution
✅ Class listing, filtering, and search
✅ Implementation/subclass discovery
✅ Class hierarchy analysis
✅ Dependency mapping (incoming/outgoing)
✅ Annotation usage search
✅ Method search across codebase
✅ Auto-start capability
✅ Multiple project support
✅ Idle shutdown
✅ JSON output support
✅ Ratpack handler detection (Handler, ChainAction, GroovyHandler)
✅ Promise usage analysis (map, flatMap, blocking, async)
✅ Migration complexity scoring and tier classification
✅ Guice module and binding analysis
✅ Source code retrieval (class and method level)
✅ External service integration detection (HTTP clients, databases, queues)
✅ Handler @Inject annotation detection for DI migration scoping
✅ Migration hints for DI conversion

## Development

### Running Tests

**Kotlin:**
```bash
./gradlew test
```

**Python:**
```bash
cd cli
uv run pytest
```

### Architecture

The codebase follows a service/repository pattern:

- **Services** contain business logic (`ServerService`, `ProjectService`, `AnalysisService`)
- **Repositories** handle data persistence (`ServerStateRepository`)
- **Routes/Commands** are thin wrappers that delegate to services
- **Container** provides dependency injection for the CLI (`ServiceContainer`)

## Requirements

- JDK 21+
- Python 3.13+
- UV package manager (recommended) or pip
- Gradle 8.x (included via wrapper)
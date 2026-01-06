# CodeLens CLI Reference

The CodeLens CLI provides a command-line interface for analyzing JVM bytecode. It manages the CodeLens server lifecycle and provides formatted output for analysis queries.

## Installation

```bash
cd cli

# Install with uv (recommended)
uv tool install --editable .

# Or install with pip
pip install -e .
```

## CLI to API Mapping

The CLI commands map to server API endpoints as follows:

| CLI Command | API Endpoint | Description |
|-------------|--------------|-------------|
| `codelens status` | `GET /admin/info` | Server status |
| `codelens project` | `GET /api/v1/project` | Project info |
| `codelens refresh` | `POST /api/v1/project/refresh` | Refresh scan |
| `codelens classes stats` | `GET /api/v1/stats` | Scan statistics |
| `codelens classes list` | `GET /api/v1/classes` | List classes |
| `codelens classes show` | `GET /api/v1/classes/{fqn}` | Class details |
| `codelens classes implementations` | `GET /api/v1/implementations/{fqn}` | Find implementations |
| `codelens classes hierarchy` | `GET /api/v1/hierarchy/{fqn}` | Class hierarchy |
| `codelens classes dependencies` | `GET /api/v1/dependencies/{fqn}` | Dependencies |
| `codelens annotations usages` | `GET /api/v1/annotations/usages/{fqn}` | Annotation usages |
| `codelens methods search` | `GET /api/v1/methods` | Search methods |

---

## Global Options

All commands support these options:

| Option | Description |
|--------|-------------|
| `--project`, `-p` | Specify project directory (defaults to current directory) |
| `--json` | Output as JSON for machine processing |
| `--help` | Show help for the command |

---

## Lifecycle Commands

These commands manage the CodeLens server.

### codelens start

Start the CodeLens server for a project.

```bash
codelens start [OPTIONS]
```

**Options:**

| Option | Default | Description |
|--------|---------|-------------|
| `--project`, `-p` | `.` | Project directory |
| `--port` | auto | Specific port to use |
| `--mode` | `auto` | Server mode: `auto`, `gradle`, or `jar` |
| `--timeout` | `60` | Startup timeout in seconds |
| `--json` | - | Output as JSON |

**Examples:**

```bash
# Start server for current directory
codelens start

# Start for a specific project
codelens start -p ~/work/my-project

# Start on a specific port
codelens start --port 9000

# Start using JAR mode
codelens start --mode jar
```

---

### codelens stop

Stop the CodeLens server for a project.

```bash
codelens stop [OPTIONS]
```

**Options:**

| Option | Default | Description |
|--------|---------|-------------|
| `--project`, `-p` | `.` | Project directory |
| `--force` | `false` | Force kill if graceful shutdown fails |
| `--json` | - | Output as JSON |

**Examples:**

```bash
# Stop server for current directory
codelens stop

# Force stop
codelens stop --force
```

---

### codelens status

Show server status for a project.

```bash
codelens status [OPTIONS]
```

**Options:**

| Option | Default | Description |
|--------|---------|-------------|
| `--project`, `-p` | `.` | Project directory |
| `--json` | - | Output as JSON |

**Example Output:**

```
CodeLens Server

Project:       my-project
Path:          /home/user/work/my-project
Status:        READY
Port:          8080
Mode:          gradle
Uptime:        5m 30s
Idle:          30s
Idle timeout:  30m
```

---

### codelens restart

Restart the CodeLens server for a project.

```bash
codelens restart [OPTIONS]
```

**Options:**

| Option | Default | Description |
|--------|---------|-------------|
| `--project`, `-p` | `.` | Project directory |
| `--mode` | (current) | Server mode for restart |
| `--json` | - | Output as JSON |

---

### codelens refresh

Refresh the project scan (after code changes).

```bash
codelens refresh [OPTIONS]
```

**Options:**

| Option | Default | Description |
|--------|---------|-------------|
| `--project`, `-p` | `.` | Project directory |
| `--json` | - | Output as JSON |

---

### codelens list

List all running CodeLens servers.

```bash
codelens list [OPTIONS]
```

**Options:**

| Option | Description |
|--------|-------------|
| `--json` | Output as JSON |

**Example Output:**

```
Running CodeLens Servers

┌──────────────┬──────┬────────┬────────┬─────────────────────────────┐
│ Project      │ Port │ Status │ Mode   │ Path                        │
├──────────────┼──────┼────────┼────────┼─────────────────────────────┤
│ my-project   │ 8080 │ READY  │ gradle │ /home/user/work/my-project  │
│ other-proj   │ 8081 │ READY  │ gradle │ /home/user/work/other-proj  │
└──────────────┴──────┴────────┴────────┴─────────────────────────────┘
```

---

## Project Commands

### codelens project

Show project information.

```bash
codelens project [OPTIONS]
```

**Options:**

| Option | Default | Description |
|--------|---------|-------------|
| `--project`, `-p` | `.` | Project directory |
| `--once` | `false` | Start server, query, then stop |
| `--json` | - | Output as JSON |

**Example Output:**

```
my-project

Path:     /home/user/work/my-project
Status:   READY
Classes:  150
Handlers: 24
Scanned:  2026-01-05T12:00:05.000Z
```

---

### codelens version

Show version information.

```bash
codelens version
```

**Example Output:**

```
codelens-cli 0.1.0
codelens-server 0.1.0 (running on port 8080)
```

---

## Class Analysis Commands

All class analysis commands are under the `codelens classes` subcommand.

### codelens classes list

List classes in the codebase with optional filtering.

```bash
codelens classes list [OPTIONS]
```

**Options:**

| Option | Description |
|--------|-------------|
| `--package` | Filter by package pattern (supports `*` wildcard) |
| `--name` | Filter by class name pattern (supports `*` wildcard) |
| `--annotation` | Filter to classes with this annotation |
| `--extends` | Filter to classes extending this class |
| `--implements` | Filter to classes implementing this interface |
| `--interfaces`, `-i` | Only show interfaces |
| `--include-libraries`, `-L` | Include library classes |
| `--page` | Page number (0-based, default: 0) |
| `--size` | Page size (default: 50) |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# List all project classes
codelens classes list

# Find all classes in a package
codelens classes list --package "com.example.api.*"

# Find all Ratpack handlers
codelens classes list --implements ratpack.handling.Handler

# Find all classes with @Singleton annotation
codelens classes list --annotation javax.inject.Singleton

# Find all interfaces
codelens classes list --interfaces

# Include library classes
codelens classes list -L
```

**Example Output:**

```
Classes (1-24 of 24) | Filter: implements=ratpack.handling.Handler

┌─────────────────────────┬───────────┬─────────┬─────────┬────────┐
│ Name                    │ Type      │ Source  │ Methods │ Fields │
├─────────────────────────┼───────────┼─────────┼─────────┼────────┤
│ UserHandler             │ class     │ PROJECT │ 5       │ 3      │
│ DeviceHandler           │ class     │ PROJECT │ 4       │ 2      │
│ AuthHandler             │ class     │ PROJECT │ 3       │ 1      │
└─────────────────────────┴───────────┴─────────┴─────────┴────────┘
```

---

### codelens classes show

Show detailed information about a specific class.

```bash
codelens classes show FQN [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `FQN` | Fully qualified class name |

**Options:**

| Option | Description |
|--------|-------------|
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# Show class details
codelens classes show com.example.api.UserHandler
```

**Example Output:**

```
com.example.api.UserHandler

Package:     com.example.api
Type:        class
Visibility:  PUBLIC
Source:      PROJECT
Extends:     java.lang.Object
Implements:  ratpack.handling.Handler
Annotations: @Singleton

Methods (5)
┌────────────────┬────────────┬─────────────┬─────────────────────┐
│ Name           │ Visibility │ Return Type │ Parameters          │
├────────────────┼────────────┼─────────────┼─────────────────────┤
│ handle         │ PUBLIC     │ void        │ ctx: Context        │
│ getUser        │ PRIVATE    │ Promise     │ ctx: Context        │
└────────────────┴────────────┴─────────────┴─────────────────────┘

Fields (3)
┌─────────────────┬────────────┬─────────────┐
│ Name            │ Visibility │ Type        │
├─────────────────┼────────────┼─────────────┤
│ userService     │ PRIVATE    │ UserService │
│ logger          │ PRIVATE    │ Logger      │
└─────────────────┴────────────┴─────────────┘
```

---

### codelens classes stats

Show scan statistics for the codebase.

```bash
codelens classes stats [OPTIONS]
```

**Options:**

| Option | Description |
|--------|-------------|
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Example Output:**

```
Scan Statistics

Project Classes:      150
  - Interfaces:       25
  - Abstract Classes: 10
  - Enums:            8
  - Annotations:      3
Project Methods:      1200
Project Fields:       450

Library Classes:      2500
JDK Classes:          8000

Classpath Entries:    85
Resolved By:          GradleToolingAPI
Scan Duration:        1250ms
Scanned At:           2026-01-05T12:00:05.000Z
```

---

### codelens classes implementations

Find all implementations of an interface or subclasses of a class.

```bash
codelens classes implementations FQN [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `FQN` | Fully qualified interface or class name |

**Options:**

| Option | Description |
|--------|-------------|
| `--include-libraries`, `-L` | Include library classes |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# Find all Ratpack handler implementations
codelens classes implementations ratpack.handling.Handler

# Include library implementations
codelens classes implementations ratpack.handling.Handler -L
```

**Example Output:**

```
Implementations of ratpack.handling.Handler
Total: 24 (24 direct, 0 indirect)

┌─────────────────────────────────────────┬───────────┬────────┬─────────┐
│ Class                                   │ Type      │ Direct │ Source  │
├─────────────────────────────────────────┼───────────┼────────┼─────────┤
│ com.example.api.UserHandler             │ class     │ Yes    │ PROJECT │
│ com.example.api.DeviceHandler           │ class     │ Yes    │ PROJECT │
│ com.example.api.AuthHandler             │ class     │ Yes    │ PROJECT │
└─────────────────────────────────────────┴───────────┴────────┴─────────┘
```

---

### codelens classes hierarchy

Show the class hierarchy for a class.

```bash
codelens classes hierarchy FQN [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `FQN` | Fully qualified class name |

**Options:**

| Option | Description |
|--------|-------------|
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# Show hierarchy for a class
codelens classes hierarchy com.example.api.UserHandler
```

**Example Output:**

```
Hierarchy for com.example.api.UserHandler

Parents:
  └── java.lang.Object (class)

com.example.api.UserHandler (class)

Implements:
  - ratpack.handling.Handler

Children (0):
```

---

### codelens classes dependencies

Show dependencies for a class (incoming and outgoing).

```bash
codelens classes dependencies FQN [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `FQN` | Fully qualified class name |

**Options:**

| Option | Description |
|--------|-------------|
| `--include-libraries`, `-L` | Include library classes |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# Show dependencies
codelens classes dependencies com.example.api.UserHandler

# Include library dependencies
codelens classes dependencies com.example.api.UserHandler -L
```

**Example Output:**

```
Dependencies for com.example.api.UserHandler

Outgoing (this class depends on 5 classes):
┌───────────────────────────────────┬───────────────────┬─────────────┬─────────┐
│ Class                             │ Type              │ Location    │ Source  │
├───────────────────────────────────┼───────────────────┼─────────────┼─────────┤
│ com.example.service.UserService   │ FIELD_TYPE        │ userService │ PROJECT │
│ ratpack.handling.Handler          │ IMPLEMENTS        │ -           │ LIBRARY │
│ ratpack.handling.Context          │ METHOD_PARAMETER  │ handle      │ LIBRARY │
└───────────────────────────────────┴───────────────────┴─────────────┴─────────┘

Incoming (3 classes depend on this):
┌───────────────────────────────────┬───────────────────┬──────────┬─────────┐
│ Class                             │ Type              │ Location │ Source  │
├───────────────────────────────────┼───────────────────┼──────────┼─────────┤
│ com.example.config.AppModule      │ TYPE_REFERENCE    │ -        │ PROJECT │
│ com.example.routes.ApiRoutes      │ TYPE_REFERENCE    │ -        │ PROJECT │
└───────────────────────────────────┴───────────────────┴──────────┴─────────┘
```

**Dependency Types:**

| Type | Description |
|------|-------------|
| `EXTENDS` | Class extends another class |
| `IMPLEMENTS` | Class implements an interface |
| `FIELD_TYPE` | Field type reference |
| `METHOD_RETURN_TYPE` | Method return type |
| `METHOD_PARAMETER` | Method parameter type |
| `TYPE_REFERENCE` | Other type reference |

---

## Annotation Commands

Commands for analyzing annotation usage are under `codelens annotations`.

### codelens annotations usages

Find all classes using a specific annotation.

```bash
codelens annotations usages FQN [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `FQN` | Fully qualified annotation name |

**Options:**

| Option | Description |
|--------|-------------|
| `--include-libraries`, `-L` | Include library classes |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# Find all @Singleton classes
codelens annotations usages javax.inject.Singleton

# Find all @Inject usages
codelens annotations usages javax.inject.Inject
```

**Example Output:**

```
Usages of @Singleton
Total: 15 classes

┌─────────────────────────┬───────────┬─────────────────────────┬─────────┐
│ Class                   │ Type      │ Package                 │ Source  │
├─────────────────────────┼───────────┼─────────────────────────┼─────────┤
│ UserService             │ class     │ com.example.service     │ PROJECT │
│ DeviceService           │ class     │ com.example.service     │ PROJECT │
│ AuthService             │ class     │ com.example.service     │ PROJECT │
└─────────────────────────┴───────────┴─────────────────────────┴─────────┘
```

---

## Method Commands

Commands for searching methods are under `codelens methods`.

### codelens methods search

Search methods across all classes.

```bash
codelens methods search [OPTIONS]
```

**Options:**

| Option | Description |
|--------|-------------|
| `--name`, `-n` | Filter by method name pattern (supports `*` wildcard) |
| `--return-type`, `-r` | Filter by return type FQN |
| `--annotation`, `-a` | Filter to methods with this annotation |
| `--class`, `-c` | Filter by containing class FQN |
| `--package` | Filter by containing package pattern |
| `--include-libraries`, `-L` | Include library classes |
| `--page` | Page number (0-based, default: 0) |
| `--size` | Page size (default: 50) |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# Find all methods returning Promise
codelens methods search --return-type ratpack.exec.Promise

# Find methods named "handle"
codelens methods search --name handle

# Find methods with wildcard pattern
codelens methods search --name "get*"

# Find methods in a specific package
codelens methods search --package "com.example.api.*"

# Find methods with a specific annotation
codelens methods search --annotation javax.annotation.Nullable
```

**Example Output:**

```
Methods (1-45 of 45)

┌────────────────────────────┬─────────────┬─────────────────┬─────────┐
│ Method                     │ Return Type │ Class           │ Source  │
├────────────────────────────┼─────────────┼─────────────────┼─────────┤
│ getUser(String)            │ Promise     │ UserService     │ PROJECT │
│ getDevice(String)          │ Promise     │ DeviceService   │ PROJECT │
│ authenticate(String)       │ Promise     │ AuthService     │ PROJECT │
└────────────────────────────┴─────────────┴─────────────────┴─────────┘
```

---

## Common Workflows

### Analyze a Ratpack Project

```bash
# Navigate to your project
cd ~/work/my-ratpack-app

# Build the project first (required for bytecode analysis)
./gradlew build

# Start CodeLens
codelens start

# Find all Ratpack handlers
codelens classes implementations ratpack.handling.Handler

# Analyze a specific handler
codelens classes show com.example.api.UserHandler
codelens classes dependencies com.example.api.UserHandler

# Find all Promise-returning methods
codelens methods search --return-type ratpack.exec.Promise

# Find all @Singleton services
codelens annotations usages javax.inject.Singleton
```

### Map Service Dependencies

```bash
# Show what a handler depends on
codelens classes dependencies com.example.api.UserHandler

# Show what depends on a service
codelens classes dependencies com.example.service.UserService

# View the full hierarchy
codelens classes hierarchy com.example.service.UserService
```

### Export Data for Processing

```bash
# Export all handlers as JSON
codelens classes list --implements ratpack.handling.Handler --json > handlers.json

# Export dependencies as JSON
codelens classes dependencies com.example.api.UserHandler --json > deps.json

# Use with jq for filtering
codelens classes list --json | jq '.classes[] | select(.methodCount > 10)'
```

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `CODELENS_SERVER_MODE` | `auto` | Server mode: `auto`, `gradle`, or `jar` |
| `CODELENS_SERVER_IDLE_TIMEOUT` | `30m` | Auto-shutdown timeout |
| `CODELENS_SERVER_HOST` | `127.0.0.1` | Server bind address |
| `CODELENS_SERVER_PORT_RANGE_START` | `8080` | Port range start |
| `CODELENS_SERVER_PORT_RANGE_END` | `8180` | Port range end |
| `CODELENS_JAVA_HOME` | (system) | JAVA_HOME override |
| `CODELENS_REPO_PATH` | (auto-detect) | Path to CodeLens repository |

---

## Exit Codes

| Code | Description |
|------|-------------|
| 0 | Success |
| 1 | General error |
| 2 | Server not running |
| 3 | Server error |
| 4 | Connection error |
| 5 | Timeout |

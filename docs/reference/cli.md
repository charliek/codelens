# CodeLens CLI Reference

The CodeLens CLI provides a command-line interface for analyzing JVM bytecode. It manages the CodeLens server lifecycle and provides formatted output for analysis queries.

## Installation

```bash
brew tap charliek/tap
brew install codelens
```

See [Installation](../getting-started/installation.md) for the JDK prerequisite,
standalone/manual layouts, and building from source. The server JAR is located
automatically — see [Server & JAR Discovery](../concepts/discovery.md).

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
| `codelens handlers list` | `GET /api/v1/ratpack/handlers` | List Ratpack handlers |
| `codelens handlers show` | `GET /api/v1/ratpack/handlers/{fqn}` | Handler details |
| `codelens promises summary` | `GET /api/v1/ratpack/promises` | Promise usage summary |
| `codelens promises show` | `GET /api/v1/ratpack/promises/{fqn}` | Class Promise usage |
| `codelens promises search` | `GET /api/v1/ratpack/promises/search` | Search Promise usage |
| `codelens migration complexity` | `GET /api/v1/ratpack/complexity` | Complexity summary |
| `codelens migration order` | `GET /api/v1/ratpack/migration-order` | Migration order |
| `codelens modules list` | `GET /api/v1/ratpack/modules` | List Guice modules |
| `codelens modules show` | `GET /api/v1/ratpack/modules/{fqn}` | Module details |
| `codelens modules bindings` | `GET /api/v1/ratpack/bindings/{fqn}` | Find bindings |
| `codelens source show` | `GET /api/v1/source/{fqn}` | Get class source code |
| `codelens source method` | `GET /api/v1/source/{fqn}/method/{name}` | Get method source |
| `codelens integrations list` | `GET /api/v1/ratpack/integrations` | Integration summary |
| `codelens integrations show` | `GET /api/v1/ratpack/integrations/{fqn}` | Class integrations |
| `codelens integrations find` | `GET /api/v1/ratpack/integrations/by-type/{type}` | Find by type |
| `codelens antipatterns scan` | `GET /api/v1/ratpack/antipatterns` | Anti-pattern summary |
| `codelens antipatterns show` | `GET /api/v1/ratpack/antipatterns/{fqn}` | Class anti-patterns |
| `codelens routes list` | `GET /api/v1/ratpack/routes` | List all routes |
| `codelens routes tree` | `GET /api/v1/ratpack/routes/tree` | Route tree structure |
| `codelens routes spring` | `GET /api/v1/ratpack/routes/spring` | Spring equivalents |
| `codelens lint check` | `POST /api/v1/ktlint/lint/file` or `lint/project` | Check style issues |
| `codelens lint format` | `POST /api/v1/ktlint/format/file` or `format/project` | Format files |

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
| `--timeout` | `180` | Startup timeout in seconds |
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
| `--json` | - | Output as JSON |

**Example Output (JSON):**

```json
{
  "name": "my-project",
  "path": "/home/user/work/my-project",
  "status": "READY",
  "classCount": 150,
  "handlerCount": 24,
  "scannedAt": "2026-01-05T12:00:05.000000Z"
}
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

## Lint Commands

Commands for linting and formatting Kotlin code are under `codelens lint`.

### codelens lint check

Check Kotlin files for style issues using ktlint.

```bash
codelens lint check [FILE] [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `FILE` | Optional file to check (checks entire project if omitted) |

**Options:**

| Option | Description |
|--------|-------------|
| `--pattern` | Glob pattern to filter files (e.g., `*.kt`) |
| `--include-tests/--no-tests` | Include test files (default: true) |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# Check all Kotlin files in project
codelens lint check

# Check a single file
codelens lint check src/main/kotlin/App.kt

# Check with pattern filter
codelens lint check --pattern "*.kt"

# Exclude test files
codelens lint check --no-tests
```

**Example Output:**

```
Lint Results for my-project

3 issue(s) in 1 file(s) (10 scanned)

src/main/kotlin/sample/BadFormatting.kt (3 issue(s))
  1:17 standard:spacing: Missing space before '{'
  2:10 standard:spacing: Missing space around '='
  3:7 standard:spacing: Missing space after 'if' (auto-fixable)

Checked in 150ms
```

**Exit Codes:**

- Exit code 0: No style issues found
- Exit code 1: Style issues found

---

### codelens lint format

Format Kotlin files using ktlint.

```bash
codelens lint format [FILE] [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `FILE` | Optional file to format (formats entire project if omitted) |

**Options:**

| Option | Description |
|--------|-------------|
| `--pattern` | Glob pattern to filter files (e.g., `*.kt`) |
| `--include-tests/--no-tests` | Include test files (default: true) |
| `--dry-run`, `-n` | Preview changes without modifying files |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# Format all Kotlin files in project
codelens lint format

# Format a single file
codelens lint format src/main/kotlin/App.kt

# Preview what would be changed (dry run)
codelens lint format --dry-run

# Format excluding test files
codelens lint format --no-tests
```

**Example Output:**

```
Format Results for my-project

Formatted 2 file(s) (10 scanned)

  src/main/kotlin/sample/BadFormatting.kt
  src/main/kotlin/sample/AnotherFile.kt

Processed in 200ms
```

---

## Handler Commands

Commands for Ratpack handler analysis are under `codelens handlers`.

### codelens handlers list

List all Ratpack handlers in the codebase.

```bash
codelens handlers list [OPTIONS]
```

**Options:**

| Option | Description |
|--------|-------------|
| `--type`, `-t` | Filter by handler type (HANDLER, CHAIN_ACTION, INLINE_HANDLER, GROOVY_HANDLER) |
| `--tier` | Filter by complexity tier (LOW, MEDIUM, HIGH, CRITICAL) |
| `--missing-inject`, `-I` | Only show handlers without @Inject annotation |
| `--include-libraries`, `-L` | Include library handlers |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# List all handlers
codelens handlers list

# Find handlers missing @Inject annotation (need DI refactoring)
codelens handlers list --missing-inject

# Find only high-complexity handlers
codelens handlers list --tier HIGH

# Find Chain Action implementations
codelens handlers list --type CHAIN_ACTION
```

**Example Output:**

```
Ratpack Handlers (24 total)

┌─────────────────────┬──────────────┬────────┬───────┬─────────────┬──────────┐
│ Class               │ Type         │ Tier   │ Score │ Promise Ops │ Blocking │
├─────────────────────┼──────────────┼────────┼───────┼─────────────┼──────────┤
│ SimpleHandler       │ HANDLER      │ LOW    │    10 │           0 │ No       │
│ UserHandler         │ HANDLER      │ MEDIUM │    35 │           5 │ Yes      │
│ AsyncHandler        │ HANDLER      │ HIGH   │    65 │          12 │ Yes      │
└─────────────────────┴──────────────┴────────┴───────┴─────────────┴──────────┘
```

---

### codelens handlers show

Show detailed information about a Ratpack handler.

```bash
codelens handlers show FQN [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `FQN` | Fully qualified handler class name |

**Examples:**

```bash
codelens handlers show com.example.UserHandler
```

**Example Output:**

```
com.example.UserHandler
  Package: com.example
  Type: HANDLER

Complexity Analysis
  Score: 35/100 (MEDIUM)
  Estimated Hours: 4.0
  Factors:
    - Blocking Usage: +15 pts (Blocking operations need conversion)
    - Promise Chain Depth: +9 pts (Chain depth: 3)
  Migration Notes:
    ! Contains Blocking.get() - requires conversion to non-blocking pattern

Promise Usage
  Total Operations: 5
  Max Chain Depth: 3
  Uses Blocking: Yes
  Uses Async: No
  Uses Fork: No

Injected Dependencies
  - userService: com.example.UserService (CONSTRUCTOR)
```

---

## Promise Commands

Commands for Promise usage analysis are under `codelens promises`.

### codelens promises summary

Show project-wide Promise usage summary.

```bash
codelens promises summary [OPTIONS]
```

**Options:**

| Option | Description |
|--------|-------------|
| `--include-libraries`, `-L` | Include library classes |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
codelens promises summary
```

**Example Output:**

```
Promise Usage Summary
  Classes Using Promises: 15

Promise Operation Counts
┌────────────────────┬───────┐
│ Operation          │ Count │
├────────────────────┼───────┤
│ Blocking.get()     │    23 │
│ Promise.async()    │     8 │
│ Execution.fork()   │     3 │
│ ParallelBatch      │     1 │
│ Promise Operators  │    45 │
└────────────────────┴───────┘

Top Classes by Promise Complexity
┌─────────────────┬─────┬───────────┬──────────┐
│ Class           │ Ops │ Max Depth │ Blocking │
├─────────────────┼─────┼───────────┼──────────┤
│ AsyncHandler    │  12 │         5 │ Yes      │
│ UserService     │   8 │         3 │ Yes      │
│ DeviceService   │   5 │         2 │ No       │
└─────────────────┴─────┴───────────┴──────────┘
```

---

### codelens promises show

Show Promise usage for a specific class.

```bash
codelens promises show FQN [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `FQN` | Fully qualified class name |

---

### codelens promises search

Search for classes with specific Promise usage patterns.

```bash
codelens promises search [OPTIONS]
```

**Options:**

| Option | Description |
|--------|-------------|
| `--blocking/--no-blocking` | Filter by Blocking usage |
| `--async/--no-async` | Filter by async usage |
| `--fork/--no-fork` | Filter by fork usage |
| `--min-ops` | Minimum operation count |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# Find all classes using Blocking
codelens promises search --blocking

# Find classes with high Promise complexity
codelens promises search --min-ops 5

# Find classes using fork but not Blocking
codelens promises search --fork --no-blocking
```

---

## Migration Commands

Commands for migration complexity analysis are under `codelens migration`.

### codelens migration complexity

Show complexity analysis for a class or project summary.

```bash
codelens migration complexity [FQN] [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `FQN` | Optional fully qualified class name (shows summary if omitted) |

**Options:**

| Option | Description |
|--------|-------------|
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# Show project-wide complexity summary
codelens migration complexity

# Show complexity for specific class
codelens migration complexity com.example.UserHandler
```

**Example Output (Summary):**

```
Migration Complexity Summary
  Total Handlers: 24
  Total Estimated Hours: 120.5
  Average Score: 42.3

Complexity Tier Breakdown
┌──────────┬───────┐
│ Tier     │ Count │
├──────────┼───────┤
│ LOW      │    10 │
│ MEDIUM   │     8 │
│ HIGH     │     4 │
│ CRITICAL │     2 │
└──────────┴───────┘
```

---

### codelens migration order

Show suggested migration order for handlers.

```bash
codelens migration order [OPTIONS]
```

**Options:**

| Option | Description |
|--------|-------------|
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Example Output:**

```
Suggested Migration Order
Total Estimated Hours: 120.5

Migration Order (24 handlers)
┌───┬─────────────────┬────────┬───────┬─────────────────────────────────┐
│ # │ Class           │ Tier   │ Hours │ Reason                          │
├───┼─────────────────┼────────┼───────┼─────────────────────────────────┤
│ 1 │ SimpleHandler   │ LOW    │   1.0 │ Quick win - simple migration    │
│ 2 │ BasicHandler    │ LOW    │   1.5 │ Quick win - simple migration    │
│ 3 │ UserHandler     │ MEDIUM │   4.0 │ Moderate complexity             │
│ 4 │ AsyncHandler    │ HIGH   │   8.0 │ Complex - allocate dedicated    │
└───┴─────────────────┴────────┴───────┴─────────────────────────────────┘

Cumulative time: 120.5 hours
```

---

## Module Commands

Commands for Guice module analysis are under `codelens modules`.

### codelens modules list

List all Guice modules in the codebase.

```bash
codelens modules list [OPTIONS]
```

**Options:**

| Option | Description |
|--------|-------------|
| `--include-libraries`, `-L` | Include library modules |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
codelens modules list
```

**Example Output:**

```
Guice Modules (4 total)
┌─────────────────┬─────────────────────┬──────────┬───────────┐
│ Class           │ Type                │ Bindings │ @Provides │
├─────────────────┼─────────────────────┼──────────┼───────────┤
│ AppModule       │ ABSTRACT_MODULE     │        5 │         3 │
│ ServiceModule   │ ABSTRACT_MODULE     │        3 │         2 │
│ ConfigModule    │ CONFIGURABLE_MODULE │        1 │         0 │
└─────────────────┴─────────────────────┴──────────┴───────────┘
```

---

### codelens modules show

Show detailed information about a Guice module.

```bash
codelens modules show FQN [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `FQN` | Fully qualified module class name |

---

### codelens modules bindings

Find all bindings for a specific type.

```bash
codelens modules bindings FQN [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `FQN` | Fully qualified type name to find bindings for |

**Examples:**

```bash
codelens modules bindings com.example.UserService
```

**Example Output:**

```
Bindings for com.example.UserService

1 binding(s) found
┌──────────────┬─────────────┬──────────┬───────────┐
│ Module       │ Bound Type  │ Source   │ Scope     │
├──────────────┼─────────────┼──────────┼───────────┤
│ AppModule    │ UserService │ PROVIDES │ Singleton │
└──────────────┴─────────────┴──────────┴───────────┘
```

---

## Source Commands

Commands for viewing source code are under `codelens source`. Source can be retrieved for project classes, library classes (from source JARs or decompilation), and JDK classes (from src.zip).

### codelens source show

View source code for a class. Supports project classes, library classes, and JDK classes.

```bash
codelens source show FQN [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `FQN` | Fully qualified class name |

**Options:**

| Option | Description |
|--------|-------------|
| `--stub` | Generate stub with placeholder bodies (from bytecode, no source needed) |
| `--signatures` | Show only method/field signatures (minimal output) |
| `--javadoc` | Show signatures with doc comments only |
| `--kotlin` | Generate Kotlin-style stub (use with `--stub`) |
| `--public-only` | Show only public members |
| `--no-decompile` | Don't decompile if source unavailable |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Format Options:**

| Format | Description | Source Required? |
|--------|-------------|------------------|
| (default) | Complete source code | Yes |
| `--stub` | Signatures with `{ ... }` bodies | No (uses bytecode) |
| `--signatures` | Just declarations | No (uses bytecode) |
| `--javadoc` | Signatures + doc comments | Yes |

**Examples:**

```bash
# View project class source
codelens source show com.example.UserHandler

# View library source (from source JAR or decompiled)
codelens source show com.google.common.collect.ImmutableList

# View JDK source (from src.zip)
codelens source show java.util.HashMap

# Generate stub from bytecode (no source needed)
codelens source show com.google.common.collect.ImmutableList --stub

# Generate Kotlin-style stub
codelens source show com.google.common.collect.ImmutableList --stub --kotlin

# Show only public signatures (minimal tokens for LLM)
codelens source show org.springframework.boot.SpringApplication --signatures --public-only

# Get source with javadoc comments
codelens source show java.util.HashMap --javadoc
```

**Example Output (stub):**

```java
package com.google.common.collect;

public abstract class ImmutableList<E> extends ImmutableCollection<E>
    implements List<E>, RandomAccess {

    public static <E> ImmutableList<E> of() { /* ... */ }
    public static <E> ImmutableList<E> copyOf(Collection<? extends E> elements) { /* ... */ }
    public abstract E get(int index);
    public abstract int size();
}
```

---

### codelens source method

View source code for a specific method.

```bash
codelens source method FQN METHOD [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `FQN` | Fully qualified class name |
| `METHOD` | Method name |

**Options:**

| Option | Description |
|--------|-------------|
| `--context`, `-c` | Number of context lines before/after (default: 0) |
| `--param-types` | Comma-separated parameter types for disambiguation |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# View a method's source
codelens source method com.example.UserHandler handle

# Add context lines
codelens source method com.example.UserHandler handle --context 5

# Disambiguate overloaded method
codelens source method com.example.UserService getUser --param-types String
```

---

## Integration Commands

Commands for detecting external service integrations are under `codelens integrations`.

### codelens integrations list

List external service integrations detected in the codebase.

```bash
codelens integrations list [OPTIONS]
```

**Options:**

| Option | Description |
|--------|-------------|
| `--type`, `-t` | Filter by integration type (HTTP_CLIENT, DATABASE, MESSAGE_QUEUE, CACHE, GRPC, FILE_STORAGE) |
| `--sub-type` | Filter by specific sub-type (DYNAMODB, SQS, REDIS_LETTUCE, etc.) |
| `--include-libraries`, `-L` | Include library classes |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# List all integrations
codelens integrations list

# Filter by type
codelens integrations list --type HTTP_CLIENT

# Filter by sub-type
codelens integrations list --type DATABASE --sub-type DYNAMODB
```

**Example Output:**

```
External Service Integrations
Classes with integrations: 12 | Total usages: 28

By Type:
  DATABASE: 15
  HTTP_CLIENT: 8
  MESSAGE_QUEUE: 5

┌─────────────┬─────────────────┬────────────────────┬─────────┬────────┐
│ Type        │ SubType         │ Primary FQN        │ Classes │ Usages │
├─────────────┼─────────────────┼────────────────────┼─────────┼────────┤
│ HTTP_CLIENT │ RATPACK_HTTP    │ HttpClient         │       5 │      8 │
│ DATABASE    │ DYNAMODB        │ DynamoDbAsyncClient│       3 │      6 │
│ MESSAGE_QUE │ SQS             │ SqsAsyncClient     │       2 │      3 │
└─────────────┴─────────────────┴────────────────────┴─────────┴────────┘
```

---

### codelens integrations show

Show integrations for a specific class.

```bash
codelens integrations show FQN [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `FQN` | Fully qualified class name |

**Examples:**

```bash
codelens integrations show com.example.UserHandler
```

---

### codelens integrations find

Find classes using a specific integration type.

```bash
codelens integrations find TYPE [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `TYPE` | Integration type (HTTP_CLIENT, DATABASE, MESSAGE_QUEUE, CACHE, GRPC, FILE_STORAGE) |

**Options:**

| Option | Description |
|--------|-------------|
| `--sub-type` | Filter by sub-type |
| `--include-libraries`, `-L` | Include library classes |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# Find all classes using HTTP clients
codelens integrations find HTTP_CLIENT

# Find DynamoDB users specifically
codelens integrations find DATABASE --sub-type DYNAMODB
```

---

## Anti-Pattern Commands

Commands for detecting code anti-patterns are under `codelens antipatterns`.

### codelens antipatterns scan

Scan the codebase for anti-patterns.

```bash
codelens antipatterns scan [OPTIONS]
```

**Options:**

| Option | Description |
|--------|-------------|
| `--severity`, `-s` | Filter by severity (INFO, WARNING, ERROR, CRITICAL) |
| `--type`, `-t` | Filter by anti-pattern type |
| `--include-libraries`, `-L` | Include library classes |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Anti-Pattern Types:**

| Type | Description |
|------|-------------|
| `BLOCKING_JDBC` | JDBC calls without Blocking.get() wrapper |
| `THREAD_SLEEP` | Thread.sleep() calls blocking the event loop |
| `SYNCHRONOUS_FILE_IO` | Blocking file I/O operations |
| `BLOCKING_HTTP_CLIENT` | Using Apache HttpClient or java.net.URL |
| `CONSOLE_LOGGING` | Direct System.out/err usage |
| `SWALLOWED_EXCEPTION` | Catching and swallowing exceptions |

**Examples:**

```bash
# Scan entire project
codelens antipatterns scan

# Filter by severity
codelens antipatterns scan --severity CRITICAL

# Filter by type
codelens antipatterns scan --type BLOCKING_JDBC
```

**Example Output:**

```
Anti-Pattern Summary: 5 issues found
  Severity: 2 CRITICAL, 2 ERROR, 1 WARNING

By Type:
  BLOCKING_JDBC      2
  BLOCKING_HTTP_CLIENT  2
  SYNCHRONOUS_FILE_IO   1

Top Classes with Issues:
┌────────────────────┬───────┬──────────┬───────┐
│ Class              │ Total │ Critical │ Error │
├────────────────────┼───────┼──────────┼───────┤
│ UserHandler        │     2 │        1 │     1 │
│ FileProcessor      │     1 │        1 │     - │
└────────────────────┴───────┴──────────┴───────┘

All Issues (5):

[CRITICAL] BLOCKING_JDBC in UserHandler
  JDBC types (connection) are used without visible Blocking.get() usage.
  Recommendation: Wrap JDBC calls in Blocking.get { ... }
```

---

### codelens antipatterns show

Show anti-patterns for a specific class.

```bash
codelens antipatterns show FQN [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `FQN` | Fully qualified class name |

**Examples:**

```bash
codelens antipatterns show com.example.UserHandler
```

---

## Route Commands

Commands for analyzing Ratpack routes are under `codelens routes`.

### codelens routes list

List all routes defined in the application.

```bash
codelens routes list [OPTIONS]
```

**Options:**

| Option | Description |
|--------|-------------|
| `--method`, `-m` | Filter by HTTP method (GET, POST, PUT, PATCH, DELETE) |
| `--include-libraries`, `-L` | Include library classes |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# List all routes
codelens routes list

# Filter by HTTP method
codelens routes list --method GET
```

**Example Output:**

```
Route Summary: 5 routes (5 unique paths)
  Methods: 3 GET, 1 POST, 1 DELETE

Routes:
┌────────┬────────────────┬─────────────────┬───────────────┐
│ Method │ Path           │ Handler         │ Chain         │
├────────┼────────────────┼─────────────────┼───────────────┤
│ GET    │ /users         │ ListUsersHandler│ UsersChain    │
│ POST   │ /users         │ CreateHandler   │ UsersChain    │
│ GET    │ /users/:id     │ GetUserHandler  │ UsersChain    │
│ DELETE │ /users/:id     │ DeleteHandler   │ UsersChain    │
└────────┴────────────────┴─────────────────┴───────────────┘

Chain Classes:
┌─────────────┬────────┬─────────┐
│ Class       │ Routes │ Prefix  │
├─────────────┼────────┼─────────┤
│ UsersChain  │      4 │ /users  │
│ RootChain   │      1 │ -       │
└─────────────┴────────┴─────────┘
```

---

### codelens routes tree

Show routes as a tree structure.

```bash
codelens routes tree [OPTIONS]
```

**Options:**

| Option | Description |
|--------|-------------|
| `--include-libraries`, `-L` | Include library classes |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Example Output:**

```
Route Tree:

/
├── /users
│   ├── GET ListUsersHandler
│   ├── POST CreateHandler
│   └── /:id
│       ├── GET GetUserHandler
│       └── DELETE DeleteHandler
└── /health
    └── GET HealthHandler
```

---

### codelens routes spring

Generate Spring @RequestMapping equivalents for all routes.

```bash
codelens routes spring [OPTIONS]
```

**Options:**

| Option | Description |
|--------|-------------|
| `--include-libraries`, `-L` | Include library classes |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# Generate Spring mappings
codelens routes spring
```

**Example Output:**

```
Spring @RequestMapping Equivalents (5 routes)

GET /users
  Annotation: @GetMapping("/users")
  Signature:  fun listUsers(): ResponseEntity<*>

POST /users
  Annotation: @PostMapping("/users")
  Signature:  fun createUsers(): ResponseEntity<*>

GET /users/:id
  Annotation: @GetMapping("/users/{id}")
  Signature:  fun getUser(@PathVariable id: String): ResponseEntity<*>
  Note: Contains 1 path parameter(s)

DELETE /users/:id
  Annotation: @DeleteMapping("/users/{id}")
  Signature:  fun deleteUser(@PathVariable id: String): ResponseEntity<*>
  Note: Contains 1 path parameter(s)
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
| `CODELENS_SERVER__MODE` | `auto` | Server mode: `auto`, `gradle`, or `jar` |
| `CODELENS_SERVER__IDLE_TIMEOUT` | `30m` | Auto-shutdown timeout |
| `CODELENS_SERVER__HOST` | `127.0.0.1` | Server bind address |
| `CODELENS_SERVER__PORT_RANGE__START` | `61000` | Port range start (scanned from a randomized offset) |
| `CODELENS_SERVER__PORT_RANGE__END` | `65535` | Port range end |
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

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
| `codelens classes dependencies` | `GET /api/v1/dependencies/{fqn}` | Class dependencies |
| `codelens annotations usages` | `GET /api/v1/annotations/usages/{fqn}` | Annotation usages |
| `codelens methods search` | `GET /api/v1/methods` | Search methods |
| `codelens calls` | `GET /api/v1/calls/{fqn}` | Forward call sites |
| `codelens xref` | `GET /api/v1/xref/{typeFqn}` | Inverse cross-reference |
| `codelens deps` / `deps graph` | `GET /api/v1/graph` | Project dependency graph |
| `codelens deps foundation` | `GET /api/v1/graph/foundation` | Most depended-on classes |
| `codelens source show` | `GET /api/v1/source/{fqn}` | Get class source code |
| `codelens source method` | `GET /api/v1/source/{fqn}/method/{name}` | Get method source |
| `codelens lint check` | `POST /api/v1/ktlint/lint/file` or `lint/project` | Check style issues |
| `codelens lint format` | `POST /api/v1/ktlint/format/file` or `format/project` | Format files |

---

## Global Options

All commands support these options:

| Option | Description |
|--------|-------------|
| `--project`, `-p` | Path to the target Gradle project (defaults to the current directory) |
| `--json` | Emit JSON output (auto-enabled when stdout is not a TTY) |
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
| `--port` | auto | Port to use (auto-allocated if unset) |
| `--mode` | `auto` | Server mode: `auto`, `gradle`, or `jar` |
| `--project-java` | (resolved) | Java home for the target project's Gradle |
| `--timeout` | `180` | Startup timeout in seconds |
| `--server-jar` | (discovered) | Override path to `codelens-server-all.jar` |
| `--project`, `-p` | `.` | Project directory |
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
| `--force` | `false` | Force kill if graceful shutdown fails |
| `--project`, `-p` | `.` | Project directory |
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
Port:          61337
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
| `--mode` | (current) | Server mode: `auto`, `gradle`, or `jar` |
| `--project-java` | (resolved) | Java home for the target project's Gradle |
| `--timeout` | `180` | Startup timeout in seconds |
| `--server-jar` | (discovered) | Override path to `codelens-server-all.jar` |
| `--project`, `-p` | `.` | Project directory |
| `--json` | - | Output as JSON |

---

### codelens refresh

Refresh the project scan (re-runs the bytecode analysis after code changes).

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

List all running CodeLens servers. Does not require a project.

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

┌──────────────┬───────┬────────┬────────┬─────────────────────────────┐
│ Project      │ Port  │ Status │ Mode   │ Path                        │
├──────────────┼───────┼────────┼────────┼─────────────────────────────┤
│ my-project   │ 61337 │ READY  │ gradle │ /home/user/work/my-project  │
│ other-proj   │ 62001 │ READY  │ gradle │ /home/user/work/other-proj  │
└──────────────┴───────┴────────┴────────┴─────────────────────────────┘
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
  "scannedAt": "2026-01-05T12:00:05.000000Z"
}
```

---

### codelens version

Show the CLI version.

```bash
codelens version
```

**Example Output:**

```
codelens-cli 0.1.0
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
codelens classes list --package "com.example.web.*"

# Find all implementations of an interface
codelens classes list --implements com.example.service.ProductService

# Find all classes with a given annotation
codelens classes list --annotation org.springframework.stereotype.Service

# Find all interfaces
codelens classes list --interfaces

# Include library classes
codelens classes list -L
```

**Example Output:**

```
Classes (1-3 of 3) | Filter: package=com.example.web.*

┌─────────────────────────┬───────────┬─────────┬─────────┬────────┐
│ Name                    │ Type      │ Source  │ Methods │ Fields │
├─────────────────────────┼───────────┼─────────┼─────────┼────────┤
│ ProductController       │ class     │ PROJECT │ 5       │ 2      │
│ OrderController         │ class     │ PROJECT │ 4       │ 2      │
│ CustomerController      │ class     │ PROJECT │ 3       │ 1      │
└─────────────────────────┴───────────┴─────────┴─────────┴────────┘
```

---

### codelens classes show

Show detailed information about a specific class.

```bash
codelens classes show <fqn> [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `<fqn>` | Fully qualified class name |

**Options:**

| Option | Description |
|--------|-------------|
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# Show class details
codelens classes show com.example.web.ProductController
```

**Example Output:**

```
com.example.web.ProductController

Package:     com.example.web
Type:        class
Visibility:  PUBLIC
Source:      PROJECT
Extends:     com.example.web.BaseController
Annotations: @RestController

Methods (5)
┌────────────────┬────────────┬─────────────┬─────────────────────┐
│ Name           │ Visibility │ Return Type │ Parameters          │
├────────────────┼────────────┼─────────────┼─────────────────────┤
│ list           │ PUBLIC     │ List        │                     │
│ create         │ PUBLIC     │ Product     │ dto: ProductDto     │
└────────────────┴────────────┴─────────────┴─────────────────────┘

Fields (2)
┌─────────────────┬────────────┬────────────────┐
│ Name            │ Visibility │ Type           │
├─────────────────┼────────────┼────────────────┤
│ productService  │ PRIVATE    │ ProductService │
│ logger          │ PRIVATE    │ Logger         │
└─────────────────┴────────────┴────────────────┘
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
codelens classes implementations <fqn> [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `<fqn>` | Fully qualified interface or class name |

**Options:**

| Option | Description |
|--------|-------------|
| `--include-libraries`, `-L` | Include library classes |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# Find all implementations of a project interface
codelens classes implementations com.example.service.ProductService

# Find implementations of a library interface (e.g. a Spring Data repository)
codelens classes implementations org.springframework.data.jpa.repository.JpaRepository

# Include library implementations
codelens classes implementations com.example.service.ProductService -L
```

**Example Output:**

```
Implementations of com.example.service.ProductService
Total: 1 (1 direct, 0 indirect)

┌─────────────────────────────────────────┬───────────┬────────┬─────────┐
│ Class                                   │ Type      │ Direct │ Source  │
├─────────────────────────────────────────┼───────────┼────────┼─────────┤
│ com.example.service.ProductServiceImpl  │ class     │ Yes    │ PROJECT │
└─────────────────────────────────────────┴───────────┴────────┴─────────┘
```

---

### codelens classes hierarchy

Show the class hierarchy for a class.

```bash
codelens classes hierarchy <fqn> [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `<fqn>` | Fully qualified class name |

**Options:**

| Option | Description |
|--------|-------------|
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# Show hierarchy for a class
codelens classes hierarchy com.example.web.ProductController
```

**Example Output:**

```
Hierarchy for com.example.web.ProductController

Parents:
  └── com.example.web.BaseController (class)
      └── java.lang.Object (class)

com.example.web.ProductController (class)

Children (0):
```

---

### codelens classes dependencies

Show dependencies for a class (incoming and outgoing).

```bash
codelens classes dependencies <fqn> [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `<fqn>` | Fully qualified class name |

**Options:**

| Option | Description |
|--------|-------------|
| `--include-libraries`, `-L` | Include library classes |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

This is the per-class view. For the whole-project graph, see [`codelens deps`](#codelens-deps).

**Examples:**

```bash
# Show dependencies
codelens classes dependencies com.example.web.ProductController

# Include library dependencies
codelens classes dependencies com.example.web.ProductController -L
```

**Example Output:**

```
Dependencies for com.example.web.ProductController

Outgoing (this class depends on 3 classes):
┌───────────────────────────────────────┬───────────────────┬────────────────┬─────────┐
│ Class                                 │ Type              │ Location       │ Source  │
├───────────────────────────────────────┼───────────────────┼────────────────┼─────────┤
│ com.example.service.ProductService    │ FIELD_TYPE        │ productService │ PROJECT │
│ com.example.web.BaseController        │ EXTENDS           │ -              │ PROJECT │
│ com.example.dto.ProductDto            │ METHOD_PARAMETER  │ create         │ PROJECT │
└───────────────────────────────────────┴───────────────────┴────────────────┴─────────┘

Incoming (0 classes depend on this):
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
codelens annotations usages <annotation-fqn> [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `<annotation-fqn>` | Fully qualified annotation name |

**Options:**

| Option | Description |
|--------|-------------|
| `--include-libraries`, `-L` | Include library classes |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# Find all classes annotated with @Service
codelens annotations usages org.springframework.stereotype.Service

# Find all @Singleton classes
codelens annotations usages javax.inject.Singleton
```

**Example Output:**

```
Usages of @Service
Total: 6 classes

┌─────────────────────────┬───────────┬─────────────────────────┬─────────┐
│ Class                   │ Type      │ Package                 │ Source  │
├─────────────────────────┼───────────┼─────────────────────────┼─────────┤
│ ProductServiceImpl      │ class     │ com.example.service     │ PROJECT │
│ OrderService            │ class     │ com.example.service     │ PROJECT │
│ CustomerService         │ class     │ com.example.service     │ PROJECT │
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
# Find all methods returning java.util.List
codelens methods search --return-type java.util.List

# Find methods named "create"
codelens methods search --name create

# Find methods with a wildcard pattern
codelens methods search --name "get*"

# Find methods in a specific package
codelens methods search --package "com.example.web.*"

# Find methods with a specific annotation
codelens methods search --annotation org.springframework.web.bind.annotation.GetMapping
```

**Example Output:**

```
Methods (1-3 of 3)

┌────────────────────────────┬─────────────┬─────────────────────┬─────────┐
│ Method                     │ Return Type │ Class               │ Source  │
├────────────────────────────┼─────────────┼─────────────────────┼─────────┤
│ list()                     │ List        │ ProductController   │ PROJECT │
│ findAll()                  │ List        │ OrderService        │ PROJECT │
│ search(String)             │ List        │ CustomerService     │ PROJECT │
└────────────────────────────┴─────────────┴─────────────────────┴─────────┘
```

---

## Call Site Commands

### codelens calls

Extract, from bytecode, the invocations a class's methods make — the owner type
of each invoked method, the method name, its JVM descriptor, any constant
string/number/class arguments observed near the call, and the source line (when
debug info is present). These are raw call-site facts; the caller interprets
them.

```bash
codelens calls <fqn> [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `<fqn>` | Fully qualified class name |

**Options:**

| Option | Description |
|--------|-------------|
| `--method`, `-m` | Only show calls made by this method |
| `--descriptor` | JVM descriptor to disambiguate overloads (requires `--method`) |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

`--descriptor` only disambiguates a named method, so it must be used together
with `--method`. Passing it alone is rejected.

Without `--method`, methods that make no calls are omitted. With `--method`, a
matching method is returned even when it makes no calls (one entry with an empty
`calls` list); an unknown method name yields no entries.

**Examples:**

```bash
# Show every call made by every method of a class
codelens calls com.example.web.ProductController

# Scope to a single method
codelens calls com.example.web.ProductController --method create

# Disambiguate an overloaded method by descriptor
codelens calls com.example.service.OrderService \
  --method process --descriptor "(Lcom/example/model/Order;)V"
```

**Example Output (JSON):**

```json
{
  "fqn": "com.example.web.ProductController",
  "methods": [
    {
      "methodName": "create",
      "descriptor": "(Lcom/example/dto/ProductDto;)Lcom/example/model/Product;",
      "calls": [
        {
          "ownerType": "com.example.service.ProductService",
          "methodName": "create",
          "descriptor": "(Ljava/lang/String;D)Lcom/example/model/Product;",
          "isInterface": true,
          "constantArgs": [],
          "lineNumber": 37
        }
      ]
    }
  ],
  "totalCalls": 1
}
```

Each entry in `constantArgs` is a `{"kind": ..., "value": ...}` pair, where
`kind` is one of `STRING`, `INT`, `LONG`, `FLOAT`, `DOUBLE`, or `CLASS`.

---

## Cross-Reference Commands

### codelens xref

Find everything across the project that references a type — the inverse of
`calls`. Reports who extends or implements it, holds it as a field, takes or
returns it, is annotated with it, instantiates it, or calls methods on it.
Results are grouped by kind, with `countsByKind` and `countsByPackage`
aggregates over the full result.

```bash
codelens xref <typeFqn> [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `<typeFqn>` | Fully qualified type name to cross-reference |

**Options:**

| Option | Description |
|--------|-------------|
| `--kind` | Restrict to one kind (see below) |
| `--scope-implementing` | Only count references from classes that implement (or extend) this type |
| `--include-libraries`, `-L` | Include references from library classes |
| `--page` | Page number (0-based, default: 0) |
| `--size` | Page size (default: 50) |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Reference Kinds (`--kind`):**

| Kind | Description |
|------|-------------|
| `EXTENDS` | Referencing class extends the target type |
| `IMPLEMENTS` | Referencing class implements the target interface |
| `FIELD` | Referencing class has a field of the target type |
| `PARAM` | A method/constructor takes the target type as a parameter |
| `RETURN` | A method returns the target type |
| `ANNOTATION` | The referencing class/member is annotated with the target type |
| `INSTANTIATION` | The referencing class instantiates the target type (`new`) |
| `CALL_RECEIVER` | The referencing class invokes a method on the target type |

The signature-level kinds (`EXTENDS`, `IMPLEMENTS`, `FIELD`, `PARAM`, `RETURN`,
`ANNOTATION`) honor `--include-libraries`. The bytecode-level kinds
(`INSTANTIATION`, `CALL_RECEIVER`) always scan project classes only.

**Examples:**

```bash
# Everything that references a type
codelens xref javax.sql.DataSource

# Only the classes that take it as a field
codelens xref javax.sql.DataSource --kind FIELD

# Only references from classes implementing a given interface
codelens xref com.example.model.Product --scope-implementing com.example.service.ProductService
```

**Example Output (JSON):**

```json
{
  "typeFqn": "javax.sql.DataSource",
  "references": [
    {
      "fromFqn": "com.example.service.InventoryService",
      "fromSimpleName": "InventoryService",
      "fromSource": "PROJECT",
      "kind": "FIELD",
      "member": "dataSource",
      "detail": "javax.sql.DataSource",
      "lineNumber": null
    },
    {
      "fromFqn": "com.example.service.InventoryService",
      "fromSimpleName": "InventoryService",
      "fromSource": "PROJECT",
      "kind": "CALL_RECEIVER",
      "member": "stockLevel",
      "detail": "getConnection",
      "lineNumber": 25
    }
  ],
  "totalCount": 4,
  "page": 0,
  "pageSize": 50,
  "totalPages": 1,
  "countsByKind": { "FIELD": 1, "PARAM": 1, "RETURN": 1, "CALL_RECEIVER": 1 },
  "countsByPackage": { "com.example.service": 3, "com.example.config": 1 },
  "appliedFilter": { "includeLibraries": false, "kind": null, "scopeImplementing": null }
}
```

`countsByKind` is computed over the full result before any `--kind` filter, so
it always shows the complete breakdown.

---

## Dependency Graph Commands

### codelens deps

Emit the project-wide dependency graph: every project class as a node, and every
project-to-project dependency as a directed edge. Run with no subcommand it
behaves like [`deps graph`](#codelens-deps-graph).

```bash
codelens deps [OPTIONS]
```

**Options:**

| Option | Description |
|--------|-------------|
| `--format`, `-f` | Output format: `json` (default) or `dot` |
| `--output`, `-o` | Write to this file instead of stdout |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# Emit the dependency graph as JSON
codelens deps

# Emit Graphviz DOT
codelens deps --format dot

# Render with Graphviz
codelens deps --format dot -o graph.dot
dot -Tsvg graph.dot -o graph.svg
```

---

### codelens deps graph

The project-wide dependency graph (same data as bare `codelens deps`).

```bash
codelens deps graph [OPTIONS]
```

**Options:**

| Option | Description |
|--------|-------------|
| `--format` | Output format: `json` (default) or `dot` |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Example Output (JSON):**

```json
{
  "nodes": [
    {
      "fqn": "com.example.service.ProductService",
      "simpleName": "ProductService",
      "packageName": "com.example.service",
      "inDegree": 5,
      "outDegree": 0
    }
  ],
  "edges": [
    {
      "source": "com.example.web.ProductController",
      "target": "com.example.service.ProductService",
      "type": "FIELD_TYPE"
    }
  ],
  "nodeCount": 18,
  "edgeCount": 31
}
```

**Example Output (DOT):**

```dot
digraph dependencies {
  rankdir=LR;
  node [shape=box, style=rounded];

  "com.example.web.ProductController" [label="ProductController"];
  "com.example.service.ProductService" [label="ProductService"];

  "com.example.web.ProductController" -> "com.example.service.ProductService" [style=solid];
}
```

---

### codelens deps foundation

List the "foundation" classes — the project classes the most other project
classes depend on, ranked by in-degree. A quick way to find the load-bearing
types in an unfamiliar codebase.

```bash
codelens deps foundation [OPTIONS]
```

**Options:**

| Option | Description |
|--------|-------------|
| `--min-dependents` | Minimum number of dependents to qualify (default: 2) |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# Most depended-on classes
codelens deps foundation

# Only classes with at least 5 dependents
codelens deps foundation --min-dependents 5
```

**Example Output (JSON):**

```json
{
  "foundationClasses": [
    {
      "fqn": "com.example.service.ProductService",
      "simpleName": "ProductService",
      "packageName": "com.example.service",
      "dependentCount": 5,
      "dependents": [
        "com.example.report.ReportGenerator",
        "com.example.service.OrderService",
        "com.example.service.ProductServiceImpl",
        "com.example.web.ProductController"
      ]
    }
  ],
  "count": 8
}
```

---

## Source Commands

Commands for viewing source code are under `codelens source`. Source can be
retrieved for project classes, library classes (from source JARs or
decompilation), and JDK classes (from `src.zip`).

### codelens source show

View source code for a class. Supports project classes, library classes, and JDK
classes.

```bash
codelens source show <fqn> [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `<fqn>` | Fully qualified class name |

**Options:**

| Option | Description |
|--------|-------------|
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# View project class source
codelens source show com.example.web.ProductController

# View library source (from source JAR or decompiled)
codelens source show com.google.common.collect.ImmutableList

# View JDK source (from src.zip)
codelens source show java.util.HashMap
```

---

### codelens source method

View source code for a specific method.

```bash
codelens source method <fqn> <method> [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `<fqn>` | Fully qualified class name |
| `<method>` | Method name |

**Options:**

| Option | Description |
|--------|-------------|
| `--param-types` | Comma-separated parameter types for disambiguation |
| `--context`, `-c` | Number of context lines before/after the method (default: 0) |
| `--project`, `-p` | Project directory |
| `--json` | Output as JSON |

**Examples:**

```bash
# View a method's source
codelens source method com.example.web.ProductController create

# Add context lines
codelens source method com.example.web.ProductController create --context 5

# Disambiguate an overloaded method
codelens source method com.example.service.ProductService create --param-types String,double
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
| `FILE` | Optional file to check (checks the whole project if omitted) |

**Options:**

| Option | Default | Description |
|--------|---------|-------------|
| `--pattern` | - | Glob pattern to filter files (project mode only) |
| `--include-tests` | `true` | Include test sources (project mode only) |
| `--project`, `-p` | `.` | Project directory |
| `--json` | - | Output as JSON |

**Examples:**

```bash
# Check all Kotlin files in the project
codelens lint check

# Check a single file
codelens lint check src/main/kotlin/App.kt

# Check with a pattern filter
codelens lint check --pattern "*.kt"

# Exclude test files
codelens lint check --include-tests=false
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

Format Kotlin files using ktlint. Writes changes to disk by default; use
`--dry-run` to preview without modifying files.

```bash
codelens lint format [FILE] [OPTIONS]
```

**Arguments:**

| Argument | Description |
|----------|-------------|
| `FILE` | Optional file to format (formats the whole project if omitted) |

**Options:**

| Option | Default | Description |
|--------|---------|-------------|
| `--pattern` | - | Glob pattern to filter files (project mode only) |
| `--include-tests` | `true` | Include test sources (project mode only) |
| `--dry-run`, `-n` | `false` | Show changes without writing them |
| `--project`, `-p` | `.` | Project directory |
| `--json` | - | Output as JSON |

**Examples:**

```bash
# Format all Kotlin files in the project
codelens lint format

# Format a single file
codelens lint format src/main/kotlin/App.kt

# Preview what would be changed (dry run)
codelens lint format --dry-run

# Format excluding test files
codelens lint format --include-tests=false
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

## Common Workflows

### Map an Unfamiliar Codebase

```bash
# Navigate to your project
cd ~/work/my-app

# Build the project first (required for bytecode analysis)
./gradlew build

# Start CodeLens
codelens start

# Find the load-bearing classes (most depended-on)
codelens deps foundation

# See the whole dependency graph
codelens deps --format dot -o graph.dot

# Find implementations of a key interface
codelens classes implementations com.example.service.ProductService

# See what a class calls
codelens calls com.example.web.ProductController

# Find every reference to a type
codelens xref com.example.model.Product
```

### Trace a Type Through the Code

```bash
# Who references this type, and how?
codelens xref javax.sql.DataSource

# Narrow to just the fields holding it
codelens xref javax.sql.DataSource --kind FIELD

# What does a specific method actually call?
codelens calls com.example.service.InventoryService --method stockLevel
```

### Map Class Dependencies

```bash
# Show what a class depends on
codelens classes dependencies com.example.web.ProductController

# Show what depends on a service
codelens classes dependencies com.example.service.ProductService

# View the full hierarchy
codelens classes hierarchy com.example.service.ProductServiceImpl
```

### Export Data for Processing

```bash
# Export classes in a package as JSON
codelens classes list --package "com.example.web.*" --json > controllers.json

# Export the dependency graph as JSON
codelens deps --json > graph.json

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
| `CODELENS_JAVA_HOME` | (system) | JAVA_HOME override for the server JVM |
| `CODELENS_REPO_PATH` | (auto-detect) | Path to CodeLens repository |

---

## Exit Codes

| Code | Description |
|------|-------------|
| 0 | Success |
| 1 | General error |
| 2 | Invalid usage (unknown command/flag, bad arguments) |
| 3 | Project not found (no `build.gradle`/`build.gradle.kts`) |
| 4 | Server error |
| 5 | Timeout |
| 6 | Connection error |
| 7 | Server not running |

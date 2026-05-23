---
name: codelens-ratpack-analysis
description: |
  Analyze Ratpack applications for migration planning. Use this skill when working with
  Ratpack-based JVM projects to understand handlers, Promise usage patterns, Guice modules,
  external integrations, anti-patterns, and migration complexity. Provides tools to assess
  migration effort, identify dependencies, and plan migration order.
---

# Ratpack Migration Analysis

This skill enables comprehensive analysis of Ratpack applications to support migration planning.

## When to Use

- Analyze a Ratpack codebase for migration readiness
- Understand handler complexity and dependencies
- Identify Promise usage patterns that need attention
- Find anti-patterns that should be fixed before/during migration
- Map Guice module structure and bindings
- Identify external integrations (databases, HTTP clients, queues)
- Plan migration order based on dependencies
- Estimate migration effort

## Prerequisites

Ensure the CodeLens server is running for your Ratpack project:

```bash
codelens start --project /path/to/ratpack-project
```

**Note:** The first start for a project may take several minutes (3-7 min for
large projects) while the server resolves the Gradle classpath and downloads
dependencies. Subsequent starts are much faster due to Gradle's dependency
cache. Use `codelens status --project /path/to/project` to monitor progress —
the status transitions from `READY` (HTTP server up) through `LOADING`
(scanning bytecode) and back to `READY` (scan complete, API fully available).

## Quick Start: Migration Assessment

Run these commands for an initial assessment:

```bash
# 1. Overall project stats
codelens classes stats

# 2. Handler overview with complexity
codelens handlers list

# 3. Complexity summary
codelens migration complexity

# 4. Anti-pattern scan
codelens antipatterns scan

# 5. Suggested migration order
codelens migration order
```

## Handler Analysis

### List Handlers

```bash
codelens handlers list [options]
```

**Options:**
- `--type <type>` - Filter by handler type (HANDLER, CHAIN_ACTION, INLINE_HANDLER, GROOVY_HANDLER)
- `--tier <tier>` - Filter by complexity tier (LOW, MEDIUM, HIGH, CRITICAL)
- `--missing-inject`, `-I` - Show only handlers **without** an `@Inject` annotation (candidates for DI refactoring)

**Examples:**
```bash
# All handlers
codelens handlers list

# High complexity handlers only
codelens handlers list --tier HIGH

# Handlers that still need @Inject wired up
codelens handlers list --missing-inject
```

### Handler Details

```bash
codelens handlers show <fully-qualified-name>
```

Shows:
- Handler type and methods
- Injected dependencies
- Complexity score and factors
- Promise usage within the handler

## Promise Analysis

### Project-Wide Summary

```bash
codelens promises summary
```

Shows counts of Promise operations across the project:
- Blocking.get/on usage
- Promise.async/sync patterns
- Fork and ParallelBatch usage
- Operator usage (map, flatMap, then, etc.)

### Class-Level Promise Usage

```bash
codelens promises show <fully-qualified-name>
```

### Search by Promise Pattern

```bash
codelens promises search [options]
```

**Options:**
- `--blocking` - Classes using Blocking.get/on
- `--async` - Classes using Promise.async
- `--fork` - Classes using fork operations
- `--min-ops <n>` - Minimum Promise operations

**Example:**
```bash
# Find handlers heavily using blocking operations
codelens promises search --blocking --min-ops 5
```

## Complexity Analysis

### Project Summary

```bash
codelens migration complexity
```

Shows:
- Tier breakdown (how many handlers at each complexity level)
- Total estimated migration effort
- Highest complexity handlers

### Class-Level Complexity

```bash
codelens migration complexity <fully-qualified-name>
```

Shows:
- Complexity score (0-100)
- Complexity tier (LOW/MEDIUM/HIGH/CRITICAL)
- Estimated migration hours
- Contributing factors breakdown
- Migration notes and warnings

### Migration Order

```bash
codelens migration order
```

Suggests migration sequence based on:
- Dependency relationships (migrate dependencies first)
- Complexity tiers (start with simpler handlers)
- Foundation classes identification

## Route Analysis

### List Routes

```bash
codelens routes list
```

Shows all HTTP routes with methods and handlers.

### Route Tree

```bash
codelens routes tree
```

Hierarchical view of route structure.

### Spring Equivalents

```bash
codelens routes spring
```

Shows equivalent Spring `@RequestMapping` annotations for each route (useful for migration planning).

## Guice Module Analysis

### List Modules

```bash
codelens modules list [options]
```

**Options:**
- `--include-libraries` - Include library modules

### Module Details

```bash
codelens modules show <fully-qualified-name>
```

Shows:
- Module type (ABSTRACT_MODULE, CONFIGURABLE_MODULE, PROVIDER_CLASS)
- Bindings defined
- @Provides methods
- Installed sub-modules

### Find Bindings

```bash
codelens modules bindings <type-fqn>
```

Find where a specific type is bound.

**Example:**
```bash
codelens modules bindings com.example.UserRepository
```

## Integration Detection

### List All Integrations

```bash
codelens integrations list
```

Shows external integrations detected:
- HTTP clients (Ratpack, OkHttp, Apache, etc.)
- Databases (JDBC, DynamoDB, MongoDB, etc.)
- Message queues (SQS, Kafka, RabbitMQ, etc.)
- Caches (Caffeine, Redis, etc.)
- gRPC services

### Filter by Type

```bash
codelens integrations list --type HTTP_CLIENT
codelens integrations list --type DATABASE
codelens integrations list --type MESSAGE_QUEUE
```

### Class-Level Integrations

```bash
codelens integrations show <fully-qualified-name>
```

### Find Classes Using Integration

```bash
codelens integrations find <type>
```

**Example:**
```bash
codelens integrations find DYNAMODB
```

## Anti-Pattern Detection

### Scan Project

```bash
codelens antipatterns scan [options]
```

**Options:**
- `--severity <level>` - Filter by severity (INFO, WARNING, ERROR, CRITICAL)
- `--type <type>` - Filter by anti-pattern type

**Anti-pattern types detected:**
- `BLOCKING_JDBC` - JDBC calls outside Blocking.get
- `THREAD_SLEEP` - Thread.sleep in handlers
- `SYNCHRONOUS_FILE_IO` - Blocking file operations
- `BLOCKING_HTTP_CLIENT` - Synchronous HTTP calls
- `CONSOLE_LOGGING` - System.out/err usage
- `SWALLOWED_EXCEPTION` - Empty catch blocks

### Class-Level Anti-Patterns

```bash
codelens antipatterns show <fully-qualified-name>
```

## Dependency Analysis

### Summary

```bash
codelens deps
```

Shows:
- Foundation classes (most depended-on, migrate first)
- Quick wins (low complexity, few dependencies)
- Dependency tiers
- Circular dependencies (if any)

### Foundation Classes

```bash
codelens deps foundation
```

Classes that many others depend on - migrate these first.

### Quick Wins

```bash
codelens deps quickwins
```

Handlers with low complexity and few dependencies - good starting points.

### Dependency Graph

```bash
codelens deps graph [options]
```

**Options:**
- `--format dot` - Graphviz DOT format
- `--format json` - JSON format

**Example:**
```bash
# Generate visualization
codelens deps graph --format dot > deps.dot
dot -Tpng deps.dot -o deps.png
```

## Migration Workflow

### Phase 1: Assessment

```bash
# Get overview
codelens classes stats
codelens handlers list
codelens migration complexity

# Identify problem areas
codelens antipatterns scan --severity ERROR
codelens promises search --blocking
```

### Phase 2: Dependency Mapping

```bash
# Understand structure
codelens deps
codelens deps foundation
codelens modules list
```

### Phase 3: Plan Migration Order

```bash
# Get suggested order
codelens migration order

# Check specific handler readiness
codelens handlers show com.example.CriticalHandler
codelens migration complexity com.example.CriticalHandler
```

### Phase 4: Pre-Migration Fixes

Address anti-patterns and simplify complex handlers before migration:

```bash
# Focus on critical issues
codelens antipatterns scan --severity CRITICAL

# Review highest complexity handlers
codelens handlers list --tier CRITICAL
```

## Complexity Tiers

| Tier | Score | Estimated Hours | Characteristics |
|------|-------|-----------------|-----------------|
| LOW | 0-25 | 1-4 | Simple handlers, few dependencies |
| MEDIUM | 26-50 | 4-12 | Moderate Promise usage, some integrations |
| HIGH | 51-75 | 12-24 | Complex Promise chains, multiple integrations |
| CRITICAL | 76-100 | 24+ | Heavy blocking, deep Promise nesting, many anti-patterns |

## Tips

- Start with `codelens deps quickwins` to identify easy migrations
- Address anti-patterns before migration - they indicate potential issues
- Use `codelens routes spring` to preview target API structure
- Foundation classes often contain shared logic worth refactoring
- High Promise chain depth suggests candidates for async/await patterns
- Use `--json` to get structured output for piping through `jq` or other tools
  (the CLI auto-enables JSON output when stdout is not a TTY)

## External References

- [Ratpack Manual](https://ratpack.io/manual/current/)
- [Ratpack Promise API](https://ratpack.io/manual/current/api/ratpack/exec/Promise.html)
- [Guice Documentation](https://github.com/google/guice/wiki)

## Related Skills

- `codelens-source-lookup` - View handler implementations
- `codelens-jvm-analysis` - General class/method analysis

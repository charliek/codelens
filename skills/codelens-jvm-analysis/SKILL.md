---
name: codelens-jvm-analysis
description: |
  Use this skill to answer structural questions about Java/Kotlin (JVM) code — the "where
  is this used, what connects to what" questions that come up when refactoring, deleting
  code, or learning a codebase. Trigger it whenever someone asks: where is class X
  instantiated or referenced (the blast radius before deleting/refactoring it)? which
  classes implement this interface or extend this base class? what's a type's inheritance
  hierarchy? which classes carry an annotation like @RestController or @Component? what
  does this method call and how are things wired together? which types are most
  depended-on (the core/foundation classes)? Prefer this skill over grep or opening files
  one-by-one for such questions — it analyzes compiled JVM bytecode and catches
  relationships that plain-text search misses. Not for editing or reading source,
  debugging runtime errors/stack traces, git history/blame, formatting/linting, or
  explaining JVM concepts.
---

# JVM Analysis

This skill enables exploration and analysis of JVM codebases through bytecode scanning.
Its primitives are framework-agnostic — they work the same on a Spring, Micronaut,
Ratpack, Android, or plain-Java/Kotlin project.

## When to Use

- Find all classes in a package or matching a pattern
- Search for methods by name, return type, or annotation
- Find all implementations of an interface
- Trace class inheritance hierarchies
- See what invocations a method makes (`calls`), with constant arguments and line numbers
- Find everything that references a type (`xref`) — callers, fields, params, subtypes, …
- Analyze dependencies between classes, or the whole project's dependency graph
- Find usages of a specific annotation

## Prerequisites

**Compile the target project first.** CodeLens analyzes compiled *bytecode* under
`build/classes`, not source files — so if the project hasn't been built, there is
nothing for it to scan. Build it (skipping tests is fine and faster):

```bash
cd /path/to/project && ./gradlew build -x test   # or: ./gradlew classes testClasses
```

Then start the server:

```bash
codelens start --project /path/to/project
```

**Note:** The first start for a project may take several minutes (3-7 min for
large projects) while the server resolves the Gradle classpath and downloads
dependencies. Use `codelens status` to monitor — the server transitions through
`LOADING` (scanning) to `READY` (fully available). Subsequent starts are fast
due to cached dependencies.

**Verify the scan actually found your code before analyzing.** A project that
wasn't compiled still starts cleanly and reports `READY` — it just finds zero
project classes, so every query comes back empty and the emptiness looks like a
real answer. Guard against that by checking the counts first:

```bash
codelens classes stats --json   # look at projectClassCount
```

If `projectClassCount` is `0`, the project is almost certainly uncompiled (or you
pointed at the wrong directory). `codelens start`/`restart` also print a
`warning:` line to stderr in this case. To recover: build the project (command
above), then re-scan without a full restart and re-check:

```bash
codelens refresh --project /path/to/project
codelens classes stats --json
```

**Output:** in a terminal, commands print human-readable tables. Pass `--json`
to get the stable machine-readable payload — it is the documented shape and is
also auto-selected when output is piped or captured. When parsing output (e.g.
through `jq`), pass `--json` explicitly. Several examples below pipe JSON through
`jq` — install it via your package manager if you want to follow those verbatim.

## JDK setup and environment

codelens uses two JDKs independently — see
[JDK Resolution](https://charliek.github.io/codelens/concepts/jdk-resolution/)
for the full design.

- **Server JVM** runs `codelens-server-all.jar`. Auto-discovered: the newest
  installed JDK with major in `[21, 25]` across SDKMAN, mise, Homebrew, and
  the system JavaVMs / jvm dirs. Override with `CODELENS_JAVA_HOME`.
- **Project JVM** runs the target project's Gradle daemon. **Must be
  declared** by the project (or pass `--project-java` to bypass).

### Declaring the project JDK

Use any one of these in the project root (checked in this order):

```text
.sdkmanrc                       java=21.0.9-amzn        # or 21-tem, 25-graal, just "21", etc.
.java-version                   21.0.9-amzn             # or just "21"
gradle.properties               org.gradle.java.home=/abs/path/to/jdk
.mise.toml | .tool-versions     java = "temurin-21.0.9" | java temurin-21.0.9
```

### Discovery sources (in order)

JDKs are located across these sources for the project JVM resolution: SDKMAN
(`~/.sdkman/candidates/java`) → Homebrew (`openjdk@<major>` keg) → JavaVMs
(`/Library/Java/JavaVirtualMachines/*` on macOS, `/usr/lib/jvm/*` on Linux —
catches Homebrew casks and DMG installers) → mise. If the exact declared
version isn't installed, a **same-major** JDK is substituted and codelens
prints a one-line `note:` to stderr explaining the swap (e.g. declared
`21-tem` → used installed `21.0.9-amzn`). Cross-major substitution is
never performed.

### Common pitfalls

- **`gradle.properties::org.gradle.java.home=/abs/path` pointing at a deleted
  JDK** → error names the path explicitly. Fix by updating the path or
  installing the JDK there.
- **Declaring a vendor you don't have installed** (e.g. `21-tem` with only
  Corretto 21) → resolves silently to the same-major install and prints
  the substitution note. If exact vendor matters, install it
  (`sdk install java 21-tem`, `brew install --cask temurin`).
- **Homebrew cask installs** (`brew install --cask temurin`) ARE
  discoverable — they land under `/Library/Java/JavaVirtualMachines/`. If
  codelens claims a JDK is missing, run
  `ls /Library/Java/JavaVirtualMachines/` to confirm what's actually there.

### Useful env vars

| Variable | Purpose |
|----------|---------|
| `CODELENS_JAVA_HOME` | Force the JDK that runs the server |
| `JAVA_HOME` | Fallback for the server JVM when nothing in range was found |
| `CODELENS_JAVA_OPTS` | Extra JVM options (e.g. `-Xmx4g`), whitespace-split |
| `CODELENS_SERVER_JAR` | Force the server JAR path |
| `CODELENS_REPO_PATH` | Hint the codelens repo root for `server/app/build/libs/...` discovery |
| `--project-java <path>` flag | Bypass project-JDK declaration / resolution entirely |

## Class Discovery

### List Classes

```bash
codelens classes list [options]
```

**Filtering options:**
- `--package <pattern>` - Filter by package (supports wildcards)
- `--name <pattern>` - Filter by class name
- `--annotation <fqn>` - Classes annotated with specific annotation
- `--extends <fqn>` - Classes extending a specific class
- `--implements <fqn>` - Classes implementing a specific interface
- `--interfaces`, `-i` - Show only interfaces
- `--include-libraries` - Include library classes (project-only by default)

**Examples:**
```bash
# All classes in a package
codelens classes list --package com.example.handlers

# Find all @Singleton classes
codelens classes list --annotation javax.inject.Singleton

# Find classes implementing an interface
codelens classes list --implements com.example.api.RequestHandler

# Classes extending a base class
codelens classes list --extends com.example.AbstractService
```

### View Class Details

```bash
codelens classes show <fully-qualified-class-name>
```

Shows:
- Class type (class, interface, enum, annotation)
- Modifiers (public, abstract, final)
- Superclass and interfaces
- Declared methods and fields
- Annotations

### Scan Statistics

```bash
codelens classes stats
```

Shows summary of scanned classes, methods, packages.

## Method Search

```bash
codelens methods search [options]
```

**Filtering options:**
- `--name <pattern>` - Method name pattern
- `--return-type <fqn>` - Methods returning specific type
- `--annotation <fqn>` - Methods with specific annotation
- `--class <fqn>` - Methods in specific class
- `--package <pattern>` - Methods in classes within package
- `--include-libraries` - Include library methods

**Examples:**
```bash
# Find all methods named "handle"
codelens methods search --name handle

# Find methods returning a specific type
codelens methods search --return-type java.util.concurrent.CompletableFuture

# Find methods carrying an annotation
codelens methods search --annotation org.springframework.web.bind.annotation.GetMapping

# Methods in a specific class
codelens methods search --class com.example.UserService
```

## Inheritance Analysis

### Find Implementations

Find all classes implementing an interface or extending a class:

```bash
codelens classes implementations <fully-qualified-name>
```

**Examples:**
```bash
# Project classes implementing an interface (incl. transitive)
codelens classes implementations com.example.api.RequestHandler

# Project repositories extending a library interface
codelens classes implementations org.springframework.data.jpa.repository.JpaRepository
```

### View Hierarchy

Trace the inheritance chain for a class:

```bash
codelens classes hierarchy <fully-qualified-name>
```

Shows parent classes and implemented interfaces up the chain.

**Example:**
```bash
codelens classes hierarchy com.example.MyHandler
```

Output:
```
com.example.MyHandler
  └── implements: com.example.api.RequestHandler
  └── extends: java.lang.Object
```

## Dependency Analysis

Analyze class-level dependencies:

```bash
codelens classes dependencies <fully-qualified-name>
```

A single call returns both directions in one response: the JSON body has
separate `incoming` and `outgoing` arrays. Use `jq` to pick the side you want.

**Examples:**
```bash
# Both directions in one call (default)
codelens classes dependencies com.example.UserService

# What does UserService depend on?
codelens classes dependencies com.example.UserService --json | jq '.outgoing'

# What depends on UserService?
codelens classes dependencies com.example.UserService --json | jq '.incoming'
```

## Annotation Usages

Find all classes using a specific annotation:

```bash
codelens annotations usages <annotation-fqn>
```

**Examples:**
```bash
# Find all @Singleton classes
codelens annotations usages javax.inject.Singleton

# Find all @Path resources
codelens annotations usages javax.ws.rs.Path
```

## Call Sites (forward)

Extract, straight from bytecode, every invocation a class's methods make — the *forward*
view of "what does this code call":

```bash
codelens calls <fully-qualified-name> [--method <name>] [--descriptor <jvm-descriptor>]
```

Each call reports its `ownerType` (the callee's declaring type), `methodName`,
`descriptor`, the `constantArgs` (LDC string/number/class literals passed near the call),
and `lineNumber`. `--method` scopes to one method; `--descriptor` disambiguates overloads.

Lambdas and method references appear as call sites with `"invokeDynamic": true`: there
`ownerType`/`methodName` name the functional interface (SAM) being implemented, while
`implMethodOwner`/`implMethodName` point at the implementation — a synthetic `lambda$…`
body for a lambda, or the referenced method for a method reference. To read a lambda
body, follow `implMethodName`: `codelens calls <implMethodOwner> --method <implMethodName>`.

**Examples:**
```bash
# Everything UserService's methods invoke
codelens calls com.example.UserService

# Just one method, e.g. to see the constant args it passes to a builder
codelens calls com.example.config.DbConfig --method dataSource

# Pull out the calls to a particular API
codelens calls com.example.UserService --method handle --json \
  | jq '.methods[].calls[] | select(.ownerType | startswith("java.sql"))'
```

Known limit (Tier-1 scan): computed (non-constant) arguments don't appear in
`constantArgs`. (Lambda/method-reference targets *are* resolved — see the `invokeDynamic`
call sites above.)

## Cross-Reference (inverse)

Find everything across the project that references a type — the *inverse* of `calls`:

```bash
codelens xref <type-fqn> [--kind <KIND>] [--scope-implementing <fqn>] [--include-libraries]
```

References are grouped by `kind` — `EXTENDS`, `IMPLEMENTS`, `FIELD`, `PARAM`, `RETURN`,
`ANNOTATION`, `INSTANTIATION`, `CALL_RECEIVER` — with `countsByKind` and `countsByPackage`
aggregates so large fan-outs stay summarized. `--scope-implementing` intersects ("classes
that implement X **and** reference Y").

References span **generic type arguments**, not just the container: a field/return/param of
type `List<Foo>`, `Mono<Foo>`, or `Repository<Foo, Long>` is counted as a reference to `Foo`
(under `FIELD`/`RETURN`/`PARAM`), and `extends Base<Foo>` / `implements Marker<Foo>` count as
references to `Foo` (under `EXTENDS`/`IMPLEMENTS`). So `xref com.example.Foo` finds the type
even where it only ever appears wrapped in a `Promise<…>` / `Mono<…>` / collection.

**Examples:**
```bash
# Who references this service, and how?
codelens xref com.example.UserService

# Only the classes that hold it as a field
codelens xref com.example.UserService --kind FIELD

# Who calls into the JDBC DataSource API (the blocking surface)?
codelens xref javax.sql.DataSource

# Who returns/holds a Reactor Mono (the reactive surface)?
codelens xref reactor.core.publisher.Mono
```

`calls` + `xref` are duals: use `calls` to see what a method does; use `xref` to see who
depends on a type.

## Project Dependency Graph

Beyond per-class dependencies, view the whole project's structure and its most
depended-on classes:

```bash
# Most depended-on classes (high in-degree) — the project's "foundation"
codelens deps foundation [--min-dependents N]

# The full project dependency graph
codelens deps --format json
codelens deps --format dot -o deps.dot   # then: dot -Tpng deps.dot -o deps.png
```

`deps foundation` ranks classes by how many project classes depend on them — useful for
finding shared/core types, refactoring targets, or a sensible order to tackle work.

## Common Workflows

### Understanding a New Codebase

```bash
# 1. Get overview statistics
codelens classes stats

# 2. List top-level packages
codelens classes list --package "com.example.*" | head -20

# 3. Find entry points (controllers, handlers) by their framework interface/annotation
codelens classes list --annotation org.springframework.web.bind.annotation.RestController
```

### Finding Where Something is Used

```bash
# 1. Find implementations
codelens classes implementations com.example.UserRepository

# 2. Find incoming dependencies (one call returns both directions)
codelens classes dependencies com.example.UserRepository --json | jq '.incoming'

# 3. View the source (use codelens-source-lookup skill)
codelens source show com.example.UserRepositoryImpl
```

### Mapping Annotations

```bash
# Find all Guice modules
codelens classes list --extends com.google.inject.AbstractModule

# Find all injectable services
codelens annotations usages javax.inject.Singleton
```

## Tips

- Use `--include-libraries` sparingly - it can return many results
- Combine with `codelens-source-lookup` to view discovered classes
- Pattern matching uses glob syntax (`*` for any characters)
- Results are paginated by default; use `--page` and `--size` for large result sets

## Related Skills

- `codelens-source-lookup` - View source for discovered classes
- `codelens-ratpack-analysis` - A worked example of framework-migration analysis built
  entirely on these primitives (`calls`/`xref`/`deps`)

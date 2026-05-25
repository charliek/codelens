# JVM Analysis Examples

All commands accept `--json` (auto-enabled when stdout is not a TTY); examples below pipe
it through `jq`. `classes dependencies` returns both directions in one response — pick a
side with `jq '.incoming'` / `jq '.outgoing'` (there is no `--direction` flag).

## Scenario: Understanding the service layer

**Goal:** Map all services and their dependencies

```bash
# Find service classes — by naming convention or by annotation
codelens classes list --name "*Service"
codelens classes list --annotation org.springframework.stereotype.Service

# For each, inspect dependencies (both directions in one call)
codelens classes dependencies com.example.UserService
codelens classes dependencies com.example.OrderService | jq '.outgoing'
```

## Scenario: Finding interface implementations

**Goal:** Find all implementations of a repository interface

```bash
codelens classes implementations com.example.repository.UserRepository
codelens classes hierarchy com.example.repository.JdbcUserRepository
```

**Output:**
```
com.example.repository.JdbcUserRepository
  └── implements: com.example.repository.UserRepository
  └── extends: com.example.repository.AbstractRepository
      └── extends: java.lang.Object
```

## Scenario: Annotation-driven discovery

**Goal:** Find all REST endpoints

```bash
# Classes with JAX-RS @Path (or your framework's controller annotation)
codelens annotations usages javax.ws.rs.Path

# Methods carrying @GET
codelens methods search --annotation javax.ws.rs.GET
```

## Scenario: What does this method actually do? (`calls`)

**Goal:** See a method's real behavior from bytecode, not its name

```bash
# Every invocation the method makes, with constant args + line numbers
codelens calls com.example.UserService --method createUser

# Just the database calls
codelens calls com.example.UserService --method createUser --json \
  | jq '.methods[].calls[] | select(.ownerType | startswith("java.sql"))'

# Constant string/number/class args a @Bean/factory method passes to a builder
codelens calls com.example.config.DbConfig --method dataSource --json \
  | jq '.methods[].calls[] | {ownerType, methodName, constantArgs}'
```

## Scenario: Who references this type? (`xref`)

**Goal:** Impact analysis — find every caller/holder/subtype of a type

```bash
# All references, grouped; check the aggregates first
codelens xref com.example.core.BaseEntity --json | jq '{countsByKind, countsByPackage, totalCount}'

# Only the classes that hold it as a field
codelens xref com.example.UserService --kind FIELD

# Find the blocking surface vs the reactive surface of a codebase
codelens xref javax.sql.DataSource          # JDBC / blocking
codelens xref reactor.core.publisher.Mono   # Reactor / reactive

# Classes that implement an interface AND reference a type
codelens xref com.example.AuditLog --scope-implementing com.example.api.RequestHandler
```

## Scenario: Dependency impact & dead code

**Goal:** Gauge blast radius / spot unused classes

```bash
# How many project classes depend on this one?
codelens classes dependencies com.example.core.BaseEntity --json | jq '.incoming | length'

# A class with no incoming dependencies is likely unused
# (verify reflection/string-based wiring separately)
codelens classes dependencies com.example.LegacyHelper --json | jq '.incoming'
```

## Scenario: Project structure & foundation classes (`deps`)

**Goal:** Find the core/shared classes and a sensible order to tackle work

```bash
# Most depended-on classes (high in-degree)
codelens deps foundation

# Only classes with many dependents
codelens deps foundation --min-dependents 5

# Whole-project graph for visualization
codelens deps --format dot -o deps.dot && dot -Tpng deps.dot -o deps.png
```

## Complex queries

### Async methods

```bash
# Methods returning CompletableFuture
codelens methods search --return-type java.util.concurrent.CompletableFuture

# Methods returning a project type
codelens methods search --return-type com.example.Result
```

### Factory methods

```bash
# Methods whose name starts with create, in a package
codelens methods search --name "create*" --package "com.example.*"
```

### Instantiations of a type

```bash
# Who constructs this class? (INSTANTIATION references)
codelens xref com.example.HttpClient --kind INSTANTIATION
```

## JSON output

Add `--json` to any command (or just redirect/pipe — it auto-enables off a TTY):

```bash
codelens classes list --package com.example --json
```

```json
{
  "classes": [
    {
      "fqn": "com.example.UserService",
      "simpleName": "UserService",
      "packageName": "com.example",
      "source": "PROJECT",
      "isInterface": false
    }
  ],
  "totalCount": 1,
  "page": 0
}
```

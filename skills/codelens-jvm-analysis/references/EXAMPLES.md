# JVM Analysis Examples

## Scenario: Understanding Service Layer

**Goal:** Map all services and their dependencies

```bash
# Find all service classes (by naming convention)
codelens classes list --name "*Service"

# Or by annotation
codelens classes list --annotation javax.inject.Singleton

# For each service, check dependencies
codelens classes dependencies com.example.UserService
codelens classes dependencies com.example.OrderService
```

## Scenario: Finding Interface Implementations

**Goal:** Find all implementations of a repository interface

```bash
# Find implementations
codelens classes implementations com.example.repository.UserRepository

# Check hierarchy of a specific implementation
codelens classes hierarchy com.example.repository.DynamoUserRepository
```

**Output:**
```
com.example.repository.DynamoUserRepository
  └── implements: com.example.repository.UserRepository
  └── extends: com.example.repository.AbstractRepository
      └── extends: java.lang.Object
```

## Scenario: Annotation-Driven Discovery

**Goal:** Find all REST endpoints

```bash
# Find all classes with JAX-RS @Path
codelens annotations usages javax.ws.rs.Path

# Find all methods with @GET
codelens methods search --annotation javax.ws.rs.GET
```

## Scenario: Dependency Impact Analysis

**Goal:** Understand impact of changing a core class

```bash
# What depends on the class?
codelens classes dependencies com.example.core.BaseEntity --direction incoming

# What does the class depend on?
codelens classes dependencies com.example.core.BaseEntity --direction outgoing
```

## Scenario: Finding Dead Code

**Goal:** Find classes with no incoming dependencies

```bash
# Check a suspect class
codelens classes dependencies com.example.LegacyHelper --direction incoming

# If empty, likely unused (verify with grep for reflection usage)
```

## Scenario: Module Mapping

**Goal:** Understand Guice module structure

```bash
# Find all modules
codelens classes list --extends com.google.inject.AbstractModule

# Check what each module depends on
codelens classes dependencies com.example.MainModule
```

## Complex Queries

### Find All Handler Methods

```bash
# Methods named "handle" in Handler implementations
codelens methods search --name handle --annotation Override
```

### Find Async Methods

```bash
# Methods returning Promise
codelens methods search --return-type ratpack.exec.Promise

# Methods returning CompletableFuture
codelens methods search --return-type java.util.concurrent.CompletableFuture
```

### Find Factory Methods

```bash
# Static methods returning specific type
codelens methods search --name "create*" --return-type com.example.Config
```

## Output Formats

### Default (Table)

```
CLASS                                    PACKAGE                  TYPE
com.example.UserService                  com.example              class
com.example.UserServiceImpl              com.example              class
com.example.UserRepository               com.example              interface
```

### JSON (for scripting)

```bash
codelens classes list --package com.example --format json
```

```json
{
  "classes": [
    {
      "fqn": "com.example.UserService",
      "package": "com.example",
      "simpleName": "UserService",
      "type": "class",
      "modifiers": ["public", "abstract"]
    }
  ]
}
```

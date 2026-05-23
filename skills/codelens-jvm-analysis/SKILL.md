---
name: codelens-jvm-analysis
description: |
  Explore and analyze JVM codebases (Java/Kotlin) - discover classes, search methods,
  trace inheritance hierarchies, and map dependencies. Use this skill when you need
  to understand codebase structure, find implementations of interfaces, locate classes
  by annotation, or analyze dependency relationships between classes.
---

# JVM Analysis

This skill enables exploration and analysis of JVM codebases through bytecode scanning.

## When to Use

- Find all classes in a package or matching a pattern
- Search for methods by name, return type, or annotation
- Find all implementations of an interface
- Trace class inheritance hierarchies
- Analyze dependencies between classes
- Find usages of a specific annotation

## Prerequisites

Ensure the CodeLens server is running for your project:

```bash
codelens start --project /path/to/project
```

Several examples below pipe JSON output through `jq` — install it via your
package manager if you want to follow those verbatim.

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

# Find all Handler implementations
codelens classes list --implements ratpack.handling.Handler

# Classes extending AbstractModule
codelens classes list --extends com.google.inject.AbstractModule
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

# Find methods returning Promise
codelens methods search --return-type ratpack.exec.Promise

# Find all @Provides methods
codelens methods search --annotation com.google.inject.Provides

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
# All Handler implementations
codelens classes implementations ratpack.handling.Handler

# All AbstractModule subclasses
codelens classes implementations com.google.inject.AbstractModule
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
  └── implements: ratpack.handling.Handler
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
codelens classes dependencies com.example.UserService | jq '.outgoing'

# What depends on UserService?
codelens classes dependencies com.example.UserService | jq '.incoming'
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

## Common Workflows

### Understanding a New Codebase

```bash
# 1. Get overview statistics
codelens classes stats

# 2. List top-level packages
codelens classes list --package "com.example.*" | head -20

# 3. Find entry points (handlers, controllers)
codelens classes list --implements ratpack.handling.Handler
```

### Finding Where Something is Used

```bash
# 1. Find implementations
codelens classes implementations com.example.UserRepository

# 2. Find incoming dependencies (one call returns both directions)
codelens classes dependencies com.example.UserRepository | jq '.incoming'

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
- `codelens-ratpack-analysis` - Ratpack-specific analysis (handlers, promises)

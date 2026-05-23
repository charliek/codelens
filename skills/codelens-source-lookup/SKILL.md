---
name: codelens-source-lookup
description: |
  Retrieve and display JVM source code (Java/Kotlin) from projects, libraries, or JDK.
  Use this skill when you need to view class implementations, method signatures, javadoc,
  or generate stubs for any JVM class - whether from the current project, third-party
  libraries, or the JDK itself. Supports decompilation when source is unavailable.
---

# Source Lookup

This skill enables viewing source code for any JVM class accessible to the project.

## When to Use

- View the implementation of a class or method
- Read javadoc documentation for a library class
- Generate interface stubs for understanding API contracts
- Inspect JDK source code
- View decompiled bytecode when source is unavailable

## Prerequisites

Ensure the CodeLens server is running for your project:

```bash
codelens start --project /path/to/project
```

## Commands

### View Full Source

```bash
codelens source show <fully-qualified-class-name>
```

**Examples:**
```bash
# Project class
codelens source show com.example.MyHandler

# Library class (auto-downloads source JAR if available)
codelens source show ratpack.handling.Context

# JDK class
codelens source show java.util.concurrent.CompletableFuture
```

### View Method Source

Extract a specific method from a class:

```bash
codelens source method <class-fqn> <method-name>
```

**Options:**
- `--params <types>` - Disambiguate overloaded methods (comma-separated parameter types)
- `--context <n>` - Include n lines before/after the method

**Examples:**
```bash
# Simple method
codelens source method com.example.UserService getUser

# Overloaded method - specify parameter types
codelens source method com.example.UserService findUsers --params "String,int"

# With surrounding context
codelens source method com.example.MyHandler handle --context 5
```

## Output Today

`codelens source show <fqn>` returns the full resolved source for the class
(default behavior). Richer output modes — stub generation, signatures-only,
javadoc extraction, visibility filtering, and Java/Kotlin language
switching — exist on the server endpoint but are not yet exposed on the CLI.
They are queued as a follow-up Go-CLI enhancement; once landed, this section
will be expanded with `--format`, `--visibility`, and `--lang` examples.

## Source Resolution

CodeLens resolves source in this order:

1. **Project source** - Your local source files
2. **Library source JARs** - Downloaded from Maven Central
3. **JDK source** - From `src.zip` or JDK modules
4. **Decompilation** - Fallback when source unavailable

**Options:**
- `--no-decompile` - Disable decompilation fallback
- `--refresh` - Force re-download of source JARs

## Tips

- Combine with `codelens-jvm-analysis` skill to find classes first, then view their source
- When viewing library code, source JARs are cached locally after first download
- Decompiled code may not perfectly match original source but preserves logic

## Related Skills

- `codelens-jvm-analysis` - Find classes and methods to view
- `codelens-ratpack-analysis` - Analyze Ratpack-specific handler source

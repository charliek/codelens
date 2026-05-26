---
name: codelens-source-lookup
description: |
  Use this skill to fetch and display the real source code of a named JVM type — a Java or
  Kotlin class, interface, or method referenced by name or fully-qualified name. Crucially,
  it works even when no source file exists in the project: it is the way to read the actual
  implementation of compiled third-party dependencies, library internals, and JDK classes
  by auto-downloading source JARs or decompiling bytecode. Reach for it whenever the goal is
  to SEE actual code: read a method's implementation body, view an entire class, inspect
  library or JDK internals, extract signatures or javadoc, or generate an interface
  stub/skeleton. Fits asks like "show me the source of X," "what does the body of X.foo do,"
  "how does library Y implement Z," "I don't have the sources jar — pull up class W," or
  "stub out interface V." Do NOT use it to grep/search the repo, find callers or
  implementers of an interface, locate documentation pages, format or restyle code, or
  explain language concepts in the abstract.
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

**Output:** in a terminal these commands print the source code directly. Pass
`--json` for the structured envelope documented in
[FORMATS.md](references/FORMATS.md) (`source.content`, `filePath`, `language`,
…) — that JSON shape is also auto-selected when output is piped or captured.

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
codelens source show com.google.common.collect.ImmutableList

# JDK class
codelens source show java.util.concurrent.CompletableFuture
```

### View Method Source

Extract a specific method from a class:

```bash
codelens source method <class-fqn> <method-name>
```

**Options:**
- `--param-types <types>` - Disambiguate overloaded methods (comma-separated parameter types)
- `--context <n>` - Include n lines before/after the method

**Examples:**
```bash
# Simple method
codelens source method com.example.UserService getUser

# Overloaded method - specify parameter types
codelens source method com.example.UserService findUsers --param-types "String,int"

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

The server endpoint accepts query parameters to disable decompilation
fallback or force a fresh source-JAR download, but those toggles are not
yet exposed on the CLI. They are queued alongside the format/visibility
flags in the follow-up Go-CLI enhancement noted above.

## Tips

- Combine with `codelens-jvm-analysis` skill to find classes first, then view their source
- When viewing library code, source JARs are cached locally after first download
- Decompiled code may not perfectly match original source but preserves logic

## Related Skills

- `codelens-jvm-analysis` - Find classes and methods to view
- `codelens-ratpack-analysis` - Analyze Ratpack-specific handler source

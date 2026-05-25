# Quick Start

This walks through analyzing a JVM project end to end. It assumes codelens is
[installed](installation.md) and a JDK 21+ is available.

## 1. Build the target project

codelens analyzes compiled bytecode, so build the project first:

```bash
cd ~/work/my-service
./gradlew build -x test   # or: ./gradlew classes testClasses
```

See [Target Project Setup](../concepts/target-project.md) for the full
prerequisites.

## 2. Run a command

The server auto-starts on the first command. Most commands take the project from
the current directory; use `-p/--project` to point elsewhere.

```bash
codelens classes list --package "com.example.*"
```

The first invocation starts the background server (this includes the initial
bytecode scan, which can take a few seconds to a minute on large projects).
Subsequent commands reuse it.

## 3. Explore the codebase

```bash
# Classes
codelens classes show com.example.UserService
codelens classes implementations com.example.Repository
codelens classes hierarchy com.example.UserService
codelens classes dependencies com.example.UserService

# Methods and annotations
codelens methods search --name "find*"
codelens annotations usages javax.inject.Singleton

# Source — your code, library code, or the JDK
codelens source show com.example.UserService
codelens source show java.util.HashMap
codelens source show com.google.common.collect.ImmutableList --stub
```

See the [CLI Reference](../reference/cli.md) for every command and flag.

## 4. Machine-readable output

Every command supports `--json` (auto-enabled when stdout is not a TTY):

```bash
codelens classes list --json | jq '.[].name'
```

## 5. Manage the server

```bash
codelens status            # status for the current project
codelens list              # all running servers
codelens refresh           # rescan after recompiling
codelens stop              # stop the server
```

You can run servers for multiple projects at once; each is keyed by project path
and gets its own port. The server also shuts down on its own after an idle
timeout (default 30m).

## Next steps

- [CLI Reference](../reference/cli.md)
- [JDK Resolution](../concepts/jdk-resolution.md) — how the server and project
  JDKs are chosen
- [Framework Analysis](../concepts/framework-analysis.md) — composing the
  primitives for framework-specific questions

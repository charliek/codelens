# codelens

codelens analyzes JVM codebases. It loads a project's compiled bytecode and
resolved classpath, then answers structural questions about it over a small HTTP
API and a command-line interface — classes, methods, annotations, type
hierarchies, dependencies, and source (including JDK and library source).

Java and Kotlin are the primary, tested languages.

codelens deliberately stops at general primitives. Framework-specific analysis —
"find the request handlers", "assess a migration" — is composed from those
primitives rather than built into the tool; see
[Framework Analysis](concepts/framework-analysis.md).

## How it works

codelens has two parts:

- **Server** (Kotlin/Ktor): runs in the background, scans a target project's
  bytecode with ClassGraph, resolves the classpath via the Gradle Tooling API,
  and serves analysis queries over a local REST API. It shuts itself down when
  idle.
- **CLI** (Go): a single static binary that manages the server's lifecycle and
  formats results. It auto-starts the server on first use.

```bash
cd ~/work/my-service
codelens classes list --package "com.example.*"
codelens classes show com.example.UserService
codelens source show java.util.HashMap
```

## What it's good for

- Mapping an unfamiliar codebase: what implements an interface, what calls what,
  what a package contains.
- Pulling exact source or bytecode-derived stubs for any class on the
  classpath — your code, library code, or the JDK.
- Feeding precise, structural context to other tools and LLM workflows via
  `--json`.

## Scope

- Targets are analyzed from **compiled bytecode**, so the project must build
  first. See [Target Project Setup](concepts/target-project.md).
- The target must be a Gradle project (Gradle wrapper present).
- The server runs on a JDK 21+; codelens auto-discovers one from SDKMAN or
  Homebrew. See [JDK Resolution](concepts/jdk-resolution.md).

## Next steps

- [Installation](getting-started/installation.md)
- [Quick Start](getting-started/quick-start.md)
- [CLI Reference](reference/cli.md)
- [Server & JAR Discovery](concepts/discovery.md)

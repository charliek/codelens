# codelens

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

codelens analyzes JVM codebases. It loads a project's compiled bytecode and
resolved classpath, then answers structural questions about it over a small HTTP
API and a command-line interface — classes, methods, annotations, type
hierarchies, dependencies, and source (including JDK and library source).

Java and Kotlin are the primary, tested languages. codelens also includes a set
of Ratpack-migration helpers; these are secondary and may be phased out over
time.

**📖 Full documentation: [charliek.github.io/codelens](https://charliek.github.io/codelens/)**

## How it works

codelens has two parts:

- **Server** (Kotlin/Ktor): runs in the background, scans the target's bytecode
  with ClassGraph, resolves the classpath via the Gradle Tooling API, and serves
  analysis over a local REST API. It shuts down when idle.
- **CLI** (Go): a single static binary that manages the server and formats
  results. It auto-starts the server on first use.

## Install

```bash
brew tap charliek/tap
brew install codelens
```

codelens runs a **JDK 21+** server under the hood and auto-discovers one from
SDKMAN or Homebrew. Install one if needed:

```bash
sdk install java 21.0.9-amzn   # SDKMAN
# or
brew install openjdk@21        # Homebrew
```

See [Installation](https://charliek.github.io/codelens/getting-started/installation/)
for standalone/manual layouts and the JDK details.

## Install the skills

codelens publishes its four skills (JVM analysis, Kotlin linting, source lookup,
and Ratpack migration) as agent skills. They drive the codelens CLI for you, so
install the CLI with the steps above first.

**Any agent, with [`skills`](https://skills.sh):** installs into Claude Code,
Cursor, Codex, Copilot, Windsurf, and dozens of other agents, auto-detecting the
ones you have.

```bash
npx skills add charliek/codelens
```

**Claude Code plugin:** a native alternative that namespaces the skills as
`codelens:<skill-name>`.

```bash
/plugin marketplace add charliek/codelens
/plugin install codelens@codelens
```

## Quick start

codelens analyzes compiled bytecode, so build the target project first, then run
a command from its directory (the server auto-starts):

```bash
cd ~/work/my-service
./gradlew build -x test

codelens classes list --package "com.example.*"
codelens classes show com.example.UserService
codelens source show java.util.HashMap
codelens stop
```

Every command supports `--json`. See the
[Quick Start](https://charliek.github.io/codelens/getting-started/quick-start/)
and [CLI Reference](https://charliek.github.io/codelens/reference/cli/).

## Documentation

| Topic | |
|-------|--|
| Installation & quick start | <https://charliek.github.io/codelens/getting-started/installation/> |
| CLI & HTTP API reference | <https://charliek.github.io/codelens/reference/cli/> |
| Server & JAR discovery | <https://charliek.github.io/codelens/concepts/discovery/> |
| JDK resolution | <https://charliek.github.io/codelens/concepts/jdk-resolution/> |
| Development (build from source) | <https://charliek.github.io/codelens/development/setup/> |

The docs site is built with MkDocs; preview it locally with `uv run --group docs mkdocs serve`.

## License

codelens is licensed under the [Apache License, Version 2.0](LICENSE).

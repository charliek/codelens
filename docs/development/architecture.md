# Architecture

codelens is a CLI client and a background analysis server.

```text
┌──────────────────────────────────────────────────────────────────┐
│ Developer machine                                                  │
│                                                                    │
│   Terminal                          Background process             │
│   ┌────────────────────┐  HTTP      ┌────────────────────────────┐ │
│   │  codelens (Go CLI)  │──────────▶│  codelens server (Kotlin)   │ │
│   │  - manages server   │◀──────────│  - scans target bytecode    │ │
│   │  - formats output   │           │  - serves /api/v1/* + /admin │ │
│   └────────────────────┘           │  - idle auto-shutdown        │ │
│            │                        └────────────────────────────┘ │
│            │ state/logs                          │ analyzes         │
│            ▼                                     ▼                  │
│   ~/.cache/codelens/                  Target Gradle project          │
│   ├─ servers/*.json                   (compiled build/classes + jars)│
│   └─ logs/*.log                                                     │
└──────────────────────────────────────────────────────────────────┘
```

## Server (Kotlin/Gradle, multi-module)

| Module | Responsibility |
|--------|----------------|
| `server:core` | Shared data models and interfaces |
| `server:classgraph` | ClassGraph-based bytecode analysis |
| `server:gradle-resolver` | Classpath resolution via the Gradle Tooling API |
| `server:source-resolver` | Library/JDK source retrieval, decompilation, stub generation |
| `server:ktlint` | Warm ktlint server for Kotlin lint/format |
| `server:app` | Ktor HTTP application (routes, services); builds the fat JAR |

Entry point: `server/app/src/main/kotlin/codelens/server/Application.kt`. The
server targets JVM 21.

## CLI (Go)

| Path | Responsibility |
|------|----------------|
| `cmd/codelens/main.go` | Entry point |
| `internal/cli/` | Cobra commands (lifecycle + analysis groups) |
| `internal/client/` | HTTP client; characterization tests lock the wire contract |
| `internal/server/` | Child-process lifecycle: spawn, ready-line parsing, stop |
| `internal/settings/` | Env config, JAR discovery, Java/SDKMAN/Homebrew/Gradle detection |
| `internal/state/` | Per-project `ServerState` repository |
| `internal/output/` | JSON / table / TTY rendering |
| `internal/errors/` | Typed exit codes |
| `test/e2e/` | Golden-fixture suite: CLI vs a live JVM |

## Startup contract

The server emits structured stdout lines that any client can track. The CLI
matches these to detect readiness:

| Line | Meaning |
|------|---------|
| `CODELENS_STARTING port=<p> host=<h>` | Listener bound; initial classpath resolution + scan still running. Not ready. |
| `CODELENS_READY port=<p> host=<h> version=<v>` | Initial scan complete; all endpoints serve real data. |
| `CODELENS_ERROR reason=<r> message="<m>"` | Initial scan failed; the server exits 1. `reason` ∈ `CLASSPATH_RESOLUTION`, `SCAN`, `UNKNOWN`. |

The ready signal is delayed until the first scan finishes, so clients use a
startup timeout large enough to cover it. `codelens start` defaults to 180s and
exposes `--timeout`.

## Related

- [Server & JAR Discovery](../concepts/discovery.md)
- [JDK Resolution](../concepts/jdk-resolution.md)
- [HTTP API Reference](../reference/api.md)

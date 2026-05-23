# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CodeLens is a developer tool for analyzing Ratpack-based JVM codebases to assist with migration planning. It consists of two components:

1. **Server** (Kotlin/Ktor): Background service that analyzes target project bytecode using ClassGraph and serves results via HTTP REST API
2. **CLI** (Go/Cobra, in `cli-go/`): User-facing interface that manages server lifecycle and presents analysis results

A second CLI in `cli/` (Python/Typer) is the historical reference implementation; it is being retired once the Go port has been green on `main` for a release cycle. New work should target `cli-go/`. The two CLIs share the same HTTP wire contract and on-disk state-file format, so they can coexist and talk to the same running server.

## Build Commands

### Server (Kotlin)

```bash
# Build the fat JAR (includes all dependencies)
./gradlew :server:app:shadowJar
# Output: server/app/build/libs/codelens-server-all.jar

# Run all Kotlin tests
./gradlew test

# Run a single test class
./gradlew test --tests "codelens.server.SomeTest"

# Run a specific test method
./gradlew test --tests "codelens.server.SomeTest.testMethod"

# Run server directly via Gradle
./gradlew :server:app:run --args="--project /path/to/project"
```

### Go CLI

Requires `mise` for tool versions (`go = "1.24"`, `golangci-lint = "2.10.1"`).

```bash
# First-time setup: install pinned toolchain
mise install

cd cli-go

# Generate the embedded version.txt then build
go generate ./...
go build -o ./bin/codelens ./cmd/codelens

# Or do both via the Makefile
make build

# Install to ~/.local/bin
make install

# Run all Go tests (parity tests are gated behind -run TestParity)
go test ./...

# Run the side-by-side parity suite against a live JVM
# (requires the Python CLI installed on PATH and the server JAR built)
go test -v -run TestParity ./test/parity/...

# Lint
golangci-lint run ./...
```

### Python CLI (legacy, kept until parity has been green for a release cycle)

```bash
cd cli
uv tool install --editable .
uv run pytest
```

## Architecture

### Gradle Multi-Module Structure

The server is split into six Gradle modules:
- `server:core` - Shared data models and interfaces
- `server:classgraph` - ClassGraph-based bytecode analysis
- `server:gradle-resolver` - Gradle Tooling API for classpath resolution
- `server:source-resolver` - Library/JDK source retrieval, decompilation, and stub generation
- `server:ktlint` - Warm ktlint server for Kotlin linting/formatting
- `server:app` - Ktor HTTP server application with routes and services

### Go CLI structure (`cli-go/`)

- `cmd/codelens/main.go` — entry point
- `internal/cli/` — Cobra commands (one file per group: classes, methods, handlers, promises, …)
- `internal/client/` — HTTP client; characterization tests lock the wire contract (`client_test.go`)
- `internal/server/` — child-process lifecycle: spawn, ready-line parsing, graceful stop
- `internal/state/` — ServerState repository at `~/.cache/codelens/servers/<hash>.json`
- `internal/settings/` — env-driven config, Java/SDKMAN/Gradle detection, JAR discovery
- `internal/output/` — JSON / table / tty rendering
- `internal/errors/` — typed exit codes (Success=0, ServerError=4, Timeout=5, NotRunning=7, …)
- `test/parity/` — runs Go and Python CLIs side by side and structurally diffs `--json` output

### Key Entry Points

- Server main: `server/app/src/main/kotlin/codelens/server/Application.kt`
- Go CLI main: `cli-go/cmd/codelens/main.go`
- Python CLI main (legacy): `cli/src/codelens_cli/main.py`

### State Storage

Server state is persisted in `~/.cache/codelens/`:
- `servers/{hash}.json` - Server state files (keyed by SHA256 hash of project path, first 12 hex chars)
- `logs/{hash}.log` - Server log files

Both CLIs use this directory verbatim (Go does NOT use `os.UserCacheDir()` on macOS so the location stays the same across both implementations).

## Version Management

Version is defined in `version.txt` at the repository root. Both Gradle and the Go CLI's `go generate` step read it; a copy lives at `cli-go/internal/version/version.txt` (gitignored — regenerated each build).

## Server JAR discovery

The Go CLI finds `codelens-server-all.jar` in this priority order:

1. `--server-jar` flag (lifecycle commands only)
2. `CODELENS_SERVER_JAR` env var
3. `$CODELENS_REPO_PATH/server/app/build/libs/codelens-server-all.jar`
4. Walked-up repo root (presence of `gradlew` + `settings.gradle.kts`)
5. `~/.codelens/codelens-server-all.jar` (installed-binary convention)

This lets the binary work in three modes: dev (inside the repo), env-var override, or two-binary install (`codelens` on PATH + JAR placed in `~/.codelens/`).

## Testing

Test fixtures are in `test-fixtures/sample-ratpack-app/` - a minimal Ratpack project for integration testing.

The Go parity suite (`cli-go/test/parity/`) is the most rigorous proof of correctness: it spawns the JVM server once, then exec's the Go binary and the Python binary side by side for every documented endpoint and structurally diffs the `--json` output (after blanking mutable fields like pid/port/timestamps).

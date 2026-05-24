# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CodeLens is a developer tool for analyzing JVM codebases (Java and Kotlin are the primary, tested languages). It loads a project's compiled bytecode and resolved classpath and answers structural questions over an HTTP API and CLI. It also includes Ratpack-migration helpers, which are a secondary capability that may be phased out over time (do not treat Ratpack as the headline framing in user-facing copy). It consists of two components:

1. **Server** (Kotlin/Ktor): Background service that analyzes target project bytecode using ClassGraph and serves results via HTTP REST API
2. **CLI** (Go/Cobra, in `cli/`): User-facing interface that manages server lifecycle and presents analysis results

The CLI was ported from an earlier Python/Typer implementation (see git history); the two shared an HTTP wire contract and on-disk state-file format, which the Go CLI preserves.

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

cd cli

# Generate the embedded version.txt then build
go generate ./...
go build -o ./bin/codelens ./cmd/codelens

# Or do both via the Makefile
make build

# Install to ~/.local/bin
make install

# Run all Go tests (e2e golden tests are gated behind -run TestE2E)
go test ./...

# Run the golden e2e suite against a live JVM (requires the server JAR built)
go test -v -run TestE2E ./test/e2e/...

# Regenerate golden fixtures after an intentional output change
UPDATE_GOLDEN=1 go test -run TestE2E ./test/e2e/...

# Lint
golangci-lint run ./...
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

### Go CLI structure (`cli/`)

- `cmd/codelens/main.go` — entry point
- `internal/cli/` — Cobra commands (one file per group: classes, methods, handlers, promises, …)
- `internal/client/` — HTTP client; characterization tests lock the wire contract (`client_test.go`)
- `internal/server/` — child-process lifecycle: spawn, ready-line parsing, graceful stop
- `internal/state/` — ServerState repository at `~/.cache/codelens/servers/<hash>.json`
- `internal/settings/` — env-driven config, Java/SDKMAN/Gradle detection, JAR discovery
- `internal/output/` — JSON / table / tty rendering
- `internal/errors/` — typed exit codes (Success=0, ServerError=4, Timeout=5, NotRunning=7, …)
- `test/e2e/` — runs the CLI against a live JVM and diffs `--json` output against committed golden fixtures

### Key Entry Points

- Server main: `server/app/src/main/kotlin/codelens/server/Application.kt`
- Go CLI main: `cli/cmd/codelens/main.go`

### State Storage

Server state is persisted in `~/.cache/codelens/`:
- `servers/{hash}.json` - Server state files (keyed by SHA256 hash of project path, first 12 hex chars)
- `logs/{hash}.log` - Server log files

The CLI uses this directory verbatim (Go does NOT use `os.UserCacheDir()` on macOS, so the location matches the original Python implementation and any existing on-disk state).

## Version Management

Version is defined in `version.txt` at the repository root. Both Gradle and the Go CLI's `go generate` step read it; a copy lives at `cli/internal/version/version.txt` (gitignored — regenerated each build). The release workflow overwrites `version.txt` from the git tag before building, so released artifacts always match the tag.

The Claude Code plugin manifest `.claude-plugin/plugin.json` also hardcodes the version, and Claude Code reads it straight from the committed default branch (not from a build artifact). The release workflow's `sync-version` job commits `version.txt` and `.claude-plugin/plugin.json` back to `main` from the tag after each release (idempotent), keeping the plugin version in lockstep so installers see the update. `scripts/set-version.sh X.Y.Z` is the underlying tool for setting both locally.

## Releases

Tag-driven via GoReleaser. `/release:release` pushes a `vX.Y.Z` tag → `.github/workflows/release.yaml` builds the server JAR (Gradle) and the Go binaries (darwin/linux × amd64/arm64), bundles the JAR into each archive, publishes a GitHub Release, and pushes a Homebrew formula to `charliek/homebrew-tap` (needs the `HOMEBREW_TAP_TOKEN` secret). A follow-up `sync-version` job then commits `version.txt` and `.claude-plugin/plugin.json` back to `main` from the tag so the committed (installer-facing) versions match the release. Config: `.goreleaser.yaml`. Docs deploy separately via `.github/workflows/docs.yml` (MkDocs → GitHub Pages).

## Server JAR discovery

The Go CLI finds `codelens-server-all.jar` in this priority order (`cli/internal/settings/jar.go`):

1. `--server-jar` flag (lifecycle commands only)
2. `CODELENS_SERVER_JAR` env var
3. `$CODELENS_REPO_PATH/server/app/build/libs/codelens-server-all.jar`
4. Walked-up repo root (presence of `gradlew` + `settings.gradle.kts`)
5. `../libexec/codelens-server-all.jar` relative to the resolved binary (Homebrew / packaged install)
6. `~/.codelens/codelens-server-all.jar` (installed-binary convention)

This lets the binary work in three modes: dev (inside the repo), env-var override, or packaged install (Homebrew, or `codelens` on PATH + JAR in `~/.codelens/`).

## JDK resolution

Two JVMs, resolved separately in `cli/internal/settings` (`javahome.go`, `projectjava.go`) — see `docs/concepts/jdk-resolution.md`:

- **Server JVM** (runs the JAR): `CODELENS_JAVA_HOME` → highest installed JDK with major in `[ServerJavaFloor=21, ServerJavaCeiling=25]` across SDKMAN (`~/.sdkman/candidates/java/*`), mise, and Homebrew (`openjdk@21..@25`) → `JAVA_HOME` → bare `java`. Picks the newest in range because the server JVM must be ≥ the target's bytecode; warns when a target is newer (`service.go`).
- **Project JVM** (`--project-java-home`): the target project's Gradle daemon JDK. **Required and never guessed** — `resolveProjectJava` (`service.go`) resolves the project's *declared* JDK (`.sdkmanrc`/`.java-version`/`gradle.properties`/mise via `DetectProjectJavaVersion` + `ResolveProjectJavaHome`, resolved through SDKMAN→Homebrew→mise via `FindJavaForVersion`) and **hard-errors + aborts** if undeclared or unresolvable. `--project-java` bypasses it. Passed in both JAR and Gradle server modes.

The ClassGraph version (`gradle/libs.versions.toml`) must support the ceiling JDK's class-file version.

## Testing

Test fixtures are in `test-fixtures/sample-ratpack-app/` - a minimal Ratpack project for integration testing.

The Go e2e golden suite (`cli/test/e2e/`) is the most rigorous proof of correctness: it spawns the JVM server once, then exec's the CLI for every documented endpoint and diffs the `--json` output against committed golden fixtures (after blanking mutable fields like pid/port/timestamps and templating machine-specific paths). Regenerate the fixtures after an intentional output change with `UPDATE_GOLDEN=1 go test -run TestE2E ./test/e2e/...`. The suite is gated behind `-run TestE2E` and skips when the server JAR is absent.

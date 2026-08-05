# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CodeLens is a developer tool for analyzing JVM codebases (Java and Kotlin are the primary, tested languages). It loads a project's compiled bytecode and resolved classpath and answers structural questions over an HTTP API and CLI. Its primitives are deliberately framework-agnostic — `classes`, `methods`, `calls` (forward call-site extraction), `xref` (inverse type cross-reference), `deps` (project dependency graph + foundation), `annotations`, `hierarchy`, `source`. Framework-specific analysis (e.g. a migration assessment) is **not** baked into the tool; it is composed from these primitives, with framework knowledge living in Claude Code skills — the `codelens-ratpack-analysis` skill is the worked example of that approach. It consists of two components:

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

### Docs

**Not part of the Kotlin or Go gates above.** The docs site has its own
toolchain (uv/Python) and its own CI workflows; `./gradlew test` and
`go test ./...` do not cover it, and it does not need to run for a change that
touches neither. Run it for commits touching `docs/`, `zensical.toml`,
`pyproject.toml`, `uv.lock`, or either docs workflow. Both workflows trigger on
those shared inputs (and each additionally on its own file), because a
dependency or lockfile change can break the build just as easily as a content
change:

```bash
uv run --locked zensical build --strict
```

The site is [Zensical](https://zensical.org) (not MkDocs — migrated in plan
001), configured in `zensical.toml`, built into `site-build/`. `--strict`
fails on broken links and anchors and is what both CI workflows run, so run it
locally before pushing docs changes. `uv run zensical serve` previews on
`http://127.0.0.1:7071` (note `serve --strict` is unsupported — verify via
`build`).

The look comes from the shared
[stridelabs-docs-theme](https://github.com/charliek/stridelabs-docs-theme)
package, pinned by tag in `pyproject.toml`. Palette, fonts and feature toggles
live there, not here — do not add `theme.palette`, `theme.features`, or a
`[project.theme.font]` table to `zensical.toml`. The last one is the sharp
edge: it re-enables Zensical's Google Fonts `<link>` on every page while the
theme's self-hosted faces keep loading anyway.

Two gotchas worth knowing: Zensical **silently ignores unknown config keys**
even under `--strict`, so a green build does not prove a config edit did what
you meant; and the `pymdownx.emoji` callables live in the
`zensical.extensions.emoji` namespace — the Material for MkDocs
`material.extensions.emoji` namespace aborts the build.

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
- `internal/cli/` — Cobra commands tagged with `GroupID` so `--help` renders them under "Server lifecycle" (`lifecycle.go`), "Code analysis" (`classes.go`, `methods.go`, `calls.go`, `xref.go`, `deps.go`, `annotations.go`, `source.go`, `projectinfo.go`), and "Kotlin tooling" (`lint.go`); `version.go` is intentionally ungrouped
- `internal/client/` — HTTP client; characterization tests lock the wire contract (`client_test.go`)
- `internal/server/` — child-process lifecycle: spawn, ready-line parsing, graceful stop
- `internal/state/` — ServerState repository at `~/.cache/codelens/servers/<hash>.json`
- `internal/settings/` — env-driven config, Java/SDKMAN/Gradle detection, JAR discovery
- `internal/output/` — JSON emit (`PrintRawJSON` preserves server key order) + `IsTTY`
- `internal/render/` — human-readable table renderers (one per command); pure consumers of the response bytes
- `internal/errors/` — typed exit codes (Success=0, ServerError=4, Timeout=5, NotRunning=7, …)
- `test/e2e/` — runs the CLI against a live JVM and diffs `--json` output against committed golden fixtures

### Output: tables vs JSON

Commands render either a human-readable table (default on a TTY) or JSON (default when piped, or with `--json`); `--table` forces a table, and the two flags are mutually exclusive (`internal/cli/mode.go:resolveMode`). The JSON path is the canonical, golden-locked contract and stays byte-identical — `emit` (`internal/cli/common.go`) passes `json.RawMessage` straight to `output.PrintRawJSON`; table renderers in `internal/render/` decode the same bytes into presentation-only structs and never feed back into the JSON path (re-marshaling would reorder keys and break golden parity). A renderer returns `render.ErrFallback` when there's no sensible table (DOT bytes, empty graph), and `emit` falls back to JSON. The e2e goldens always pass `--json`, and unit/e2e runs are non-TTY (so the default is JSON) — that's why the table layer is additive and existing goldens are unaffected. `completeness_test.go` fails if a new analysis command isn't wired to a renderer.

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

Tag-driven via GoReleaser. `/release:release` pushes a `vX.Y.Z` tag → `.github/workflows/release.yaml` builds the server JAR (Gradle) and the Go binaries (darwin/linux × amd64/arm64), bundles the JAR into each archive, publishes a GitHub Release, and pushes a Homebrew formula to `charliek/homebrew-tap` (needs the `HOMEBREW_TAP_TOKEN` secret). A follow-up `sync-version` job then commits `version.txt` and `.claude-plugin/plugin.json` back to `main` from the tag so the committed (installer-facing) versions match the release. Config: `.goreleaser.yaml`. Docs deploy separately via `.github/workflows/docs.yml` (Zensical → GitHub Pages).

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

Three self-contained sample projects live under `test-fixtures/`, each exercising the general primitives against a different framework's bytecode: `sample-ratpack-app` (the original Ratpack app, also the route-reproduction proof surface), `sample-spring-boot-app` (a rich Spring Boot app — controllers/services/repositories, blocking-vs-reactive), and `sample-micronaut-app` (Micronaut + Flyway + Hikari). Dependency versions are pinned so golden output is reproducible.

The Go e2e golden suite (`cli/test/e2e/`) is the most rigorous proof of correctness: for each fixture it spawns a JVM server, exec's the CLI for every documented endpoint, and diffs the `--json` output against committed golden fixtures (after blanking mutable fields like pid/port/timestamps and templating machine-specific paths). `TestE2E`/`TestE2ESpring`/`TestE2EMicronaut` cover the three fixtures with goldens under `testdata/golden/` (and `…/spring/`, `…/micronaut/`). Regenerate after an intentional output change with `UPDATE_GOLDEN=1 go test -run TestE2E ./test/e2e/...`. The suite is gated behind `-run TestE2E` and skips when the server JAR is absent.

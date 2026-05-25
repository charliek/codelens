# Contributing to CodeLens

Thanks for your interest in contributing! CodeLens is a developer tool for
analyzing JVM codebases (Java & Kotlin) — classes, methods, calls,
cross-references, dependencies, and source.

## Getting set up

You need these on your machine:

- **JDK 21+** (the repo pins `java=21.0.9-amzn` via `.sdkmanrc`). SDKMAN is
  recommended; if you have it, `sdk env` in the repo root picks the right JDK
  automatically. Otherwise install any JDK 21 distribution and point
  `JAVA_HOME` at it.
- **[mise](https://mise.jdx.dev/)** to manage the Go + golangci-lint
  versions (`go = "1.24"`, `golangci-lint = "2.10.1"` — see `.mise.toml`).
  After installing mise, run `mise install` in the repo root.

Gradle is included via the wrapper (`./gradlew`); no separate install is
needed.

## Build & test

```bash
# Build the server fat JAR (downloads dependencies on first run)
./gradlew :server:app:shadowJar
# Output: server/app/build/libs/codelens-server-all.jar

# Build the Go CLI
cd cli
go generate ./...   # copy /version.txt into the embed package
go build -o ./bin/codelens ./cmd/codelens

# Run all Kotlin tests
./gradlew test

# Run all Go tests (e2e golden tests skip without -run TestE2E)
go test ./...

# Lint
./gradlew ktlintCheck            # Kotlin
golangci-lint run ./...          # Go (from cli/)

# Auto-format Kotlin
./gradlew ktlintFormat

# Coverage report
./gradlew koverXmlReport
# Output: build/reports/kover/report.xml
```

A quick end-to-end smoke against the bundled sample project:

```bash
cd test-fixtures/sample-spring-boot-app
codelens start
codelens project
codelens stop
```

The golden e2e suite (diffs the CLI's `--json` output against committed
fixtures for every documented endpoint) requires the server JAR:

```bash
./gradlew :server:app:shadowJar
cd cli
go test -v -run TestE2E ./test/e2e/...

# Regenerate the fixtures after an intentional output change:
UPDATE_GOLDEN=1 go test -run TestE2E ./test/e2e/...
```

## Submitting a pull request

1. Fork the repo and create a topic branch.
2. Make your changes. Keep commits small and focused; a PR with one logical
   change per commit is much easier to review than one large blob.
3. Run `./gradlew check` and `cd cli && go test ./...` locally. CI runs
   the same gates on every PR plus the golden e2e suite.
4. Open a pull request describing the *why* as well as the *what*. Link any
   relevant issue.
5. Be patient and friendly; maintainers will review when they can.

## Code style

- **Kotlin**: enforced by ktlint via `org.jlleitschuh.gradle.ktlint` (rules
  pinned to ktlint 1.5.0). Run `./gradlew ktlintFormat` before submitting.
  Wildcard imports are intentionally allowed (see `.editorconfig`) because
  Ktor DSL imports read more naturally that way.
- **Go**: enforced by `golangci-lint` (config in `cli/.golangci.yml`).
  `gofmt` + `goimports` formatting is required.
- **Tests**: characterization tests for the public CLI / wire contract
  live in `cli/internal/client/client_test.go` and the golden e2e suite
  in `cli/test/e2e/`; please don't relax them lightly -- they protect
  against silent regressions in the CLI's output and wire contract.

## Reporting bugs and feature requests

Please use GitHub Issues. For security-sensitive reports, see
[SECURITY.md](SECURITY.md) and do **not** open a public issue.

## Code of Conduct

By participating in this project you agree to abide by the
[Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md).

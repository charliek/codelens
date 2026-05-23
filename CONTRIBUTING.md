# Contributing to CodeLens

Thanks for your interest in contributing! CodeLens is a developer tool for
analyzing Ratpack-based JVM codebases to assist with migration planning.

## Getting set up

You need three things on your machine:

- **JDK 21+** (the repo pins `java=21.0.9-amzn` via `.sdkmanrc`). SDKMAN is
  recommended; if you have it, `sdk env` in the repo root picks the right JDK
  automatically. Otherwise install any JDK 21 distribution and point
  `JAVA_HOME` at it.
- **Python 3.13+** for the CLI.
- **[uv](https://docs.astral.sh/uv/)** as the Python package manager.

Gradle is included via the wrapper (`./gradlew`); no separate install is
needed.

## Build & test

```bash
# Build the server fat JAR (downloads dependencies on first run)
./gradlew :server:app:shadowJar
# Output: server/app/build/libs/codelens-server-all.jar

# Install the CLI in editable mode
cd cli && uv tool install --editable .

# Run all Kotlin tests
./gradlew test

# Run all Python tests
cd cli && uv run pytest

# Lint Kotlin
./gradlew ktlintCheck

# Auto-format Kotlin
./gradlew ktlintFormat

# Coverage report
./gradlew koverXmlReport
# Output: build/reports/kover/report.xml
```

A quick end-to-end smoke test against the bundled sample project:

```bash
cd test-fixtures/sample-ratpack-app
codelens start
codelens project
codelens stop
```

## Submitting a pull request

1. Fork the repo and create a topic branch.
2. Make your changes. Keep commits small and focused; a PR with one logical
   change per commit is much easier to review than one large blob.
3. Run `./gradlew check` and `cd cli && uv run pytest` locally. CI runs the
   same gates on every PR.
4. Open a pull request describing the *why* as well as the *what*. Link any
   relevant issue.
5. Be patient and friendly; maintainers will review when they can.

## Code style

- **Kotlin**: enforced by ktlint via `org.jlleitschuh.gradle.ktlint` (rules
  pinned to ktlint 1.5.0). Run `./gradlew ktlintFormat` before submitting.
  Wildcard imports are intentionally allowed (see `.editorconfig`) because
  Ktor DSL imports read more naturally that way.
- **Python**: enforced by `ruff` and `black` (see `cli/pyproject.toml`).
- **Tests**: characterization tests for the public CLI / wire contract live
  in `cli/tests/test_*_contract.py`; please don't change them lightly --
  they protect future ports against silent regressions.

## Reporting bugs and feature requests

Please use GitHub Issues. For security-sensitive reports, see
[SECURITY.md](SECURITY.md) and do **not** open a public issue.

## Code of Conduct

By participating in this project you agree to abide by the
[Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md).

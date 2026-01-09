# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CodeLens is a developer tool for analyzing Ratpack-based JVM codebases to assist with migration planning. It consists of two components:

1. **Server** (Kotlin/Ktor): Background service that analyzes target project bytecode using ClassGraph and serves results via HTTP REST API
2. **CLI** (Python/Typer): User-facing interface that manages server lifecycle and presents analysis results

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

### CLI (Python)

```bash
cd cli

# Install CLI in editable mode (for development)
uv tool install --editable .

# Run Python tests
uv run pytest

# Run a single test file
uv run pytest tests/test_models.py

# Run a specific test
uv run pytest tests/test_models.py::test_function_name
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

### Service/Repository Pattern

Both server and CLI follow a service/repository pattern:
- **Services** contain business logic (`ServerService`, `ProjectService`, `AnalysisService`)
- **Repositories** handle data persistence (`ServerStateRepository`)
- **Routes/Commands** are thin wrappers delegating to services
- **Container** provides dependency injection for the CLI (`ServiceContainer`)

### Key Entry Points

- Server main: `server/app/src/main/kotlin/codelens/server/Application.kt`
- CLI main: `cli/src/codelens_cli/main.py`

### State Storage

Server state is persisted in `~/.cache/codelens/`:
- `servers/{hash}.json` - Server state files (keyed by SHA256 hash of project path)
- `logs/{hash}.log` - Server log files

## Version Management

Version is defined in `version.txt` at the repository root and read by both Gradle and Python builds.

## Testing

Test fixtures are in `test-fixtures/sample-ratpack-app/` - a minimal Ratpack project for integration testing.

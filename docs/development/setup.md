# Development Setup

This covers building codelens from a checkout. For installing a release, see
[Installation](../getting-started/installation.md).

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 21 | For the server; via SDKMAN (`.sdkmanrc` pins `21.0.9-amzn`) |
| Go | 1.24 | Via [mise](https://mise.jdx.dev/) (`mise install` reads `.mise.toml`) |
| golangci-lint | 2.10.1 | Via mise |
| Gradle | 9.x | Bundled via the wrapper (`./gradlew`) |

```bash
mise install   # installs the pinned Go toolchain + golangci-lint
```

## Build the server

```bash
# Fat JAR with all dependencies
./gradlew :server:app:shadowJar
# Output: server/app/build/libs/codelens-server-all.jar
```

## Build the CLI

The Go module lives in `cli/`. `go generate` embeds `version.txt` into the
binary; run it before building.

```bash
cd cli
go generate ./...
make build      # produces ./bin/codelens
make install    # copies to ~/.local/bin/codelens
```

Running from a checkout, the CLI finds the freshly built JAR by walking up to the
repo root — no configuration needed. See
[Server & JAR Discovery](../concepts/discovery.md).

## Run the server directly

```bash
# Via Gradle
./gradlew :server:app:run --args="--project /path/to/project"

# Via the JAR
java -jar server/app/build/libs/codelens-server-all.jar \
  --project /path/to/project --port 8080 --idle-timeout 30m
```

## Tests

=== "Kotlin (server)"

    ```bash
    ./gradlew test
    ./gradlew test --tests "codelens.server.SomeTest"   # one class
    ```

=== "Go (CLI)"

    ```bash
    cd cli
    go test ./...

    # Golden e2e suite — spawns a live JVM and diffs --json output against
    # committed fixtures (requires the server JAR built):
    go test -v -run TestE2E ./test/e2e/...

    # Regenerate fixtures after an intentional output change:
    UPDATE_GOLDEN=1 go test -run TestE2E ./test/e2e/...
    ```

## Lint

```bash
./gradlew ktlintCheck          # Kotlin
cd cli && golangci-lint run    # Go
```

## Version

`version.txt` at the repo root is the single source of truth. Gradle reads it for
the JAR; the Go build copies it into `cli/internal/version/version.txt` (via
`go generate`) and embeds it. Releases overwrite it from the git tag — see the
release workflow.

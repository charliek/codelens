# Server & JAR Discovery

The `codelens` CLI is a thin client. The actual analysis runs in a Java server
process started from `codelens-server-all.jar`. This page explains how the CLI
locates that JAR, and the three install layouts it supports.

## Install modes

codelens is designed to work in three layouts without configuration:

| Mode | Layout | JAR location |
|------|--------|--------------|
| Dev (in repo) | A checked-out codelens repo | `server/app/build/libs/codelens-server-all.jar` |
| Packaged | Homebrew or a two-file install | `../libexec/` next to the binary, or `~/.codelens/` |
| Override | Any layout | Wherever `CODELENS_SERVER_JAR` points |

## JAR discovery order

When the CLI needs the server JAR, it checks these locations in order and uses
the first that exists:

| Priority | Source | Notes |
|----------|--------|-------|
| 1 | `--server-jar` flag | Lifecycle commands only (`start`, `restart`) |
| 2 | `CODELENS_SERVER_JAR` env var | Explicit override |
| 3 | `$CODELENS_REPO_PATH/server/app/build/libs/codelens-server-all.jar` | When the repo path is set explicitly |
| 4 | Walked-up repo root | Searches upward from the binary and the cwd for a directory containing `gradlew` + `settings.gradle.kts`, then looks for the built JAR |
| 5 | `../libexec/codelens-server-all.jar` | Relative to the resolved binary — the Homebrew / packaged layout |
| 6 | `~/.codelens/codelens-server-all.jar` | Manual install convention |

If none resolve, lifecycle commands fail with a message telling you to set
`CODELENS_SERVER_JAR`, build the JAR, or pass `--server-jar`.

!!! note "Why dev wins over packaged"

    The repo candidates (3–4) come before the packaged ones (5–6) on purpose: if
    you're working inside a codelens checkout, the CLI uses the JAR you just
    built rather than an installed copy.

### Homebrew layout

Homebrew installs the binary to `<prefix>/bin/codelens` (a symlink into the
Cellar) and the JAR to `<prefix>/libexec/codelens-server-all.jar`. The CLI
resolves its own executable path (following symlinks) and looks for the JAR in
the sibling `libexec/` directory, so the packaged install is self-contained.

## Gradle vs JAR mode

The server can run either from the fat JAR (`jar` mode) or via the repo's Gradle
wrapper (`gradle` mode). With the default `--mode auto`, the CLI uses `jar` mode
when a JAR resolves and falls back to `gradle` mode otherwise. Set the mode
explicitly with `--mode` or `CODELENS_SERVER_MODE`.

```bash
codelens start --mode jar     # force the fat JAR
codelens start --mode gradle  # force the Gradle wrapper (repo checkout only)
```

## State and logs

Per-project server state and logs live under `~/.cache/codelens/`:

```text
~/.cache/codelens/
├── servers/{hash}.json   # one state file per project (SHA-256 of the project path)
└── logs/{hash}.log       # the server's stdout/stderr
```

State files are cleaned up when a server stops. To inspect a server that won't
start, tail its log:

```bash
tail -100 ~/.cache/codelens/logs/*.log
```

## Related

- [JDK Resolution](jdk-resolution.md) — how the JVM that runs the JAR is chosen
- [Installation](../getting-started/installation.md)

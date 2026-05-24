# JDK Resolution

codelens uses Java at two independent levels, and resolves each separately:

1. **Server JVM** — the JDK that runs `codelens-server-all.jar` (codelens itself).
2. **Project JVM** — a JDK used to run the *target project's* Gradle when its
   Gradle version can't run on the server JVM.

```text
┌────────────────────────────────────────────────────────────┐
│ codelens CLI                                                 │
│                                                              │
│  Server JVM   →  runs codelens-server-all.jar                │
│                  (JDK 21–25, newest available)               │
│                                                              │
│  Project JVM  →  passed to the server as --project-java-home │
│                  (the target's own JDK, when needed)         │
└────────────────────────────────────────────────────────────┘
```

## Server JVM

The server is built for **Java 21** and is verified through **Java 25**. The CLI
runs it on the **newest installed JDK whose major version is in the range
21–25**, discovered across SDKMAN and Homebrew.

### Resolution order

| Priority | Source | Notes |
|----------|--------|-------|
| 1 | `CODELENS_JAVA_HOME` | Explicit override; used as-is if it has `bin/java` |
| 2 | SDKMAN + Homebrew (in range) | Highest installed JDK with major in **[21, 25]**; SDKMAN preferred on ties |
| 3 | `JAVA_HOME` | Fallback if nothing in range was found |
| 4 | `java` on `PATH` | Last resort |

SDKMAN candidates are read from `~/.sdkman/candidates/java/*`; Homebrew kegs are
checked at `openjdk@21` … `openjdk@25` under the standard prefixes
(`/opt/homebrew`, `/usr/local`, `/home/linuxbrew/.linuxbrew`).

### Floor and ceiling

- **Floor (21)** is the server's build target — the minimum JVM that can launch
  it.
- **Ceiling (25)** is the newest JDK the server stack (Kotlin/Ktor/Netty, the
  Gradle Tooling API, and ClassGraph) is verified on.

The CLI picks the **newest** JDK in range rather than pinning an exact version,
because a server JVM must be **greater than or equal to** the bytecode version of
the project it analyzes. Running on the newest in-range JDK maximizes the set of
target projects codelens can read without rebuilding the server.

### Target newer than the server

If a target project targets a Java version newer than the server JVM, codelens
prints a warning at startup, for example:

```text
warning: server is running Java 21 but /path/to/project targets Java 24;
install a JDK >= 24 (<= 25) via `sdk install java 24...` or
`brew install openjdk@24` so codelens can analyze it.
```

Install an in-range JDK that is at least the target's version and codelens will
prefer it on the next start.

## Project JVM

To resolve a target project's classpath, the server runs the project's Gradle.
That Gradle daemon must run on a JDK the project's Gradle version supports — often
an **older** JVM than the server's. codelens runs it on the project's
**declared** JDK, passed as `--project-java-home`, decoupled from the server JVM.

!!! warning "A declared project JDK is required"

    codelens does not guess the project's JDK. Every analyzed project must
    **declare** one (or you pass `--project-java`). If none is declared — or the
    declared version isn't installed — codelens prints an actionable error and
    stops, rather than running the project's Gradle on the wrong JVM.

### How the project JDK is declared

Declare it with any one of these (checked in order); the first that specifies a
Java version wins:

| Priority | Source | Example |
|----------|--------|---------|
| 1 | `.sdkmanrc` | `java=11.0.28-tem` |
| 2 | `.java-version` | `11.0.28-tem` or `11` |
| 3 | `gradle.properties` | `org.gradle.java.home=/abs/path/to/jdk` |
| 4 | mise | `.mise.toml` (`[tools]` `java = "21"`) or `.tool-versions` (`java temurin-21.0.9`) |

A `.sdkmanrc` is the simplest. See [Target Project Setup](target-project.md).

### How the declared version is resolved

The declared version is located in order: **SDKMAN** (`~/.sdkman/candidates/java`,
exact then major-prefix), **Homebrew** (`openjdk@<major>`), then **mise**
(`~/.local/share/mise/installs/java`). An absolute `org.gradle.java.home` path is
used directly.

If nothing resolves, codelens stops with guidance to install the JDK
(`sdk install java <v>` / `brew install openjdk@<major>` / `mise install java@<v>`)
or to pass `--project-java`:

```bash
# Explicit escape hatch (bypasses declaration/resolution)
codelens start -p /path/to/project \
  --project-java ~/.sdkman/candidates/java/11.0.28-tem
```

Because the daemon runs on the declared JDK, a project on an older Gradle works
even when the server runs a newer JDK — and a server on Java 25 can still read
the project's (older) bytecode via ClassGraph.

## Environment variables and flags

| Variable / flag | Applies to | Purpose |
|-----------------|------------|---------|
| `CODELENS_JAVA_HOME` | Server JVM | Force the JDK that runs the server |
| `JAVA_HOME` | Server JVM | Fallback when nothing in range is found |
| `CODELENS_JAVA_OPTS` | Server JVM | Extra JVM options (e.g. `-Xmx4g`), whitespace-split |
| `--project-java` | Project JVM | Java home for the target project's Gradle |

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| `UnsupportedClassVersionError` at startup | Server JVM older than Java 21 | Install a JDK 21+ (SDKMAN or `brew install openjdk@21`), or set `CODELENS_JAVA_HOME` |
| Warning that the target needs a newer Java | Target bytecode newer than the server JVM | Install an in-range JDK ≥ the target version |
| `no JDK declared for project …` | Project doesn't declare a JDK | Add a `.sdkmanrc` / `.java-version` / mise config, or pass `--project-java` |
| `project … declares Java X but it isn't installed` | Declared JDK missing | `sdk install java <X>`, `brew install openjdk@<major>`, or `mise install java@<X>` |
| Server starts but shows 0 classes | Project not compiled | Build the target first (`./gradlew build -x test`) |

To see what the server logged:

```bash
tail -100 ~/.cache/codelens/logs/*.log
```

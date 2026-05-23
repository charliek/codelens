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
Older Gradle versions can't run on Java 21, so when needed the CLI detects the
project's own JDK and passes it to the server as `--project-java-home`.

### When it kicks in

The CLI only resolves a project JDK when the target's Gradle is too old for the
server JVM:

| Gradle Version | Max Java Version |
|----------------|------------------|
| 7.x | Java 19 |
| 8.0 – 8.4 | Java 20 |
| 8.5+ | Java 21+ |

For Gradle 8.5+ no project JDK is needed — the project builds on the server JVM.

### How the project JDK is detected

The requested version is read from the project, in order:

| Priority | File | Example |
|----------|------|---------|
| 1 | `.sdkmanrc` | `java=11.0.28-tem` |
| 2 | `.java-version` | `11.0.28-tem` or `11` |
| 3 | `gradle.properties` | `org.gradle.java.home=/path/to/java` |

That version is then located in **SDKMAN** (exact match, then a major-version
fallback) or **Homebrew** (`openjdk@<major>`). If the version is requested but
not installed, codelens prints a hint and continues; you can also point at a JDK
explicitly with `--project-java`.

```bash
# Pin the project's JDK explicitly
codelens start -p /path/to/project \
  --project-java ~/.sdkman/candidates/java/11.0.28-tem
```

The most reliable setup is a `.sdkmanrc` in the target project — see
[Target Project Setup](target-project.md).

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
| `Unsupported class file major version …` from Gradle | Target's Gradle too old for the server JVM | Add a `.sdkmanrc` to the project, or pass `--project-java` |
| "could not find Java … in SDKMAN" | Requested project JDK not installed | `sdk install java <version>` (or install the matching `openjdk@<major>`) |
| Server starts but shows 0 classes | Project not compiled | Build the target first (`./gradlew build -x test`) |

To see what the server logged:

```bash
tail -100 ~/.cache/codelens/logs/*.log
```

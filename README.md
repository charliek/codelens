# codelens

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

codelens analyzes JVM codebases (Java and Kotlin). It loads a project's compiled
bytecode and resolved classpath, then answers structural questions over a
command-line interface (and a small local HTTP API) — classes, methods,
annotations, type hierarchies, the calls a method makes (`calls`), everything that
references a type (`xref`), the project dependency graph (`deps`), and source
(including JDK and library source).

**📖 Full documentation: [charliek.github.io/codelens](https://charliek.github.io/codelens/)**

## Goals

- **Accurate structural facts, straight from bytecode.** Answer "what implements
  this", "who references this type", and "what does this method actually call" from
  compiled bytecode and the resolved classpath — relationships that text search and
  source-only tooling miss.
- **Framework-agnostic primitives; framework knowledge in skills.** The tool ships
  general primitives only. Framework-specific analysis (e.g. a Ratpack migration
  assessment) is composed from them in installable agent skills, not baked into the
  binary.
- **Works with the agent you already use.** One CLI any agent can drive, plus skills
  that install into Claude Code, GitHub Copilot, OpenCode, and others.

## How it works

- **Server** (Kotlin/Ktor): runs in the background, scans the target's bytecode with
  ClassGraph, resolves the classpath via the Gradle Tooling API, and serves analysis
  over a local REST API. It shuts down when idle.
- **CLI** (Go): a single static binary that manages the server and formats results.
  It auto-starts the server on first use.

## Quick start (macOS)

### 1. Install the CLI

```bash
brew tap charliek/tap
brew install codelens
codelens version
```

The server runs on a **JDK 21+**, which codelens auto-discovers from SDKMAN or
Homebrew. Install one if you don't have it:

```bash
sdk install java 21.0.9-amzn   # SDKMAN
# or: brew install openjdk@21
```

**Linux (apt)** — on Ubuntu/Pop!_OS 24.04+ (`amd64`, `arm64`):

```bash
# One-time: add the apt-charliek repo
sudo install -d -m 0755 /etc/apt/keyrings
curl -fsSL https://apt.stridelabs.ai/pubkey.gpg | \
  sudo tee /etc/apt/keyrings/apt-charliek.gpg > /dev/null
echo 'deb [signed-by=/etc/apt/keyrings/apt-charliek.gpg] https://apt.stridelabs.ai noble main' | \
  sudo tee /etc/apt/sources.list.d/apt-charliek.list
sudo apt update

sudo apt install codelens openjdk-21-jre-headless
codelens version
```

codelens auto-discovers the server JDK from `/usr/lib/jvm`, SDKMAN, mise, or
Linuxbrew. For a direct `.deb` download (no apt repo), see
[apt-charliek](https://github.com/charliek/apt-charliek#direct-deb-download-no-apt-repo).

### 2. Install the skills

The skills teach your agent to drive the codelens CLI, so install the CLI (step 1)
first. The general route — [`skills`](https://skills.sh) — installs into Claude Code,
GitHub Copilot, OpenCode, and many other agents, auto-detecting the ones you have:

```bash
npx skills add charliek/codelens
```

For Claude Code, a native plugin is also available (it namespaces the skills as
`codelens:<name>`):

```bash
/plugin marketplace add charliek/codelens
/plugin install codelens@codelens
```

### 3. Prepare your project

codelens runs your project's Gradle on the JDK your project **declares** — it does
not guess. Declare one (a `.sdkmanrc` is simplest), then build the project so there is
bytecode to analyze:

```bash
cd ~/work/my-service
echo "java=21.0.9-tem" > .sdkmanrc   # the JDK your project builds with
./gradlew build -x test
```

> [!NOTE]
> This is the **project's** JDK (used for its Gradle daemon), separate from the JDK
> 21+ the codelens server runs on. Without a declared JDK, codelens stops with a clear
> error. See [Target Project Setup](https://charliek.github.io/codelens/concepts/target-project/)
> for the other accepted sources (`.java-version`, `gradle.properties`, mise).

### 4. Boot it up and check

From the project directory — the server auto-starts on the first command:

```bash
codelens classes stats                          # boots the server, scans, prints class counts
codelens classes list --package "com.example.*" # your classes
codelens status                                  # confirm the server is running
```

If `classes stats` returns counts, you're set. Every command supports `--json`. From
here, ask your agent a structural question and let the skills drive the CLI, or work
the [CLI](https://charliek.github.io/codelens/reference/cli/) directly.

## Documentation

| Topic | |
|-------|--|
| Installation (Homebrew, standalone, from source) | <https://charliek.github.io/codelens/getting-started/installation/> |
| Quick start | <https://charliek.github.io/codelens/getting-started/quick-start/> |
| Target project setup (declaring the JDK) | <https://charliek.github.io/codelens/concepts/target-project/> |
| CLI & HTTP API reference | <https://charliek.github.io/codelens/reference/cli/> |
| JDK resolution (server vs. project JVM) | <https://charliek.github.io/codelens/concepts/jdk-resolution/> |
| Framework analysis with skills | <https://charliek.github.io/codelens/concepts/framework-analysis/> |
| Development (build from source) | <https://charliek.github.io/codelens/development/setup/> |

## License

codelens is licensed under the [Apache License, Version 2.0](LICENSE).

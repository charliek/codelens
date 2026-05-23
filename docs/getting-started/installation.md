# Installation

codelens ships as two artifacts that travel together: the `codelens` CLI binary
and the `codelens-server-all.jar` server. The server runs on a **JDK 21+**, which
you provide.

## Homebrew (recommended)

```bash
brew tap charliek/tap
brew install codelens
```

This installs the `codelens` binary and the server JAR. The CLI finds the JAR
automatically (it sits next to the binary — see
[Server & JAR Discovery](../concepts/discovery.md)).

## Provide a JDK 21+

The server needs a JDK 21 or newer at runtime. codelens auto-discovers JDKs from
**SDKMAN** and **Homebrew** and runs the server on the newest it finds in the
supported range (21–25). Install one if you don't have it:

=== "SDKMAN"

    ```bash
    curl -s "https://get.sdkman.io" | bash
    sdk install java 21.0.9-amzn
    ```

=== "Homebrew"

    ```bash
    brew install openjdk@21
    ```

If you prefer to point at a specific JDK, set `CODELENS_JAVA_HOME` (or
`JAVA_HOME`) to a JDK 21+ home. See [JDK Resolution](../concepts/jdk-resolution.md)
for the full resolution order and the floor/ceiling rules.

!!! note "Analyzing a target project may need its own JDK"

    The server JVM runs codelens itself. A target project built with an older
    Gradle may additionally need its own JDK to resolve the classpath; codelens
    auto-discovers that too. See [JDK Resolution](../concepts/jdk-resolution.md).

## Verify

```bash
codelens version
```

## Manual / standalone install

You don't need Homebrew. Put the `codelens` binary on your `PATH` and make the
server JAR discoverable in one of these ways:

| Method | How |
|--------|-----|
| Alongside the binary | Place `codelens-server-all.jar` in `../libexec/` relative to the binary |
| Home directory | Place it at `~/.codelens/codelens-server-all.jar` |
| Environment variable | `export CODELENS_SERVER_JAR=/path/to/codelens-server-all.jar` |

Download both from the [GitHub releases](https://github.com/charliek/codelens/releases).
The full priority order is documented in [Server & JAR Discovery](../concepts/discovery.md).

## From source

See [Development → Setup](../development/setup.md) to build the CLI and server
from a checkout.

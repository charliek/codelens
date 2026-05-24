# Target Project Setup Guide

This document describes the prerequisites and recommended configuration for projects that will be analyzed by CodeLens.

## Required Prerequisites

### 1. Gradle Wrapper

Your project **must** use the Gradle Wrapper. CodeLens uses the Gradle Tooling API to resolve your project's classpath, and it requires the wrapper to be present.

```bash
# Verify your project has these files:
gradlew        # Unix wrapper script
gradlew.bat    # Windows wrapper script
gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.properties
```

If your project doesn't have a wrapper, generate one:

```bash
gradle wrapper
```

### 2. Compiled Project

CodeLens analyzes compiled bytecode, not source files. Your project must be compiled before analysis.

```bash
# Compile without running tests (faster)
./gradlew build -x test

# Or just compile classes
./gradlew classes testClasses
```

**Why this is needed:** CodeLens scans `build/classes/` directories to discover classes in your project. Without compiled bytecode, there's nothing to analyze.

### 3. Gradle Version Compatibility

CodeLens supports Gradle versions 4.x through 9.x. Your project's Gradle version must also be compatible with the JDK you're using to run CodeLens.

Check your current Gradle version:

```bash
./gradlew --version
```

## Required: declare the project's JDK

CodeLens runs your project's Gradle on the JDK your project **declares** — it
does not guess. You must declare a JDK with one of the following (or pass
`--project-java`), otherwise CodeLens stops with an error:

| Source | Example |
|--------|---------|
| `.sdkmanrc` | `java=17.0.9-tem` |
| `.java-version` | `17.0.9-tem` or `17` |
| `gradle.properties` | `org.gradle.java.home=/abs/path/to/jdk` |
| mise | `.mise.toml` (`[tools]` `java = "17"`) or `.tool-versions` (`java temurin-17.0.9`) |

A `.sdkmanrc` is the simplest:

```bash
echo "java=17.0.9-tem" > .sdkmanrc
```

The declared version is resolved from SDKMAN, Homebrew (`openjdk@<major>`), or
mise. If it isn't installed, CodeLens tells you how to install it. See
[JDK Resolution](jdk-resolution.md) for the full order and the server-vs-project
JVM distinction.

To list available JDK versions in SDKMAN: `sdk list java`.

## Recommended Configuration

### Standard Gradle Plugins

Your project should use standard Gradle plugins. CodeLens works best with:

- `java` or `java-library` plugin for Java projects
- `org.jetbrains.kotlin.jvm` plugin for Kotlin projects

These are typically already present in any JVM project.

## What You DON'T Need

### Source JAR Downloads

**You do NOT need to download source JARs manually.** CodeLens automatically fetches source JARs from Maven Central when you request library source code. There's no Gradle task or plugin required for this.

### IDEA Plugin

**You do NOT need the IntelliJ IDEA plugin.** Some tools require `idea` plugin configuration for source attachment, but CodeLens handles source resolution independently.

### Special Dependency Configurations

**You do NOT need special dependency configurations.** CodeLens reads your existing dependency declarations and resolves source JARs on-demand from Maven Central.

## Quick Setup Checklist

- [ ] Gradle wrapper present (`./gradlew --version` works)
- [ ] Project compiled (`./gradlew build -x test`)
- [ ] `.sdkmanrc` file created (recommended for JDK source resolution)

## Troubleshooting

### "Could not resolve classpath"

Ensure your project compiles successfully:

```bash
./gradlew build -x test
```

### "JDK source not found"

1. Verify your JDK installation includes `src.zip`:
   ```bash
   ls $JAVA_HOME/lib/src.zip
   ```

2. Add a `.sdkmanrc` file to help CodeLens detect the correct JDK.

### "Source JAR not found"

Some libraries don't publish source JARs to Maven Central. In these cases, CodeLens will automatically fall back to decompilation using CFR.

### Gradle version incompatibility

If you see Gradle Tooling API errors, ensure your Gradle version is compatible with your JDK:

| Java Version | Minimum Gradle Version |
|--------------|------------------------|
| Java 21      | Gradle 8.5+            |
| Java 17      | Gradle 7.3+            |
| Java 11      | Gradle 5.0+            |
| Java 8       | Gradle 4.x+            |

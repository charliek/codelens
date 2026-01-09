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

CodeLens supports Gradle versions 4.x through 8.x. Your project's Gradle version must also be compatible with the JDK you're using to run CodeLens.

Check your current Gradle version:

```bash
./gradlew --version
```

## Recommended Configuration

### 1. SDKMAN Configuration (Recommended)

For reliable JDK detection, create a `.sdkmanrc` file in your project root. This tells CodeLens which JDK version your project uses.

```bash
# Create .sdkmanrc file
echo "java=17.0.9-tem" > .sdkmanrc
```

Example `.sdkmanrc` content:

```properties
# Enable auto-env to automatically switch JDK when entering directory
java=17.0.9-tem
```

**Why this helps:** CodeLens attempts to detect your project's JDK from multiple sources. The `.sdkmanrc` file provides an unambiguous signal of which JDK should be used, improving reliability of JDK source resolution (e.g., `java.util.HashMap`).

To list available JDK versions in SDKMAN:

```bash
sdk list java
```

### 2. Standard Gradle Plugins

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
| Java 21      | Gradle 8.4+            |
| Java 17      | Gradle 7.3+            |
| Java 11      | Gradle 5.0+            |
| Java 8       | Gradle 4.x+            |

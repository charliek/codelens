# JVM Detection and Compatibility

CodeLens requires Java at two levels:

1. **Server JVM**: Java 21+ to run the CodeLens server itself
2. **Project JVM**: A compatible Java version to run Gradle on the target project

This document explains how CodeLens detects and uses Java at each level, and how to troubleshoot issues.

## Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         CodeLens CLI                                │
│                                                                     │
│  1. Detect Server Java (Java 21+)                                   │
│     └─> Used to run codelens-server-all.jar                         │
│                                                                     │
│  2. Detect Project Java (varies by Gradle version)                  │
│     └─> Passed to server via --project-java-home                    │
│     └─> Used by Gradle Tooling API to resolve classpath             │
└─────────────────────────────────────────────────────────────────────┘
```

## Server JVM Detection

The CodeLens server requires **Java 21 or later**. When starting in JAR mode, the CLI detects Java in this priority order:

| Priority | Source | How to Set |
|----------|--------|------------|
| 1 | `CODELENS_JAVA_HOME` env var | `export CODELENS_JAVA_HOME=/path/to/java21` |
| 2 | CodeLens `.sdkmanrc` | Reads `java=` from CodeLens repo's `.sdkmanrc` |
| 3 | `JAVA_HOME` env var | `export JAVA_HOME=/path/to/java` |
| 4 | System PATH | Uses `java` command from PATH |

### Detection Logic

```text
# Priority 1: Explicit setting
if CODELENS_JAVA_HOME is set:
    use CODELENS_JAVA_HOME

# Priority 2: SDKMAN detection
elif codelens/.sdkmanrc exists:
    version = parse java version from .sdkmanrc
    java_home = find version in ~/.sdkman/candidates/java/
    if found: use java_home

# Priority 3: Environment
elif JAVA_HOME is set:
    use JAVA_HOME

# Priority 4: Fallback
else:
    use "java" from PATH
```

### Common Server JVM Errors

**Error: `UnsupportedClassVersionError`**

This means the Java running the server is too old (< Java 21).

```
Java version mismatch: The codelens server requires Java 21.
```

**Solutions:**
1. Install Java 21: `sdk install java 21.0.9-amzn`
2. Set explicitly: `export CODELENS_JAVA_HOME=~/.sdkman/candidates/java/21.0.9-amzn`
3. Use Gradle mode: `codelens start --mode gradle` (uses project's Gradle wrapper)

## Project JVM Detection

When analyzing a target project, CodeLens needs to run Gradle to resolve the classpath. Older Gradle versions don't support Java 21:

| Gradle Version | Max Java Version |
|----------------|------------------|
| 6.x | Java 13 |
| 7.x | Java 19 |
| 8.0 - 8.4 | Java 20 |
| 8.5+ | Java 21+ |

CodeLens automatically detects if the target project needs an older Java and configures the Gradle Tooling API accordingly.

### Detection Logic

**Step 1: Check if older Java is needed**

```text
# Read gradle/wrapper/gradle-wrapper.properties
gradle_version = parse version from distributionUrl

if gradle_version >= 8.5:
    # Compatible with Java 21, no action needed
    return None
else:
    # Needs older Java, proceed to detection
    ...
```

**Step 2: Detect project's Java version**

Checked in this priority order:

| Priority | File | Format |
|----------|------|--------|
| 1 | `.sdkmanrc` | `java=11.0.28-tem` |
| 2 | `.java-version` | `11.0.28-tem` or `11` |
| 3 | `gradle.properties` | `org.gradle.java.home=/path/to/java` |

**Step 3: Locate the Java installation**

```text
# Try to find in SDKMAN
java_home = ~/.sdkman/candidates/java/{version}

# Or use explicit path from gradle.properties
java_home = value of org.gradle.java.home
```

**Step 4: Pass to server**

```bash
# CLI passes detected Java to server
java -jar codelens-server-all.jar \
    --project /path/to/project \
    --project-java-home ~/.sdkman/candidates/java/11.0.28-tem
```

### Common Project JVM Errors

**Error: `Unsupported class file major version 65`**

This means Gradle is trying to run with Java 21 but the Gradle version doesn't support it.

```
Java version incompatibility: The target project's Gradle version cannot run with Java 21.

Solutions:
  1. Pass --project-java-home to specify a compatible Java installation
  2. Use --classpath-file with a pre-generated classpath file
  3. Upgrade the target project's Gradle to 8.5+
```

**Solutions:**

1. **Add `.sdkmanrc` to target project** (recommended):
   ```bash
   echo "java=11.0.28-tem" > /path/to/project/.sdkmanrc
   ```

2. **Specify Java explicitly**:
   ```bash
   codelens start -p /path/to/project \
       --project-java ~/.sdkman/candidates/java/11.0.28-tem
   ```

3. **Install the required Java**:
   ```bash
   sdk install java 11.0.28-tem
   # Then retry - CodeLens will auto-detect
   codelens start -p /path/to/project
   ```

4. **Use classpath file fallback**:
   ```bash
   # Generate classpath with project's Java
   cd /path/to/project
   ./gradlew writeClasspath  # You need to add this task first

   # Start with classpath file
   codelens start -p /path/to/project \
       --classpath-file ./build/codelens-classpath.txt
   ```

## Fallback Strategy

When Gradle Tooling API fails, CodeLens supports a manual classpath file as fallback:

### 1. Add writeClasspath task to build.gradle

```groovy
// build.gradle (Groovy DSL)
tasks.register('writeClasspath') {
    doLast {
        def cp = configurations.runtimeClasspath.files
            .collect { it.absolutePath }
            .join('\n')
        file('build/codelens-classpath.txt').text = cp
    }
}
```

```kotlin
// build.gradle.kts (Kotlin DSL)
tasks.register("writeClasspath") {
    doLast {
        val cp = configurations.getByName("runtimeClasspath")
            .files.joinToString("\n") { it.absolutePath }
        file("build/codelens-classpath.txt").writeText(cp)
    }
}
```

### 2. Generate the classpath file

```bash
# Use the project's Java version
cd /path/to/project
./gradlew build -x test
./gradlew writeClasspath
```

### 3. Start with classpath file

```bash
codelens start -p /path/to/project \
    --classpath-file ./build/codelens-classpath.txt
```

## Debugging

### Check detected versions

```bash
# Check what Java CodeLens will use for the server
echo $CODELENS_JAVA_HOME
cat /path/to/codelens/.sdkmanrc
echo $JAVA_HOME
which java

# Check target project configuration
cat /path/to/project/.sdkmanrc
cat /path/to/project/.java-version
cat /path/to/project/gradle.properties | grep java
cat /path/to/project/gradle/wrapper/gradle-wrapper.properties
```

### Check server logs

```bash
# Find log file for a project
ls ~/.cache/codelens/logs/

# View recent logs
tail -100 ~/.cache/codelens/logs/*.log

# Search for Java-related messages
grep -E "(Java home|java|Gradle)" ~/.cache/codelens/logs/*.log
```

### Key log messages

**Successful auto-detection:**
```
INFO  AnalysisService - Using Gradle Tooling API resolver
INFO  AnalysisService - Will use project Java home: ~/.sdkman/candidates/java/11.0.28-tem
INFO  GradleProjectResolver - Using Java home for Gradle: ~/.sdkman/candidates/java/11.0.28-tem
INFO  GradleProjectResolver - Resolved 584 classpath entries, 2 project output dirs
```

**Failed detection (needs manual intervention):**
```
WARN  ServerService - Project uses Gradle 7.6 which requires an older Java.
                      Detected version 11.0.28-tem but could not find it in SDKMAN.
                      Install with: sdk install java 11.0.28-tem
```

### Verify SDKMAN installation

```bash
# List installed Java versions
ls ~/.sdkman/candidates/java/

# Check if a specific version exists
ls ~/.sdkman/candidates/java/11.0.28-tem/bin/java

# Install a version
sdk install java 11.0.28-tem
```

## Environment Variables Reference

| Variable | Purpose | Example |
|----------|---------|---------|
| `CODELENS_JAVA_HOME` | Java for running CodeLens server | `~/.sdkman/candidates/java/21.0.9-amzn` |
| `JAVA_HOME` | Fallback for server Java | `/usr/lib/jvm/java-21` |
| `CODELENS_JAVA_OPTS` | JVM options for server | `-Xmx4g` |

## CLI Options Reference

| Option | Purpose | Example |
|--------|---------|---------|
| `--project-java` | Java for target project's Gradle | `--project-java ~/.sdkman/candidates/java/11.0.28-tem` |
| `--classpath-file` | Pre-generated classpath (fallback) | `--classpath-file ./build/classpath.txt` |
| `--mode gradle` | Use Gradle wrapper instead of JAR | `--mode gradle` |

## Quick Troubleshooting Guide

| Symptom | Likely Cause | Solution |
|---------|--------------|----------|
| "UnsupportedClassVersionError" on startup | Server Java too old | Install Java 21+, set `CODELENS_JAVA_HOME` |
| "Unsupported class file major version 65" | Target Gradle too old for Java 21 | Add `.sdkmanrc` to project, or use `--project-java` |
| Server starts but shows 0 project classes | Project not compiled | Run `./gradlew classes` in target project |
| "Could not find Java X in SDKMAN" | Required Java not installed | Run `sdk install java X` |
| Classpath resolution timeout | Large project, slow Gradle | Increase timeout or use `--classpath-file` |

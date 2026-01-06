# Bug 001: Gradle Tooling API Java Version Incompatibility

## Summary

The CodeLens server uses the Gradle Tooling API to automatically resolve the target project's classpath. When the server runs on Java 21, it fails to analyze projects that use older Gradle versions (< 8.5) because those Gradle versions don't support Java 21 bytecode.

## Severity

**High** - Prevents analysis of many real-world projects without manual workarounds.

## Symptoms

When starting the server against a project with an older Gradle version:

```
codelens start -p ~/projects/ratpack-migration/moonracer
```

The server starts but enters ERROR state. The log shows:

```
Caused by: org.codehaus.groovy.GroovyBugError: BUG! exception in phase 'semantic analysis'
in source unit '_BuildScript_' Unsupported class file major version 65
    at org.gradle.groovy.scripts.internal.DefaultScriptCompilationHandler.compileScript(...)
...
Caused by: java.lang.IllegalArgumentException: Unsupported class file major version 65
    at groovyjarjarasm.asm.ClassReader.<init>(ClassReader.java:199)
```

Class file major version 65 = Java 21.

## Root Cause

The Gradle Tooling API works by connecting to the target project's Gradle daemon and executing tasks. When CodeLens runs on Java 21:

1. The Tooling API spawns/connects to Gradle using the current JVM (Java 21)
2. The target project's older Gradle wrapper (e.g., Gradle 7.x) tries to compile build scripts
3. Older Gradle versions use an embedded Groovy with an older ASM library
4. That ASM library doesn't recognize Java 21 bytecode (major version 65)
5. Build script compilation fails before classpath resolution can occur

This is NOT a ClassGraph issue - ClassGraph can analyze bytecode from any Java version. The problem is specifically with the Gradle Tooling API's requirement to execute Gradle in the same JVM.

## Affected Configurations

| Target Project Gradle | CodeLens JVM | Result |
|----------------------|--------------|--------|
| Gradle 8.5+ | Java 21 | Works |
| Gradle 7.x | Java 21 | **Fails** |
| Gradle 6.x | Java 21 | **Fails** |
| Gradle 8.5+ | Java 17 | Works |
| Gradle 7.x | Java 17 | Works |
| Gradle 6.x | Java 17 | Likely works |

## Reproduction Steps

1. Find a project using Gradle < 8.5 and Java 11 (like moonracer):
   ```bash
   cd ~/projects/ratpack-migration/moonracer
   cat .sdkmanrc  # Shows java=11.0.28-tem
   ./gradlew --version  # Shows Gradle 7.x or similar
   ```

2. Ensure CodeLens server is built:
   ```bash
   cd ~/projects/codelens
   ./gradlew :server:app:shadowJar
   ```

3. Start server (using system Java 21):
   ```bash
   java --version  # Confirm Java 21
   codelens start -p ~/projects/ratpack-migration/moonracer
   ```

4. Check status:
   ```bash
   codelens status -p ~/projects/ratpack-migration/moonracer
   # Shows status: ERROR
   ```

5. Check logs:
   ```bash
   cat ~/.cache/codelens/logs/*.log | grep -A5 "Unsupported class file major version"
   ```

## Current Workaround

Use the `--classpath-file` option to bypass the Gradle Tooling API entirely:

### Step 1: Generate classpath file using the project's Java version

```bash
# Switch to the project's Java version
export JAVA_HOME=/Users/charlieknudsen/.sdkman/candidates/java/11.0.28-tem

# Create an init script to generate classpath
cat > /tmp/classpath-init.gradle << 'EOF'
allprojects {
    task writeClasspath {
        doLast {
            def cp = []
            def buildDir = new File(project.projectDir, "build/classes/java/main")
            if (buildDir.exists()) cp.add(buildDir.absolutePath)
            def resourcesDir = new File(project.projectDir, "build/resources/main")
            if (resourcesDir.exists()) cp.add(resourcesDir.absolutePath)
            try {
                configurations.runtimeClasspath.files.each { cp.add(it.absolutePath) }
            } catch (e) {}
            new File(project.projectDir, "build/codelens-classpath.txt").text = cp.join("\n")
            println "Wrote ${cp.size()} classpath entries"
        }
    }
}
EOF

# Navigate to project and generate classpath
cd ~/projects/ratpack-migration/moonracer
./gradlew build -x test  # Ensure project is compiled
./gradlew --init-script /tmp/classpath-init.gradle writeClasspath
```

### Step 2: Start server with classpath file

```bash
# For multi-module projects, copy the main module's classpath to root
cp moonracer-web/build/codelens-classpath.txt build/codelens-classpath.txt

# Start server with classpath file (can use Java 21 now)
java -jar /path/to/codelens-server-all.jar \
  --project ~/projects/ratpack-migration/moonracer \
  --classpath-file ~/projects/ratpack-migration/moonracer/build/codelens-classpath.txt \
  --port 8085
```

## Proposed Solutions

### Option A: Use Project's Java for Gradle (Recommended)

Modify the Gradle Tooling API integration to detect and use the target project's Java version:

1. Check for `.sdkmanrc`, `.java-version`, or `gradle.properties` in target project
2. Resolve the appropriate JAVA_HOME
3. Configure the Tooling API connection to use that JVM:
   ```kotlin
   connector.useInstallation(File(projectGradleHome))
   // Set JVM args to use project's Java
   val javaHome = detectProjectJavaHome(projectDir)
   connection.newBuild()
       .setJavaHome(File(javaHome))
       .forTasks("dependencies")
       .run()
   ```

**Pros:**
- Transparent to users
- Works with any project configuration
- No manual steps required

**Cons:**
- Requires multiple JDKs installed
- More complex JDK detection logic
- May need sdkman/asdf integration

### Option B: Automatic Classpath File Generation

When Gradle Tooling API fails, automatically fall back to generating a classpath file:

1. Detect failure with "Unsupported class file major version" error
2. Prompt user or automatically attempt to run Gradle with detected project Java
3. Generate classpath file as workaround
4. Retry scanning with classpath file

**Pros:**
- Graceful degradation
- Works when Option A isn't possible

**Cons:**
- Requires shelling out to Gradle
- May require user interaction for Java selection

### Option C: Lower CodeLens JVM Requirement

Build and run CodeLens with Java 17 instead of Java 21:

**Pros:**
- Simple change
- Compatible with more projects

**Cons:**
- Loses Java 21 features
- Still won't work with very old Gradle versions (< 6.7)
- Not a long-term solution

### Option D: Improve CLI Classpath Workflow

Make the classpath file workflow first-class in the CLI:

```bash
# New command to generate classpath file
codelens classpath generate -p ~/projects/moonracer

# Automatically detects project Java, runs Gradle, generates file
# Then starts server with that file
```

**Pros:**
- Explicit user control
- Works reliably
- Good for CI/CD integration

**Cons:**
- Extra manual step
- Users need to know about it

## Recommended Approach

Implement in phases:

1. **Phase 1 (Quick Win)**: Improve error message when Gradle Tooling API fails due to Java version. Include instructions for the classpath file workaround.

2. **Phase 2**: Implement Option D - Add `codelens classpath generate` command that handles Java version detection and classpath file generation.

3. **Phase 3**: Implement Option A - Automatic project Java detection and Tooling API configuration.

## Related Files

- `server/gradle-resolver/src/main/kotlin/codelens/gradle/GradleProjectResolver.kt` - Tooling API integration
- `server/gradle-resolver/src/main/kotlin/codelens/gradle/ClasspathFileResolver.kt` - Classpath file fallback
- `server/app/src/main/kotlin/codelens/server/services/AnalysisService.kt` - Resolver selection

## References

- [Gradle Compatibility Matrix](https://docs.gradle.org/current/userguide/compatibility.html)
- [Gradle Tooling API Documentation](https://docs.gradle.org/current/userguide/third_party_integration.html)
- Java class file version mapping: 65=Java 21, 61=Java 17, 55=Java 11

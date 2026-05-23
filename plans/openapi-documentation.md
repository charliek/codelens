# Plan: Add OpenAPI Documentation to CodeLens Server

## Overview

Upgrade Kotlin and Ktor to enable official OpenAPI spec generation with SwaggerUI. This provides auto-generated, always-in-sync API documentation served at `/docs`.

**Current State:**
- Kotlin 2.0.21
- Ktor 3.0.2
- No OpenAPI support

**Target State:**
- Kotlin 2.2.20
- Ktor 3.3.3
- OpenAPI spec auto-generated from routes
- SwaggerUI served at `/docs`

---

## Step 1: Upgrade Kotlin and Ktor Versions

**File:** `gradle/libs.versions.toml`

**Current:**
```toml
[versions]
kotlin = "2.0.21"
ktor = "3.0.2"
```

**Change to:**
```toml
[versions]
kotlin = "2.2.20"
ktor = "3.3.3"
```

**Verification:**
```bash
./gradlew clean build
./gradlew test
```

**Potential Issues:**
- Kotlin 2.2 deprecates `kotlinOptions` DSL (see Step 2)
- If tests fail, check for synthetic property usage or interface default method conflicts

---

## Step 2: Update Kotlin Compiler Options Syntax

Kotlin 2.2 deprecates `kotlinOptions` in favor of `compilerOptions`. Update the root build file.

**File:** `build.gradle.kts` (root)

**Current:**
```kotlin
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "21"
            freeCompilerArgs = listOf("-Xjsr305=strict")
        }
    }
}
```

**Change to:**
```kotlin
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }
}
```

**Verification:**
```bash
./gradlew build
```

---

## Step 3: Add Ktor Gradle Plugin

The OpenAPI generation requires the Ktor Gradle plugin.

**File:** `gradle/libs.versions.toml`

Add to `[plugins]` section:
```toml
ktor = { id = "io.ktor.plugin", version.ref = "ktor" }
```

**File:** `build.gradle.kts` (root)

Add plugin (apply false at root level):
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
}
```

---

## Step 4: Configure OpenAPI in Server App Module

**File:** `server/app/build.gradle.kts`

**Current:**
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    application
}
```

**Change to:**
```kotlin
import io.ktor.plugin.features.OpenApiPreview

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    alias(libs.plugins.ktor)
    application
}

// ... existing configuration ...

ktor {
    @OptIn(OpenApiPreview::class)
    openApi {
        title = "CodeLens API"
        version = project.version.toString()
        summary = "API for CodeLens bytecode analysis server"
        description = """
            CodeLens server provides bytecode analysis for JVM codebases.
            Use these endpoints to query classes, methods, dependencies, and annotations.
        """.trimIndent()
        contact = "https://github.com/charliek/codelens/issues"
        target = project.layout.buildDirectory.file("openapi/codelens-api.json")
    }
}
```

**Add Dependencies** (in the same file, add to dependencies block):
```kotlin
dependencies {
    // ... existing dependencies ...

    // OpenAPI/Swagger dependencies
    implementation(libs.ktor.server.openapi)
    implementation(libs.ktor.server.swagger)
}
```

**File:** `gradle/libs.versions.toml`

Add to `[libraries]` section:
```toml
ktor-server-openapi = { module = "io.ktor:ktor-server-openapi", version.ref = "ktor" }
ktor-server-swagger = { module = "io.ktor:ktor-server-swagger", version.ref = "ktor" }
```

**Verification:**
```bash
./gradlew :server:app:buildOpenApi
# Check output: server/app/build/openapi/codelens-api.json
```

---

## Step 5: Add SwaggerUI Route

**File:** `server/app/src/main/kotlin/codelens/server/Application.kt`

**Add import:**
```kotlin
import io.ktor.server.plugins.openapi.*
import io.ktor.server.plugins.swagger.*
```

**Update the routing block in `configureServer()`:**

**Current:**
```kotlin
routing {
    adminRoutes(analysisService, activityTracker, config)
    projectRoutes(analysisService)
    analysisRoutes(analysisService)
    ktlintRoutes(ktlintService)
}
```

**Change to:**
```kotlin
routing {
    // OpenAPI documentation
    swaggerUI(path = "docs", swaggerFile = "openapi/codelens-api.json")

    // API routes
    adminRoutes(analysisService, activityTracker, config)
    projectRoutes(analysisService)
    analysisRoutes(analysisService)
    ktlintRoutes(ktlintService)
}
```

---

## Step 6: Include OpenAPI Spec in JAR

The generated OpenAPI spec needs to be included in the fat JAR.

**File:** `server/app/build.gradle.kts`

Add task dependency to include generated spec:
```kotlin
tasks.shadowJar {
    archiveBaseName.set("codelens-server")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()

    // Include generated OpenAPI spec
    from(layout.buildDirectory.dir("openapi")) {
        into("openapi")
    }
    dependsOn("buildOpenApi")
}
```

**Verification:**
```bash
./gradlew :server:app:shadowJar
# Verify spec is in JAR:
unzip -l server/app/build/libs/codelens-server-all.jar | grep openapi
```

---

## Step 7: Final Verification

### Build and Test
```bash
./gradlew clean build
./gradlew test
```

### Start Server and Verify Docs
```bash
# Start the server
./gradlew :server:app:run --args="--project /path/to/test/project"

# In another terminal, access SwaggerUI
curl http://localhost:8080/docs
# Or open in browser: http://localhost:8080/docs
```

### Verify OpenAPI Spec
```bash
# Check the generated spec
cat server/app/build/openapi/codelens-api.json | jq '.paths | keys'
```

---

## Files Summary

| File | Action |
|------|--------|
| `gradle/libs.versions.toml` | Update kotlin/ktor versions, add plugin and dependencies |
| `build.gradle.kts` (root) | Update compiler options syntax, add ktor plugin |
| `server/app/build.gradle.kts` | Add ktor plugin, configure openApi extension, update shadowJar |
| `server/app/src/main/kotlin/codelens/server/Application.kt` | Add swaggerUI route |

---

## Rollback Plan

If the upgrade causes issues:

1. Revert `gradle/libs.versions.toml` to original versions
2. Revert `build.gradle.kts` to original `kotlinOptions` syntax
3. Remove OpenAPI-related changes from `server/app/build.gradle.kts`
4. Remove swaggerUI route from `Application.kt`
5. Run `./gradlew clean build` to verify rollback

---

## Alternative: Use ktor-openapi-tools (No Upgrade Required)

If the Kotlin/Ktor upgrade proves problematic, the third-party [ktor-openapi-tools](https://github.com/SMILEY4/ktor-openapi-tools) library works with current versions (Kotlin 2.0.21, Ktor 3.0.2).

**Trade-offs:**
- Requires adding DSL annotations to each route
- More manual work but no version upgrades needed
- Mature and well-maintained

---

## References

- [Ktor OpenAPI Spec Generation](https://ktor.io/docs/openapi-spec-generation.html)
- [Kotlin 2.2 Compatibility Guide](https://kotlinlang.org/docs/compatibility-guide-22.html)
- [Ktor 3.3.0 What's New](https://ktor.io/docs/whats-new-330.html)
- [ktor-openapi-tools GitHub](https://github.com/SMILEY4/ktor-openapi-tools)

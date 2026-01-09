# Phase 2C.5: Library Source Code Resolution

**Status**: In Progress (January 2026)
**Prerequisite**: Phase 2C complete ✓
**Target**: Enable LLMs to retrieve source code for library and JDK classes
**Module**: `server:source-resolver` (new)

---

## Overview

This feature extends the existing source code retrieval capability (Feature 5, Phase 2B) to include library dependencies and JDK classes. This is critical for LLM-assisted development where understanding framework implementations (Spring Boot, Micronaut, Ratpack, etc.) is essential.

**Problem Statement**: LLMs often need to understand how library APIs work internally, but they only have access to project source code. This limits their ability to:
- Understand framework behavior when suggesting implementations
- Debug issues involving library code
- Make informed decisions about library usage patterns

**Solution**: Retrieve source code from:
1. Library source JARs (downloaded from Maven Central if needed)
2. JDK `src.zip` (bundled with JDK installations)
3. Decompiled bytecode (fallback when source isn't available)

---

## Success Criteria

- [ ] Can retrieve source for library classes from source JARs
- [ ] Can retrieve source for JDK classes from src.zip
- [ ] Falls back to decompilation when source JAR unavailable
- [ ] Provides LLM-friendly output formats (stub, signatures, javadoc)
- [ ] Supports Kotlin-style stub generation

---

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Artifact coordinates | Capture via Gradle init script | Already iterating over `resolvedArtifacts`, just need to extract `moduleVersion.id` |
| Decompiler | CFR (Class File Reader) | Pure Java, well-maintained, excellent Kotlin support, Apache 2.0 license |
| Caching | Disk cache in `~/.cache/codelens/sources/` | Follows existing convention, source JARs are immutable |
| API design | Extend existing `/api/v1/source/{fqn}` | Consistent API, no client changes needed |
| JDK sources | Extract from `$JAVA_HOME/lib/src.zip` | Standard location, ships with most JDKs |

---

## New Module: `server:source-resolver`

A new Gradle module to isolate the decompiler dependency and library source resolution logic.

### Package Structure

```
server/source-resolver/
  src/main/kotlin/codelens/source/
    model/
      MavenCoordinates.kt       # groupId:artifactId:version data class
      LibrarySourceInfo.kt      # Extended source info with origin metadata
    cache/
      SourceCache.kt            # Disk cache management
    resolver/
      LibrarySourceResolver.kt  # Main orchestrator
      MavenCentralClient.kt     # HTTP client for source JAR downloads
      SourceJarExtractor.kt     # Extract .java from JARs
      JdkSourceResolver.kt      # JDK src.zip extraction
      Decompiler.kt             # CFR wrapper
    format/
      StubGenerator.kt          # Generate source stubs from bytecode
      JavadocExtractor.kt       # Extract doc comments from source
```

### Dependencies

- `org.benf:cfr:0.152` - CFR decompiler
- `com.squareup.okhttp3:okhttp:4.12.0` - HTTP client for Maven Central

---

## Implementation Phases

### Phase 1: Capture Artifact Coordinates (Complete)

Modified `GradleProjectResolver.kt` to capture Maven coordinates during classpath resolution.

**Changes**:
- Added `MavenCoordinates` and `ArtifactMapping` data classes
- Extended `ResolvedClasspath` with `artifactMappings` field
- Modified Gradle init script to capture `artifact.moduleVersion.id`
- Added `# ARTIFACT_MAPPINGS` section to output file

### Phase 2: Store JAR Path in ClassInfo (Complete)

Added `jarPath` field to `ClassInfo` to track which JAR contains each class.

**Changes**:
- Added `jarPath: String?` field to `ClassInfo` in `AnalysisModels.kt`
- Capture `cgClass.classpathElementFile?.absolutePath` in `ClassGraphProviderImpl`

### Phase 3: Create source-resolver Module (In Progress)

Create the new Gradle module structure.

### Phase 4: Implement Core Components

1. **SourceCache**: Disk cache for source JARs and decompiled sources
2. **MavenCentralClient**: Download source JARs from Maven Central
3. **SourceJarExtractor**: Extract .java files from source JARs
4. **Decompiler**: CFR wrapper for bytecode decompilation
5. **JdkSourceResolver**: Extract from JDK src.zip
6. **LibrarySourceResolver**: Orchestrate resolution with fallback

### Phase 5: Extend Source Models

Add `SourceOrigin` enum and extend `SourceInfo` with metadata fields.

### Phase 6: Integrate with AnalysisService

Wire `LibrarySourceResolver` into `AnalysisService.getSource()`.

### Phase 7: Update SourceRoutes

Add query parameters: `allowDecompilation`, `forceRefresh`, `format`, `visibility`, `lang`.

### Phase 8: Implement StubGenerator

Generate source stubs from ClassInfo (works without source code).

### Phase 9: Implement JavadocExtractor

Extract signatures + doc comments from source code.

---

## API Design

### Query Parameters

```
GET /api/v1/source/{fqn}?format=full           # Full source (default)
GET /api/v1/source/{fqn}?format=stub           # Stub with empty bodies
GET /api/v1/source/{fqn}?format=signatures     # Signatures only (minimal)
GET /api/v1/source/{fqn}?format=javadoc        # Signatures + doc comments

GET /api/v1/source/{fqn}?visibility=public     # Public members only
GET /api/v1/source/{fqn}?format=stub&lang=kotlin  # Kotlin-style stub
GET /api/v1/source/{fqn}?allowDecompilation=false # Only original source
```

### Response Model Extensions

```kotlin
@Serializable
enum class SourceOrigin {
    PROJECT_SOURCE,   // From project source roots
    SOURCE_JAR,       // From library -sources.jar
    DECOMPILED,       // From bytecode decompilation
    JDK_SOURCE        // From JDK src.zip
}

@Serializable
enum class SourceFormat {
    FULL,        // Complete source
    STUB,        // Stub with placeholder bodies
    SIGNATURES,  // Just declarations
    JAVADOC      // Signatures + doc comments
}

@Serializable
data class SourceInfo(
    // ... existing fields ...
    val sourceOrigin: SourceOrigin? = null,
    val mavenCoordinates: String? = null,
    val isDecompiled: Boolean = false,
    val format: SourceFormat = SourceFormat.FULL
)
```

---

## CLI Commands

```bash
# Full source (default)
codelens source com.example.MyClass

# Stub generation (from bytecode, no source needed)
codelens source com.google.guava.ImmutableList --stub
codelens source com.google.guava.ImmutableList --stub --kotlin

# Signatures only (minimal tokens)
codelens source io.ktor.server.application.Application --signatures

# With Javadoc (requires source)
codelens source java.util.HashMap --javadoc

# Filter by visibility
codelens source org.springframework.boot.SpringApplication --public-only

# Library source examples
codelens source com.google.common.collect.ImmutableList  # From source JAR
codelens source java.util.HashMap                        # From JDK src.zip
codelens source some.library.WithoutSource               # Decompiled fallback
```

---

## LLM-Friendly Output Formats

| Format | Description | Source Required? | Token Usage |
|--------|-------------|------------------|-------------|
| `full` | Complete source code (default) | Yes | High |
| `stub` | Signatures with `{ ... }` bodies | No (bytecode) | Low |
| `signatures` | Just method/field declarations | No (bytecode) | Very low |
| `javadoc` | Signatures + doc comments only | Yes | Medium |

### Stub Generation Examples

**Java stub:**
```java
package com.google.common.collect;

public abstract class ImmutableList<E> extends ImmutableCollection<E>
    implements List<E>, RandomAccess {

    public static <E> ImmutableList<E> of() { /* ... */ }
    public static <E> ImmutableList<E> copyOf(Collection<? extends E> elements) { /* ... */ }
    public abstract E get(int index);
    public abstract int size();
}
```

**Kotlin stub:**
```kotlin
package com.google.common.collect

abstract class ImmutableList<E> : ImmutableCollection<E>(), List<E>, RandomAccess {

    companion object {
        @JvmStatic fun <E> of(): ImmutableList<E> = TODO()
        @JvmStatic fun <E> copyOf(elements: Collection<E>): ImmutableList<E> = TODO()
    }

    abstract override fun get(index: Int): E
    abstract override val size: Int
}
```

---

## Cache Structure

```
~/.cache/codelens/sources/
  maven/{group}/{artifact}/{version}/
    {artifact}-{version}-sources.jar
  decompiled/{jarHash}/
    com/example/SomeClass.java
  jdk/{version}/
    java/lang/String.java
```

---

## Resolution Flow

```
1. Request: GET /api/v1/source/com.google.common.collect.ImmutableList

2. Lookup ClassInfo:
   - source: LIBRARY
   - jarPath: /home/user/.gradle/caches/.../guava-32.1.3-jre.jar

3. Resolution Strategy:
   a. If JDK class → JdkSourceResolver
   b. If LIBRARY class:
      i.  Lookup coordinates from artifactMappings
      ii. Check cache for source JAR
      iii. If not cached, download from Maven Central
      iv. Extract .java from source JAR
      v.  If source JAR unavailable and allowDecompilation:
          - Check cache for decompiled source
          - If not cached, decompile with CFR

4. Apply format transformation if requested

5. Return SourceInfo with metadata
```

---

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Maven Central rate limiting | Exponential backoff, aggressive caching |
| Large source JARs | Stream extraction, don't load entire JAR in memory |
| CFR decompilation failures | Catch exceptions, return clear error |
| src.zip missing in minimal JDK | Fall back to decompilation |
| Network unavailable | Return clear error, cached sources still work |

---

## Files Modified/Created

### New Files
- `server/source-resolver/build.gradle.kts`
- `server/source-resolver/src/main/kotlin/codelens/source/model/MavenCoordinates.kt`
- `server/source-resolver/src/main/kotlin/codelens/source/cache/SourceCache.kt`
- `server/source-resolver/src/main/kotlin/codelens/source/resolver/*.kt`
- `server/source-resolver/src/main/kotlin/codelens/source/format/*.kt`

### Modified Files
- `settings.gradle.kts` - Add source-resolver module
- `gradle/libs.versions.toml` - Add CFR and OkHttp dependencies
- `server/gradle-resolver/.../ClasspathResolver.kt` - Add MavenCoordinates, ArtifactMapping
- `server/gradle-resolver/.../GradleProjectResolver.kt` - Capture coordinates in init script
- `server/core/.../AnalysisModels.kt` - Add jarPath to ClassInfo
- `server/core/.../source/SourceModels.kt` - Add SourceOrigin, extend SourceInfo
- `server/classgraph/.../ClassGraphProviderImpl.kt` - Capture jarPath
- `server/app/.../services/AnalysisService.kt` - Integrate LibrarySourceResolver
- `server/app/.../routes/SourceRoutes.kt` - Add query params

---

## Testing

### Unit Tests
1. `MavenCoordinates` - parsing, path generation, source JAR naming
2. `SourceJarExtractor` - inner class path conversion, ZIP extraction
3. `Decompiler` - simple class decompilation, error handling
4. `JdkSourceResolver` - src.zip detection, extraction
5. `SourceCache` - cache hit/miss, file management
6. `StubGenerator` - Java and Kotlin stub generation

### Integration Tests
1. End-to-end flow with test-fixtures project
2. Request source for a known library class (e.g., Guava's `ImmutableList`)
3. Request source for JDK class (`java.lang.String`)
4. Decompilation fallback when source JAR unavailable
5. Stub generation for various class types

### Manual Testing
```bash
# Start server
./gradlew :server:app:run --args="--project /path/to/project"

# Test library source
curl localhost:8080/api/v1/source/com.google.common.collect.ImmutableList

# Test JDK source
curl localhost:8080/api/v1/source/java.util.HashMap

# Test stub generation
curl "localhost:8080/api/v1/source/com.google.common.collect.ImmutableList?format=stub&lang=kotlin"
```

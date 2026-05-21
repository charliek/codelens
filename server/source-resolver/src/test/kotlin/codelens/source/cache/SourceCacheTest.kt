package codelens.source.cache

import codelens.core.model.MavenCoordinates
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceCacheTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var cache: SourceCache

    @BeforeEach
    fun setUp() {
        cache = SourceCache(tempDir)
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ========== Source JAR Cache Tests ==========

    @Test
    fun `putSourceJar stores JAR and getSourceJar retrieves it`() {
        val coords = MavenCoordinates("com.example", "lib", "1.0.0")
        val jarBytes = "PK\u0003\u0004test jar content".toByteArray()

        val cachedFile = cache.putSourceJar(coords, jarBytes)

        assertTrue(cachedFile.exists())
        assertEquals(jarBytes.size, cachedFile.length().toInt())

        val retrieved = cache.getSourceJar(coords)
        assertNotNull(retrieved)
        assertTrue(retrieved.exists())
        assertEquals(cachedFile.absolutePath, retrieved.absolutePath)
    }

    @Test
    fun `getSourceJar returns null for uncached coordinates`() {
        val coords = MavenCoordinates("com.example", "nonexistent", "1.0.0")

        val result = cache.getSourceJar(coords)

        assertNull(result)
    }

    @Test
    fun `hasSourceJar returns true for cached JAR`() {
        val coords = MavenCoordinates("com.example", "lib", "1.0.0")
        cache.putSourceJar(coords, "PK\u0003\u0004content".toByteArray())

        assertTrue(cache.hasSourceJar(coords))
    }

    @Test
    fun `hasSourceJar returns false for uncached JAR`() {
        val coords = MavenCoordinates("com.example", "lib", "1.0.0")

        assertFalse(cache.hasSourceJar(coords))
    }

    @Test
    fun `clearSourceJar removes cached JAR`() {
        val coords = MavenCoordinates("com.example", "lib", "1.0.0")
        cache.putSourceJar(coords, "PK\u0003\u0004content".toByteArray())

        assertTrue(cache.hasSourceJar(coords))

        cache.clearSourceJar(coords)

        assertFalse(cache.hasSourceJar(coords))
    }

    // ========== Decompiled Source Cache Tests ==========

    @Test
    fun `putDecompiledSource stores and getDecompiledSource retrieves`() {
        val jarPath = "/path/to/lib.jar"
        val className = "com.example.MyClass"
        val source = "public class MyClass { }"

        cache.putDecompiledSource(jarPath, className, source)

        val retrieved = cache.getDecompiledSource(jarPath, className)
        assertEquals(source, retrieved)
    }

    @Test
    fun `getDecompiledSource returns null for uncached class`() {
        val result = cache.getDecompiledSource("/path/to/lib.jar", "com.example.Missing")

        assertNull(result)
    }

    @Test
    fun `hasDecompiledSource returns correct values`() {
        val jarPath = "/path/to/lib.jar"
        val className = "com.example.MyClass"

        assertFalse(cache.hasDecompiledSource(jarPath, className))

        cache.putDecompiledSource(jarPath, className, "source")

        assertTrue(cache.hasDecompiledSource(jarPath, className))
    }

    // ========== JDK Source Cache Tests ==========

    @Test
    fun `putJdkSource stores and getJdkSource retrieves`() {
        val jdkVersion = "17.0.1"
        val className = "java.util.HashMap"
        val source = "public class HashMap<K,V> { }"

        cache.putJdkSource(jdkVersion, className, source)

        val retrieved = cache.getJdkSource(jdkVersion, className)
        assertEquals(source, retrieved)
    }

    @Test
    fun `getJdkSource returns null for uncached class`() {
        val result = cache.getJdkSource("17.0.1", "java.util.Missing")

        assertNull(result)
    }

    @Test
    fun `hasJdkSource returns correct values`() {
        val jdkVersion = "17.0.1"
        val className = "java.lang.String"

        assertFalse(cache.hasJdkSource(jdkVersion, className))

        cache.putJdkSource(jdkVersion, className, "source")

        assertTrue(cache.hasJdkSource(jdkVersion, className))
    }

    // ========== Security Tests - Path Traversal Prevention ==========

    @Test
    fun `putDecompiledSource rejects class name with path traversal`() {
        val jarPath = "/path/to/lib.jar"
        val maliciousClassName = "com.example..MyClass"

        assertThrows<IllegalArgumentException> {
            cache.putDecompiledSource(jarPath, maliciousClassName, "source")
        }
    }

    @Test
    fun `putDecompiledSource rejects class name with double dots`() {
        val jarPath = "/path/to/lib.jar"
        val maliciousClassName = "../../etc/passwd"

        assertThrows<IllegalArgumentException> {
            cache.putDecompiledSource(jarPath, maliciousClassName, "source")
        }
    }

    @Test
    fun `putDecompiledSource rejects class name with invalid characters`() {
        val jarPath = "/path/to/lib.jar"
        val maliciousClassName = "com/example/MyClass" // Contains forward slash

        assertThrows<IllegalArgumentException> {
            cache.putDecompiledSource(jarPath, maliciousClassName, "source")
        }
    }

    @Test
    fun `putJdkSource rejects class name with path traversal`() {
        val maliciousClassName = "java.lang..String"

        assertThrows<IllegalArgumentException> {
            cache.putJdkSource("17", maliciousClassName, "source")
        }
    }

    @Test
    fun `valid class names with dollar signs are accepted`() {
        val jarPath = "/path/to/lib.jar"
        val innerClassName = "com.example.Outer\$Inner"

        // Should not throw
        cache.putDecompiledSource(jarPath, innerClassName, "source")

        val retrieved = cache.getDecompiledSource(jarPath, innerClassName)
        assertEquals("source", retrieved)
    }

    // ========== Cache Management Tests ==========

    @Test
    fun `clearAll removes all cached data`() {
        val coords = MavenCoordinates("com.example", "lib", "1.0.0")
        cache.putSourceJar(coords, "PK\u0003\u0004content".toByteArray())
        cache.putDecompiledSource("/path/lib.jar", "com.example.Class", "source")
        cache.putJdkSource("17", "java.lang.String", "source")

        assertTrue(cache.hasSourceJar(coords))

        cache.clearAll()

        assertFalse(cache.hasSourceJar(coords))
        assertNull(cache.getDecompiledSource("/path/lib.jar", "com.example.Class"))
        assertNull(cache.getJdkSource("17", "java.lang.String"))
    }

    @Test
    fun `cacheSize returns total size of cached files`() {
        assertEquals(0L, cache.cacheSize())

        cache.putDecompiledSource("/path/lib.jar", "com.example.Class", "x".repeat(1000))

        assertTrue(cache.cacheSize() >= 1000L)
    }

    @Test
    fun `stats returns correct statistics`() {
        val coords = MavenCoordinates("com.example", "lib", "1.0.0")
        cache.putSourceJar(coords, "PK\u0003\u0004content".toByteArray())
        cache.putDecompiledSource("/path/lib.jar", "com.example.Class", "source")
        cache.putJdkSource("17", "java.lang.String", "source")

        val stats = cache.stats()

        assertEquals(1, stats.sourceJarCount)
        assertEquals(1, stats.decompiledCount)
        assertEquals(1, stats.jdkCount)
        assertEquals(3, stats.totalCount)
        assertTrue(stats.totalSize > 0)
    }
}

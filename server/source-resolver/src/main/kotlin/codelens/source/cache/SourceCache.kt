package codelens.source.cache

import codelens.core.model.MavenCoordinates
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest

/**
 * Disk cache for source JARs, decompiled sources, and JDK sources.
 *
 * Cache structure:
 * ```
 * ~/.cache/codelens/sources/
 *   maven/{group}/{artifact}/{version}/
 *     {artifact}-{version}-sources.jar
 *   decompiled/{jarHash}/
 *     com/example/SomeClass.java
 *   jdk/{version}/
 *     java/lang/String.java
 * ```
 */
class SourceCache(
    private val cacheDir: File = File(System.getProperty("user.home"), ".cache/codelens/sources"),
) {
    private val logger = LoggerFactory.getLogger(SourceCache::class.java)

    init {
        cacheDir.mkdirs()
    }

    // ========== Source JAR Cache ==========

    /**
     * Gets the cached source JAR for the given coordinates.
     * Returns null if not cached.
     */
    fun getSourceJar(coords: MavenCoordinates): File? {
        val jarFile = sourceJarPath(coords)
        return if (jarFile.exists()) {
            logger.debug("Cache hit for source JAR: {}", coords.toGradleNotation())
            jarFile
        } else {
            logger.debug("Cache miss for source JAR: {}", coords.toGradleNotation())
            null
        }
    }

    /**
     * Stores a source JAR in the cache.
     * Returns the cached file path.
     */
    fun putSourceJar(
        coords: MavenCoordinates,
        jarBytes: ByteArray,
    ): File {
        val jarFile = sourceJarPath(coords)
        jarFile.parentFile.mkdirs()
        jarFile.writeBytes(jarBytes)
        logger.info("Cached source JAR: {} ({} bytes)", coords.toGradleNotation(), jarBytes.size)
        return jarFile
    }

    /**
     * Checks if a source JAR is cached.
     */
    fun hasSourceJar(coords: MavenCoordinates): Boolean = sourceJarPath(coords).exists()

    private fun sourceJarPath(coords: MavenCoordinates): File {
        val groupPath = coords.groupId.replace('.', File.separatorChar)
        return File(cacheDir, "maven/$groupPath/${coords.artifactId}/${coords.version}/${coords.sourceJarName()}")
    }

    // ========== Decompiled Source Cache ==========

    /**
     * Gets cached decompiled source for a class.
     * Returns null if not cached.
     */
    fun getDecompiledSource(
        jarPath: String,
        className: String,
    ): String? {
        val sourceFile = decompiledSourcePath(jarPath, className)
        return if (sourceFile.exists()) {
            logger.debug("Cache hit for decompiled source: {} in {}", className, jarPath)
            sourceFile.readText()
        } else {
            logger.debug("Cache miss for decompiled source: {} in {}", className, jarPath)
            null
        }
    }

    /**
     * Stores decompiled source in the cache.
     */
    fun putDecompiledSource(
        jarPath: String,
        className: String,
        source: String,
    ) {
        val sourceFile = decompiledSourcePath(jarPath, className)
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(source)
        logger.debug("Cached decompiled source: {} in {}", className, jarPath)
    }

    /**
     * Checks if decompiled source is cached.
     */
    fun hasDecompiledSource(
        jarPath: String,
        className: String,
    ): Boolean = decompiledSourcePath(jarPath, className).exists()

    private fun decompiledSourcePath(
        jarPath: String,
        className: String,
    ): File {
        val sanitizedClassName = sanitizeClassName(className)
        val jarHash = hashString(jarPath).take(16)
        val sourcePath = sanitizedClassName.replace('.', File.separatorChar) + ".java"
        return File(cacheDir, "decompiled/$jarHash/$sourcePath")
    }

    // ========== JDK Source Cache ==========

    /**
     * Gets cached JDK source for a class.
     * Returns null if not cached.
     */
    fun getJdkSource(
        jdkVersion: String,
        className: String,
    ): String? {
        val sourceFile = jdkSourcePath(jdkVersion, className)
        return if (sourceFile.exists()) {
            logger.debug("Cache hit for JDK source: {} (JDK {})", className, jdkVersion)
            sourceFile.readText()
        } else {
            logger.debug("Cache miss for JDK source: {} (JDK {})", className, jdkVersion)
            null
        }
    }

    /**
     * Stores JDK source in the cache.
     */
    fun putJdkSource(
        jdkVersion: String,
        className: String,
        source: String,
    ) {
        val sourceFile = jdkSourcePath(jdkVersion, className)
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(source)
        logger.debug("Cached JDK source: {} (JDK {})", className, jdkVersion)
    }

    /**
     * Checks if JDK source is cached.
     */
    fun hasJdkSource(
        jdkVersion: String,
        className: String,
    ): Boolean = jdkSourcePath(jdkVersion, className).exists()

    private fun jdkSourcePath(
        jdkVersion: String,
        className: String,
    ): File {
        val sanitizedClassName = sanitizeClassName(className)
        val sourcePath = sanitizedClassName.replace('.', File.separatorChar) + ".java"
        return File(cacheDir, "jdk/$jdkVersion/$sourcePath")
    }

    // ========== Cache Management ==========

    /**
     * Clears all cached data.
     */
    fun clearAll() {
        logger.info("Clearing entire source cache: {}", cacheDir.absolutePath)
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
    }

    /**
     * Clears cached source JAR for specific coordinates.
     */
    fun clearSourceJar(coords: MavenCoordinates) {
        val jarFile = sourceJarPath(coords)
        if (jarFile.exists()) {
            jarFile.delete()
            logger.info("Cleared cached source JAR: {}", coords.toGradleNotation())
        }
    }

    /**
     * Returns the total size of the cache in bytes.
     */
    fun cacheSize(): Long = cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Returns cache statistics.
     */
    fun stats(): CacheStats {
        var sourceJarCount = 0
        var sourceJarSize = 0L
        var decompiledCount = 0
        var decompiledSize = 0L
        var jdkCount = 0
        var jdkSize = 0L

        File(cacheDir, "maven").walkTopDown().filter { it.isFile }.forEach {
            sourceJarCount++
            sourceJarSize += it.length()
        }

        File(cacheDir, "decompiled").walkTopDown().filter { it.isFile }.forEach {
            decompiledCount++
            decompiledSize += it.length()
        }

        File(cacheDir, "jdk").walkTopDown().filter { it.isFile }.forEach {
            jdkCount++
            jdkSize += it.length()
        }

        return CacheStats(
            sourceJarCount = sourceJarCount,
            sourceJarSize = sourceJarSize,
            decompiledCount = decompiledCount,
            decompiledSize = decompiledSize,
            jdkCount = jdkCount,
            jdkSize = jdkSize,
        )
    }

    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Sanitizes a class name to prevent path traversal attacks.
     * Valid class names contain only alphanumeric characters, dots, underscores, and dollar signs.
     *
     * @throws IllegalArgumentException if the class name contains invalid characters
     */
    private fun sanitizeClassName(className: String): String {
        require(!className.contains("..")) { "Invalid class name (contains '..'): $className" }
        require(className.matches(CLASS_NAME_PATTERN)) {
            "Invalid class name (contains illegal characters): $className"
        }
        return className
    }

    companion object {
        private val CLASS_NAME_PATTERN = Regex("^[a-zA-Z0-9._\$]+$")
    }
}

/**
 * Statistics about the source cache.
 */
data class CacheStats(
    val sourceJarCount: Int,
    val sourceJarSize: Long,
    val decompiledCount: Int,
    val decompiledSize: Long,
    val jdkCount: Int,
    val jdkSize: Long,
) {
    val totalCount: Int get() = sourceJarCount + decompiledCount + jdkCount
    val totalSize: Long get() = sourceJarSize + decompiledSize + jdkSize
}

package codelens.source.resolver

import codelens.source.cache.SourceCache
import org.slf4j.LoggerFactory
import java.io.File
import java.util.zip.ZipFile

/**
 * Resolves source code for JDK classes from src.zip.
 */
class JdkSourceResolver(
    private val cache: SourceCache,
    private val decompiler: Decompiler = Decompiler()
) {
    private val logger = LoggerFactory.getLogger(JdkSourceResolver::class.java)

    // Cache the JDK version once detected
    private val jdkVersion: String by lazy { detectJdkVersion() }

    /**
     * Resolves source code for a JDK class.
     *
     * @param className Fully qualified class name (e.g., "java.util.HashMap")
     * @param allowDecompilation Whether to fall back to decompilation if src.zip is not available
     * @return Result containing the source code on success, or an error on failure
     */
    fun resolveSource(className: String, allowDecompilation: Boolean = true): Result<String> {
        // Check cache first
        cache.getJdkSource(jdkVersion, className)?.let { cachedSource ->
            logger.debug("Using cached JDK source for: {}", className)
            return Result.success(cachedSource)
        }

        // Try to extract from src.zip
        val srcZip = findSrcZip()
        if (srcZip != null) {
            extractFromSrcZip(srcZip, className)?.let { source ->
                cache.putJdkSource(jdkVersion, className, source)
                return Result.success(source)
            }
        } else {
            logger.debug("JDK src.zip not found, will attempt decompilation")
        }

        // Fall back to decompilation if allowed
        if (allowDecompilation) {
            val jrtPath = findJrtModule(className)
            if (jrtPath != null) {
                return decompileFromJrt(className)
            }

            // Try from rt.jar (JDK 8 and earlier)
            val rtJar = findRtJar()
            if (rtJar != null) {
                val result = decompiler.decompile(rtJar, className)
                result.onSuccess { source ->
                    cache.putJdkSource(jdkVersion, className, source)
                }
                return result
            }
        }

        return Result.failure(JdkSourceNotFoundException(className, "src.zip not found and decompilation disabled"))
    }

    /**
     * Checks if source is available for a JDK class.
     */
    fun hasSource(className: String): Boolean {
        if (cache.hasJdkSource(jdkVersion, className)) return true
        val srcZip = findSrcZip() ?: return false
        return hasClassInSrcZip(srcZip, className)
    }

    /**
     * Returns the detected JDK version.
     */
    fun jdkVersionString(): String = jdkVersion

    /**
     * Finds the JDK src.zip file.
     * Checks both JDK 9+ location (lib/src.zip) and JDK 8 location (src.zip).
     */
    private fun findSrcZip(): File? {
        val javaHome = System.getProperty("java.home") ?: return null
        val javaHomeDir = File(javaHome)

        // JDK 9+ location
        val srcZip9 = File(javaHomeDir, "lib/src.zip")
        if (srcZip9.exists()) {
            logger.debug("Found JDK src.zip at: {}", srcZip9.absolutePath)
            return srcZip9
        }

        // JDK 8 and earlier location (java.home points to jre, src.zip is in parent)
        val srcZip8 = File(javaHomeDir.parentFile, "src.zip")
        if (srcZip8.exists()) {
            logger.debug("Found JDK src.zip at: {}", srcZip8.absolutePath)
            return srcZip8
        }

        // Some installations put it directly in java.home
        val srcZipDirect = File(javaHomeDir, "src.zip")
        if (srcZipDirect.exists()) {
            logger.debug("Found JDK src.zip at: {}", srcZipDirect.absolutePath)
            return srcZipDirect
        }

        logger.debug("JDK src.zip not found in JAVA_HOME: {}", javaHome)
        return null
    }

    /**
     * Extracts source from src.zip for a specific class.
     */
    private fun extractFromSrcZip(srcZip: File, className: String): String? {
        val sourcePath = classToSourcePath(className)

        return try {
            ZipFile(srcZip).use { zip ->
                // JDK 9+ structure: java.base/java/lang/String.java
                // JDK 8 structure: java/lang/String.java
                val modulePrefixes = listOf(
                    "java.base/",
                    "java.desktop/",
                    "java.logging/",
                    "java.management/",
                    "java.naming/",
                    "java.net.http/",
                    "java.prefs/",
                    "java.rmi/",
                    "java.scripting/",
                    "java.security.jgss/",
                    "java.security.sasl/",
                    "java.sql/",
                    "java.sql.rowset/",
                    "java.transaction.xa/",
                    "java.xml/",
                    "java.xml.crypto/",
                    ""  // Try without module prefix (JDK 8)
                )

                for (prefix in modulePrefixes) {
                    val entryPath = prefix + sourcePath
                    zip.getEntry(entryPath)?.let { entry ->
                        logger.debug("Found JDK source: {} in {}", entryPath, srcZip.name)
                        return zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    }
                }

                logger.debug("JDK source not found for {} in {}", className, srcZip.name)
                null
            }
        } catch (e: Exception) {
            logger.error("Error extracting JDK source from {}: {}", srcZip.absolutePath, e.message, e)
            null
        }
    }

    /**
     * Checks if a class exists in src.zip.
     */
    private fun hasClassInSrcZip(srcZip: File, className: String): Boolean {
        val sourcePath = classToSourcePath(className)

        return try {
            ZipFile(srcZip).use { zip ->
                val modulePrefixes = listOf("java.base/", "")
                modulePrefixes.any { prefix ->
                    zip.getEntry(prefix + sourcePath) != null
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Finds the rt.jar file (JDK 8 and earlier).
     */
    private fun findRtJar(): File? {
        val javaHome = System.getProperty("java.home") ?: return null
        val rtJar = File(javaHome, "lib/rt.jar")
        return if (rtJar.exists()) rtJar else null
    }

    /**
     * Checks if JRT filesystem can access the class module (JDK 9+).
     */
    private fun findJrtModule(className: String): String? {
        // JRT filesystem is available in JDK 9+
        return try {
            val jrtPath = java.nio.file.FileSystems.getFileSystem(java.net.URI.create("jrt:/"))
                .getPath("modules", "java.base", className.replace('.', '/') + ".class")
            if (java.nio.file.Files.exists(jrtPath)) "java.base" else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decompiles a JDK class from the JRT filesystem (JDK 9+).
     */
    private fun decompileFromJrt(className: String): Result<String> {
        // For JDK 9+, we need to extract the class file first, then decompile
        // This is more complex and rarely needed since most JDKs ship with src.zip
        logger.warn("JRT decompilation not yet implemented for: {}", className)
        return Result.failure(JdkSourceNotFoundException(className, "JRT decompilation not implemented"))
    }

    /**
     * Detects the JDK version.
     */
    private fun detectJdkVersion(): String {
        return System.getProperty("java.version") ?: "unknown"
    }

    /**
     * Converts a class name to source file path.
     */
    private fun classToSourcePath(className: String): String {
        val outerClass = className.substringBefore('$')
        return outerClass.replace('.', '/') + ".java"
    }
}

/**
 * Exception thrown when JDK source cannot be found.
 */
class JdkSourceNotFoundException(
    val className: String,
    message: String
) : Exception("JDK source not found for $className: $message")

package codelens.source.resolver

import org.slf4j.LoggerFactory
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Extracts Java/Kotlin source files from source JARs.
 */
class SourceJarExtractor {
    private val logger = LoggerFactory.getLogger(SourceJarExtractor::class.java)

    /**
     * Extracts source code for a specific class from a source JAR.
     *
     * @param sourceJar The source JAR file
     * @param className Fully qualified class name (e.g., "com.example.MyClass")
     * @return The source code, or null if not found
     */
    fun extractSource(
        sourceJar: File,
        className: String,
    ): String? {
        if (!sourceJar.exists()) {
            logger.warn("Source JAR does not exist: {}", sourceJar.absolutePath)
            return null
        }

        val sourcePath = classToSourcePath(className)
        val kotlinPath = classToKotlinPath(className)

        return try {
            ZipFile(sourceJar).use { zip ->
                // Try Java source first
                zip.getEntry(sourcePath)?.let { entry ->
                    logger.debug("Found Java source: {} in {}", sourcePath, sourceJar.name)
                    return zip.getInputStream(entry).bufferedReader().use { it.readText() }
                }

                // Try Kotlin source
                zip.getEntry(kotlinPath)?.let { entry ->
                    logger.debug("Found Kotlin source: {} in {}", kotlinPath, sourceJar.name)
                    return zip.getInputStream(entry).bufferedReader().use { it.readText() }
                }

                // For inner classes, try the outer class file
                if (className.contains('$')) {
                    val outerClassName = className.substringBefore('$')
                    val outerPath = classToSourcePath(outerClassName)
                    zip.getEntry(outerPath)?.let { entry ->
                        logger.debug("Found outer class source for inner class: {} in {}", outerPath, sourceJar.name)
                        return zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    }

                    val outerKotlinPath = classToKotlinPath(outerClassName)
                    zip.getEntry(outerKotlinPath)?.let { entry ->
                        logger.debug("Found outer Kotlin source for inner class: {} in {}", outerKotlinPath, sourceJar.name)
                        return zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    }
                }

                logger.debug("Source not found for {} in {}", className, sourceJar.name)
                null
            }
        } catch (e: Exception) {
            logger.error("Error extracting source from {}: {}", sourceJar.absolutePath, e.message, e)
            null
        }
    }

    /**
     * Lists all source files in a source JAR.
     *
     * @param sourceJar The source JAR file
     * @return List of source file paths (e.g., "com/example/MyClass.java")
     */
    fun listSourceFiles(sourceJar: File): List<String> {
        if (!sourceJar.exists()) {
            return emptyList()
        }

        return try {
            ZipFile(sourceJar).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .filter { !it.isDirectory }
                    .map { it.name }
                    .filter { it.endsWith(".java") || it.endsWith(".kt") }
                    .toList()
            }
        } catch (e: Exception) {
            logger.error("Error listing source files in {}: {}", sourceJar.absolutePath, e.message)
            emptyList()
        }
    }

    /**
     * Extracts all source files from a source JAR to a directory.
     *
     * @param sourceJar The source JAR file
     * @param targetDir The directory to extract to
     * @return Number of files extracted
     */
    fun extractAll(
        sourceJar: File,
        targetDir: File,
    ): Int {
        if (!sourceJar.exists()) {
            return 0
        }

        targetDir.mkdirs()
        val canonicalTargetDir = targetDir.canonicalFile
        var count = 0

        try {
            ZipFile(sourceJar).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .filter { !it.isDirectory }
                    .filter { it.name.endsWith(".java") || it.name.endsWith(".kt") }
                    .forEach { entry ->
                        // Validate entry path to prevent ZIP slip attacks
                        if (!isValidZipEntry(entry, canonicalTargetDir)) {
                            logger.warn("Skipping potentially malicious ZIP entry: {}", entry.name)
                            return@forEach
                        }

                        val targetFile = File(canonicalTargetDir, entry.name)
                        targetFile.parentFile.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            targetFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        count++
                    }
            }
            logger.info("Extracted {} source files from {} to {}", count, sourceJar.name, targetDir.absolutePath)
        } catch (e: Exception) {
            logger.error("Error extracting source JAR {}: {}", sourceJar.absolutePath, e.message, e)
        }

        return count
    }

    /**
     * Validates that a ZIP entry path does not escape the target directory (ZIP slip prevention).
     */
    private fun isValidZipEntry(
        entry: ZipEntry,
        targetDir: File,
    ): Boolean {
        val targetFile = File(targetDir, entry.name).canonicalFile
        return targetFile.toPath().startsWith(targetDir.toPath())
    }

    /**
     * Converts a fully qualified class name to a Java source file path.
     * Example: "com.example.MyClass" -> "com/example/MyClass.java"
     */
    private fun classToSourcePath(className: String): String {
        // Handle inner classes: com.example.Outer$Inner -> com/example/Outer.java
        val outerClass = className.substringBefore('$')
        return outerClass.replace('.', '/') + ".java"
    }

    /**
     * Converts a fully qualified class name to a Kotlin source file path.
     * Example: "com.example.MyClass" -> "com/example/MyClass.kt"
     */
    private fun classToKotlinPath(className: String): String {
        val outerClass = className.substringBefore('$')
        return outerClass.replace('.', '/') + ".kt"
    }
}

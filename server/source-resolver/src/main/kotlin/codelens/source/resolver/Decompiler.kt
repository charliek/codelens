package codelens.source.resolver

import org.benf.cfr.reader.api.CfrDriver
import org.benf.cfr.reader.api.OutputSinkFactory
import org.benf.cfr.reader.api.SinkReturns
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Wrapper for the CFR (Class File Reader) decompiler.
 * Decompiles bytecode to Java source code.
 */
class Decompiler {
    private val logger = LoggerFactory.getLogger(Decompiler::class.java)

    /**
     * Decompiles a class from a JAR file.
     *
     * @param jarPath Path to the JAR file containing the class
     * @param className Fully qualified class name (e.g., "com.example.MyClass")
     * @return Result containing the decompiled source on success, or an error on failure
     */
    fun decompile(
        jarPath: File,
        className: String,
    ): Result<String> {
        if (!jarPath.exists()) {
            return Result.failure(DecompilationException(className, "JAR file not found: ${jarPath.absolutePath}"))
        }

        logger.info("Decompiling {} from {}", className, jarPath.name)

        return try {
            val output = StringBuilder()
            var decompilationError: String? = null

            val outputSink =
                object : OutputSinkFactory {
                    override fun getSupportedSinks(
                        sinkType: OutputSinkFactory.SinkType,
                        collection: Collection<OutputSinkFactory.SinkClass>,
                    ): List<OutputSinkFactory.SinkClass> = listOf(OutputSinkFactory.SinkClass.STRING)

                    override fun <T> getSink(
                        sinkType: OutputSinkFactory.SinkType,
                        sinkClass: OutputSinkFactory.SinkClass,
                    ): OutputSinkFactory.Sink<T> {
                        @Suppress("UNCHECKED_CAST")
                        return when (sinkType) {
                            OutputSinkFactory.SinkType.JAVA ->
                                OutputSinkFactory.Sink { source: T ->
                                    if (source is SinkReturns.Decompiled) {
                                        output.append(source.java)
                                    }
                                }
                            OutputSinkFactory.SinkType.EXCEPTION ->
                                OutputSinkFactory.Sink { source: T ->
                                    if (source is SinkReturns.ExceptionMessage) {
                                        decompilationError = source.message
                                        logger.warn("Decompilation warning for {}: {}", className, source.message)
                                    }
                                }
                            else -> OutputSinkFactory.Sink { }
                        } as OutputSinkFactory.Sink<T>
                    }
                }

            // Convert class name to internal format for CFR
            val internalClassName = className.replace('.', '/')

            val options =
                mapOf(
                    "jarfilter" to internalClassName,
                    "showversion" to "false",
                    "silent" to "true",
                    "comments" to "false",
                    "decodefinally" to "true",
                    "sugarenums" to "true",
                    "removeboilerplate" to "true",
                    "removedeadmethods" to "true",
                    "removebadgenerics" to "true",
                )

            val driver =
                CfrDriver
                    .Builder()
                    .withOptions(options)
                    .withOutputSink(outputSink)
                    .build()

            driver.analyse(listOf(jarPath.absolutePath))

            val result = output.toString()
            if (result.isNotBlank()) {
                logger.info("Successfully decompiled {} ({} chars)", className, result.length)
                Result.success(result)
            } else if (decompilationError != null) {
                Result.failure(DecompilationException(className, decompilationError!!))
            } else {
                Result.failure(DecompilationException(className, "No output produced"))
            }
        } catch (e: Exception) {
            logger.error("Decompilation failed for {}: {}", className, e.message)
            Result.failure(DecompilationException(className, e.message ?: "Unknown error", e))
        }
    }

    /**
     * Decompiles multiple classes from a JAR file.
     *
     * @param jarPath Path to the JAR file
     * @param classNames List of fully qualified class names
     * @return Map of class name to decompiled source (only successful decompilations)
     */
    fun decompileMultiple(
        jarPath: File,
        classNames: List<String>,
    ): Map<String, String> {
        val results = mutableMapOf<String, String>()

        for (className in classNames) {
            decompile(jarPath, className).onSuccess { source ->
                results[className] = source
            }
        }

        return results
    }

    /**
     * Checks if decompilation is likely to succeed for a class.
     * This does a quick check without full decompilation.
     */
    fun canDecompile(
        jarPath: File,
        className: String,
    ): Boolean {
        if (!jarPath.exists()) return false

        return try {
            java.util.jar.JarFile(jarPath).use { jar ->
                val entryName = className.replace('.', '/') + ".class"
                jar.getEntry(entryName) != null
            }
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Exception thrown when decompilation fails.
 */
class DecompilationException(
    val className: String,
    message: String,
    cause: Throwable? = null,
) : Exception("Failed to decompile $className: $message", cause)

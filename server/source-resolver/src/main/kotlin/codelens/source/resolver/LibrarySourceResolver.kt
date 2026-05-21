package codelens.source.resolver

import codelens.core.model.MavenCoordinates
import codelens.core.model.source.SourceOrigin
import codelens.core.model.source.SourceResolutionErrorReason
import codelens.core.model.source.SourceResolutionException
import codelens.source.cache.SourceCache
import codelens.source.model.LibrarySourceInfo
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Main orchestrator for resolving source code from libraries and JDK.
 *
 * Resolution order:
 * 1. For JDK classes -> JdkSourceResolver
 * 2. For library classes:
 *    a. Check if source JAR is cached
 *    b. If not cached, download from Maven Central
 *    c. Extract source from JAR
 *    d. If source JAR unavailable, decompile bytecode (if allowed)
 */
class LibrarySourceResolver(
    private val artifactMappings: Map<String, MavenCoordinates>,
    private val cache: SourceCache = SourceCache(),
    private val mavenClient: MavenCentralClient = MavenCentralClient(),
    private val extractor: SourceJarExtractor = SourceJarExtractor(),
    private val decompiler: Decompiler = Decompiler(),
    private val jdkResolver: JdkSourceResolver = JdkSourceResolver(cache, decompiler),
) {
    private val logger = LoggerFactory.getLogger(LibrarySourceResolver::class.java)

    /**
     * Resolves source code for a library or JDK class.
     *
     * @param fqn Fully qualified class name
     * @param jarPath Path to the JAR containing the class
     * @param isJdkClass Whether this is a JDK class
     * @param allowDecompilation Whether to fall back to decompilation
     * @param forceRefresh Whether to re-download source JARs
     * @return Result containing LibrarySourceInfo on success
     */
    fun resolveSource(
        fqn: String,
        jarPath: String?,
        isJdkClass: Boolean,
        allowDecompilation: Boolean = true,
        forceRefresh: Boolean = false,
    ): Result<LibrarySourceInfo> {
        logger.info("Resolving source for: {} (JDK: {}, decompile: {})", fqn, isJdkClass, allowDecompilation)

        // Handle JDK classes
        if (isJdkClass) {
            return resolveJdkSource(fqn, allowDecompilation)
        }

        // Handle library classes
        if (jarPath == null) {
            return Result.failure(
                SourceResolutionException(
                    fqn,
                    SourceResolutionErrorReason.FILE_NOT_FOUND,
                    "JAR path not available for $fqn",
                ),
            )
        }

        return resolveLibrarySource(fqn, jarPath, allowDecompilation, forceRefresh)
    }

    /**
     * Resolves source for a JDK class.
     */
    private fun resolveJdkSource(
        fqn: String,
        allowDecompilation: Boolean,
    ): Result<LibrarySourceInfo> =
        jdkResolver
            .resolveSource(fqn, allowDecompilation)
            .map { source ->
                val isDecompiled = !jdkResolver.hasSource(fqn)
                LibrarySourceInfo(
                    fqn = fqn,
                    source = source,
                    sourceOrigin = if (isDecompiled) SourceOrigin.DECOMPILED else SourceOrigin.JDK_SOURCE,
                    isDecompiled = isDecompiled,
                )
            }.recoverCatching { error ->
                throw SourceResolutionException(
                    fqn,
                    SourceResolutionErrorReason.JDK_CLASS,
                    error.message ?: "Unknown error resolving JDK source for $fqn",
                )
            }

    /**
     * Resolves source for a library class.
     */
    private fun resolveLibrarySource(
        fqn: String,
        jarPath: String,
        allowDecompilation: Boolean,
        forceRefresh: Boolean,
    ): Result<LibrarySourceInfo> {
        val jarFile = File(jarPath)

        // Look up Maven coordinates for the JAR
        val coordinates = artifactMappings[jarPath]

        if (coordinates != null) {
            // Try source JAR resolution
            val sourceJarResult = resolveFromSourceJar(fqn, coordinates, forceRefresh)
            if (sourceJarResult.isSuccess) {
                return sourceJarResult
            }

            logger.debug("Source JAR resolution failed for {}, trying decompilation", fqn)
        } else {
            logger.debug("No Maven coordinates found for JAR: {}", jarPath)
        }

        // Fall back to decompilation
        if (allowDecompilation) {
            return resolveFromDecompilation(fqn, jarFile, coordinates)
        }

        return Result.failure(
            SourceResolutionException(
                fqn,
                SourceResolutionErrorReason.LIBRARY_CLASS,
                "Source JAR not available and decompilation disabled for $fqn",
            ),
        )
    }

    /**
     * Resolves source from a Maven source JAR.
     */
    private fun resolveFromSourceJar(
        fqn: String,
        coordinates: MavenCoordinates,
        forceRefresh: Boolean,
    ): Result<LibrarySourceInfo> {
        // Check cache (unless forcing refresh)
        val cachedJar =
            if (forceRefresh) {
                cache.clearSourceJar(coordinates)
                null
            } else {
                cache.getSourceJar(coordinates)
            }

        val sourceJar =
            if (cachedJar != null) {
                cachedJar
            } else {
                // Download from Maven Central
                val downloadResult = mavenClient.downloadSourceJar(coordinates)
                if (downloadResult.isFailure) {
                    val error =
                        downloadResult.exceptionOrNull()
                            ?: Exception("Download failed with unknown error")
                    return Result.failure(
                        SourceResolutionException(
                            fqn,
                            SourceResolutionErrorReason.LIBRARY_CLASS,
                            "Failed to download source JAR for ${coordinates.toGradleNotation()}: ${error.message}",
                        ),
                    )
                }
                cache.putSourceJar(coordinates, downloadResult.getOrThrow())
            }

        // Extract source from JAR
        val source = extractor.extractSource(sourceJar, fqn)
        return if (source != null) {
            Result.success(
                LibrarySourceInfo(
                    fqn = fqn,
                    source = source,
                    sourceOrigin = SourceOrigin.SOURCE_JAR,
                    mavenCoordinates = coordinates,
                    isDecompiled = false,
                ),
            )
        } else {
            Result.failure(
                SourceResolutionException(
                    fqn,
                    SourceResolutionErrorReason.FILE_NOT_FOUND,
                    "Class $fqn not found in source JAR",
                ),
            )
        }
    }

    /**
     * Resolves source by decompiling bytecode.
     */
    private fun resolveFromDecompilation(
        fqn: String,
        jarFile: File,
        coordinates: MavenCoordinates?,
    ): Result<LibrarySourceInfo> {
        // Check decompilation cache
        val cachedSource = cache.getDecompiledSource(jarFile.absolutePath, fqn)
        if (cachedSource != null) {
            return Result.success(
                LibrarySourceInfo(
                    fqn = fqn,
                    source = cachedSource,
                    sourceOrigin = SourceOrigin.DECOMPILED,
                    mavenCoordinates = coordinates,
                    isDecompiled = true,
                ),
            )
        }

        // Decompile
        return decompiler
            .decompile(jarFile, fqn)
            .map { source ->
                cache.putDecompiledSource(jarFile.absolutePath, fqn, source)
                LibrarySourceInfo(
                    fqn = fqn,
                    source = source,
                    sourceOrigin = SourceOrigin.DECOMPILED,
                    mavenCoordinates = coordinates,
                    isDecompiled = true,
                )
            }.recoverCatching { error ->
                throw SourceResolutionException(
                    fqn,
                    SourceResolutionErrorReason.LIBRARY_CLASS,
                    "Decompilation failed for $fqn: ${error.message ?: "Unknown error"}",
                )
            }
    }

    /**
     * Checks if source is available for a class (without resolving).
     */
    fun hasSource(
        fqn: String,
        jarPath: String?,
        isJdkClass: Boolean,
        allowDecompilation: Boolean = true,
    ): Boolean {
        if (isJdkClass) {
            return jdkResolver.hasSource(fqn) || allowDecompilation
        }

        if (jarPath == null) return false

        val coordinates = artifactMappings[jarPath]
        if (coordinates != null && cache.hasSourceJar(coordinates)) {
            return true
        }

        if (allowDecompilation) {
            return cache.hasDecompiledSource(jarPath, fqn) ||
                decompiler.canDecompile(File(jarPath), fqn)
        }

        return false
    }

    companion object {
        /**
         * Creates a LibrarySourceResolver from artifact mapping lines.
         * Each line should be in format: "groupId:artifactId:version|/path/to/jar.jar"
         */
        fun fromMappingLines(lines: List<String>): LibrarySourceResolver {
            val mappings = mutableMapOf<String, MavenCoordinates>()

            for (line in lines) {
                MavenCoordinates.parseFromMapping(line)?.let { (coords, jarPath) ->
                    mappings[jarPath] = coords
                }
            }

            return LibrarySourceResolver(mappings)
        }
    }
}

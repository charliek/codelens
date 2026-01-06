package codelens.gradle

import java.io.File

/**
 * Result of classpath resolution.
 */
data class ResolvedClasspath(
    /**
     * All classpath entries (JARs and directories).
     */
    val entries: List<File>,

    /**
     * The project's own build output directories.
     * Used to classify classes as PROJECT vs LIBRARY.
     */
    val projectOutputDirs: Set<File>,

    /**
     * Resolution method used (for diagnostics).
     */
    val resolvedBy: String
)

/**
 * Interface for resolving a project's runtime classpath.
 */
interface ClasspathResolver {
    /**
     * Resolve the classpath for the given project directory.
     *
     * @param projectDir The root directory of the Gradle project
     * @return Resolved classpath with entries categorized by source
     * @throws ClasspathResolutionException if resolution fails
     */
    fun resolve(projectDir: File): ResolvedClasspath
}

/**
 * Exception thrown when classpath resolution fails.
 */
class ClasspathResolutionException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

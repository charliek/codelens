package codelens.gradle

import codelens.core.model.source.SourceRootInfo
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
     * Source root directories for the project.
     * Used for source code retrieval.
     */
    val sourceRoots: List<SourceRootInfo>,

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
     * @param javaHome Optional Java home directory to use for Gradle operations.
     *                 Required when the target project uses an older Gradle version
     *                 that is incompatible with the server's Java version.
     * @return Resolved classpath with entries categorized by source
     * @throws ClasspathResolutionException if resolution fails
     */
    fun resolve(projectDir: File, javaHome: File? = null): ResolvedClasspath
}

/**
 * Exception thrown when classpath resolution fails.
 */
class ClasspathResolutionException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

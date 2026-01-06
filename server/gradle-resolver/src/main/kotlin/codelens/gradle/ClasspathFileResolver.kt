package codelens.gradle

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Resolves classpath from a pre-generated classpath file.
 *
 * This is a fallback option for when the Gradle Tooling API has issues.
 * Users can generate the file by adding a task to their build.gradle:
 *
 * ```groovy
 * // build.gradle (Groovy DSL)
 * tasks.register('writeClasspath') {
 *     doLast {
 *         def cp = configurations.runtimeClasspath.files.collect { it.absolutePath }.join('\n')
 *         file('build/codelens-classpath.txt').text = cp
 *     }
 * }
 * ```
 *
 * ```kotlin
 * // build.gradle.kts (Kotlin DSL)
 * tasks.register("writeClasspath") {
 *     doLast {
 *         val cp = configurations.getByName("runtimeClasspath")
 *             .files.joinToString("\n") { it.absolutePath }
 *         file("build/codelens-classpath.txt").writeText(cp)
 *     }
 * }
 * ```
 */
class ClasspathFileResolver(
    private val classpathFile: File
) : ClasspathResolver {
    private val logger = LoggerFactory.getLogger(ClasspathFileResolver::class.java)

    override fun resolve(projectDir: File): ResolvedClasspath {
        logger.info("Resolving classpath from file: ${classpathFile.absolutePath}")

        if (!classpathFile.exists()) {
            throw ClasspathResolutionException(
                "Classpath file not found: ${classpathFile.absolutePath}\n" +
                    "Generate it by running: ./gradlew writeClasspath\n" +
                    "See documentation for how to add the writeClasspath task."
            )
        }

        val classpathEntries = mutableListOf<File>()
        val projectOutputDirs = mutableSetOf<File>()

        // Read entries from file
        classpathFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { line ->
                val file = File(line.trim())
                if (file.exists()) {
                    classpathEntries.add(file)
                } else {
                    logger.warn("Classpath entry does not exist: $line")
                }
            }

        // Identify project output directories (heuristic: anything under the project's build dir)
        val projectPath = projectDir.absolutePath
        classpathEntries.forEach { entry ->
            val entryPath = entry.absolutePath
            if (entryPath.startsWith(projectPath) && entryPath.contains("/build/")) {
                projectOutputDirs.add(entry)
            }
        }

        // Also scan for standard output directories
        addStandardOutputDirs(projectDir, projectOutputDirs, classpathEntries)

        logger.info("Resolved ${classpathEntries.size} classpath entries, ${projectOutputDirs.size} project output dirs")

        return ResolvedClasspath(
            entries = classpathEntries.distinctBy { it.absolutePath },
            projectOutputDirs = projectOutputDirs,
            resolvedBy = "Classpath file: ${classpathFile.name}"
        )
    }

    /**
     * Adds standard Gradle output directories.
     */
    private fun addStandardOutputDirs(
        projectDir: File,
        projectOutputDirs: MutableSet<File>,
        classpathEntries: MutableList<File>
    ) {
        val buildDir = projectDir.resolve("build")
        val classesDir = buildDir.resolve("classes")

        listOf(
            classesDir.resolve("java/main"),
            classesDir.resolve("kotlin/main"),
            classesDir.resolve("groovy/main"),
            buildDir.resolve("resources/main")
        ).filter { it.exists() }.forEach {
            if (projectOutputDirs.add(it) && it !in classpathEntries) {
                classpathEntries.add(it)
            }
        }
    }
}

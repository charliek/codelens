package codelens.gradle

import org.gradle.tooling.GradleConnector
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Resolves classpath using the Gradle Tooling API.
 *
 * This approach:
 * - Requires no changes to the target project
 * - Uses the project's own Gradle wrapper
 * - Works across Gradle 4.x - 8.x and Java 8-21 projects
 * - Resolves the full runtimeClasspath including transitive dependencies
 */
class GradleProjectResolver : ClasspathResolver {
    private val logger = LoggerFactory.getLogger(GradleProjectResolver::class.java)

    override fun resolve(projectDir: File, javaHome: File?): ResolvedClasspath {
        logger.info("Resolving classpath via Gradle Tooling API for: ${projectDir.absolutePath}")
        if (javaHome != null) {
            logger.info("Using Java home for Gradle: ${javaHome.absolutePath}")
        }

        // Create a temp file for the init script
        val initScript = createTempInitScript()

        val connector = GradleConnector.newConnector()
            .forProjectDirectory(projectDir)
            .useBuildDistribution()

        var outputFile: File? = null
        try {
            connector.connect().use { connection ->
                outputFile = File.createTempFile("codelens-classpath", ".txt")

                val stdout = ByteArrayOutputStream()
                val stderr = ByteArrayOutputStream()

                // Run the classpath-extracting task via init script
                try {
                    val buildLauncher = connection.newBuild()
                        .withArguments(
                            "--init-script", initScript.absolutePath,
                            "-PcodelensOutputFile=${outputFile!!.absolutePath}",
                            "codelensWriteClasspath",
                            "--quiet"
                        )
                        .setStandardOutput(stdout)
                        .setStandardError(stderr)

                    // Set Java home if provided (for older Gradle versions)
                    if (javaHome != null) {
                        buildLauncher.setJavaHome(javaHome)
                    }

                    buildLauncher.run()
                } catch (e: Exception) {
                    val stderrStr = stderr.toString()
                    logger.error("Gradle task failed. Stderr: $stderrStr", e)

                    // Provide helpful error message for Java version incompatibility
                    if (stderrStr.contains("Unsupported class file major version") ||
                        e.message?.contains("Unsupported class file major version") == true) {
                        throw ClasspathResolutionException(
                            buildJavaVersionErrorMessage(e.message),
                            e
                        )
                    }

                    throw ClasspathResolutionException(
                        "Failed to run Gradle classpath resolution task: ${e.message}\nStderr: $stderrStr",
                        e
                    )
                }

                // Parse the output file
                return parseClasspathOutput(outputFile!!, projectDir)
            }
        } catch (e: ClasspathResolutionException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to resolve classpath via Tooling API: ${e.message}", e)
            throw ClasspathResolutionException(
                "Failed to resolve classpath via Gradle Tooling API: ${e.message}",
                e
            )
        } finally {
            connector.disconnect()
            initScript.delete()
            outputFile?.delete()
        }
    }

    /**
     * Builds a helpful error message for Java version incompatibility errors.
     */
    private fun buildJavaVersionErrorMessage(originalMessage: String?): String {
        return """
            |Java version incompatibility: The target project's Gradle version cannot run with Java 21.
            |
            |This typically occurs when analyzing projects using Gradle < 8.5 while running on Java 21.
            |
            |Solutions:
            |  1. Pass --project-java-home to specify a compatible Java installation:
            |     codelens start --project-java-home ~/.sdkman/candidates/java/11.0.28-tem
            |
            |  2. Use --classpath-file with a pre-generated classpath file:
            |     codelens start --classpath-file ./build/codelens-classpath.txt
            |
            |  3. Upgrade the target project's Gradle to 8.5+ (supports Java 21)
            |
            |Original error: $originalMessage
        """.trimMargin()
    }

    /**
     * Creates a temporary Gradle init script that defines a task to write the classpath.
     */
    private fun createTempInitScript(): File {
        val script = """
            allprojects {
                task codelensWriteClasspath {
                    doLast {
                        def outputPath = project.findProperty('codelensOutputFile') ?: 'build/codelens-classpath.txt'
                        def outputFile = new File(outputPath)
                        outputFile.parentFile?.mkdirs()

                        def classpathEntries = []
                        def projectOutputDirs = []

                        // Collect from all source sets
                        if (project.plugins.hasPlugin('java') || project.plugins.hasPlugin('java-library')) {
                            project.sourceSets.each { sourceSet ->
                                // Add output directories
                                sourceSet.output.classesDirs.each { dir ->
                                    if (dir.exists()) {
                                        projectOutputDirs << dir.absolutePath
                                        classpathEntries << dir.absolutePath
                                    }
                                }

                                // Add resources
                                if (sourceSet.output.resourcesDir?.exists()) {
                                    projectOutputDirs << sourceSet.output.resourcesDir.absolutePath
                                    classpathEntries << sourceSet.output.resourcesDir.absolutePath
                                }
                            }

                            // Add runtime dependencies
                            try {
                                configurations.runtimeClasspath.resolvedConfiguration.resolvedArtifacts.each { artifact ->
                                    classpathEntries << artifact.file.absolutePath
                                }
                            } catch (Exception e) {
                                // Try compileClasspath as fallback
                                try {
                                    configurations.compileClasspath.resolvedConfiguration.resolvedArtifacts.each { artifact ->
                                        classpathEntries << artifact.file.absolutePath
                                    }
                                } catch (Exception e2) {
                                    logger.warn("Could not resolve classpath configurations: " + e2.message)
                                }
                            }
                        }

                        // Write output in a parseable format
                        outputFile.text = "# CodeLens Classpath\n" +
                            "# PROJECT_OUTPUTS\n" +
                            projectOutputDirs.join('\n') + '\n' +
                            "# CLASSPATH_ENTRIES\n" +
                            classpathEntries.join('\n') + '\n'

                        println "CodeLens: Wrote classpath (" + classpathEntries.size() + " entries) to " + outputFile.absolutePath
                    }
                }
            }
        """.trimIndent()

        val initScript = File.createTempFile("codelens-init", ".gradle")
        initScript.writeText(script)
        // Note: initScript is deleted in the finally block of resolve()
        return initScript
    }

    /**
     * Parses the classpath output file.
     */
    private fun parseClasspathOutput(outputFile: File, projectDir: File): ResolvedClasspath {
        if (!outputFile.exists()) {
            throw ClasspathResolutionException("Classpath output file was not created")
        }

        val lines = outputFile.readLines()
        val projectOutputDirs = mutableSetOf<File>()
        val classpathEntries = mutableListOf<File>()

        var inProjectOutputs = false
        var inClasspathEntries = false

        for (line in lines) {
            when {
                line.startsWith("#") -> {
                    inProjectOutputs = line.contains("PROJECT_OUTPUTS")
                    inClasspathEntries = line.contains("CLASSPATH_ENTRIES")
                }
                line.isBlank() -> continue
                inProjectOutputs -> {
                    val file = File(line)
                    if (file.exists()) projectOutputDirs.add(file)
                }
                inClasspathEntries -> {
                    val file = File(line)
                    if (file.exists()) classpathEntries.add(file)
                }
            }
        }

        // Also add standard output directories that might not be in the output
        addStandardOutputDirs(projectDir, projectOutputDirs, classpathEntries)

        logger.info("Resolved ${classpathEntries.size} classpath entries, ${projectOutputDirs.size} project output dirs")

        return ResolvedClasspath(
            entries = classpathEntries.distinctBy { it.absolutePath },
            projectOutputDirs = projectOutputDirs,
            resolvedBy = "Gradle Tooling API"
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

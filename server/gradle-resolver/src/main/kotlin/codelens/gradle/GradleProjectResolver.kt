package codelens.gradle

import codelens.core.model.MavenCoordinates
import codelens.core.model.source.SourceRootInfo
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
 * - Is compatible with Gradle's configuration cache (8.14+ / 9.x): the injected
 *   init script reads all project state at configuration time and the writer
 *   task's action touches only captured Strings, so it never references
 *   `Task.project` at execution time (see issue #33).
 */
class GradleProjectResolver : ClasspathResolver {
    private val logger = LoggerFactory.getLogger(GradleProjectResolver::class.java)

    override fun resolve(
        projectDir: File,
        javaHome: File?,
    ): ResolvedClasspath {
        logger.info("Resolving classpath via Gradle Tooling API for: ${projectDir.absolutePath}")
        if (javaHome != null) {
            logger.info("Using Java home for Gradle: ${javaHome.absolutePath}")
        }

        // Create a temp file for the init script
        val initScript = createTempInitScript()

        val connector =
            GradleConnector
                .newConnector()
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
                    val buildLauncher =
                        connection
                            .newBuild()
                            .withArguments(
                                "--init-script",
                                initScript.absolutePath,
                                "-PcodelensOutputFile=${outputFile!!.absolutePath}",
                                "codelensWriteClasspath",
                                "--quiet",
                            ).setStandardOutput(stdout)
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
                        e.message?.contains("Unsupported class file major version") == true
                    ) {
                        throw ClasspathResolutionException(
                            buildJavaVersionErrorMessage(e.message),
                            e,
                        )
                    }

                    throw ClasspathResolutionException(
                        "Failed to run Gradle classpath resolution task: ${e.message}\nStderr: $stderrStr",
                        e,
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
                e,
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
    private fun buildJavaVersionErrorMessage(originalMessage: String?): String =
        """
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

    /**
     * Creates a temporary Gradle init script that defines a task to write the classpath.
     * This aggregates classpath from ALL subprojects in a multi-module build.
     *
     * Configuration-cache compatible (issue #33): all project state (source sets,
     * configurations, project paths) is read during the CONFIGURATION phase inside
     * `gradle.projectsEvaluated`, accumulating into plain serializable Strings. The
     * `codelensWriteClasspath` task's action references only those captured String
     * lists, so it never touches `Task.project` at execution time. The output byte
     * format is identical to the previous (execution-time) script so
     * [parseClasspathOutput] is unchanged.
     */
    private fun createTempInitScript(): File {
        val script =
            """
            import org.gradle.api.artifacts.component.ModuleComponentIdentifier

            // Collect during the CONFIGURATION phase, after every project is evaluated.
            // Reading the project model here (not inside a task action) keeps this init
            // script compatible with Gradle's configuration cache: the writer task's
            // action below captures only plain Strings, never Project/Task/Configuration.
            gradle.projectsEvaluated {
                def codelensClasspathEntries  = new LinkedHashSet()
                def codelensProjectOutputDirs = new LinkedHashSet()
                def codelensSourceRoots       = new LinkedHashSet()   // "path|language|sourceSet|module"
                def codelensArtifactMappings  = new LinkedHashSet()   // "group:name:version|jarPath"

                gradle.rootProject.allprojects { proj ->
                    if (proj.plugins.hasPlugin('java') || proj.plugins.hasPlugin('java-library')) {
                        proj.sourceSets.each { sourceSet ->
                            // Output directories
                            sourceSet.output.classesDirs.each { dir ->
                                if (dir.exists()) {
                                    codelensProjectOutputDirs << dir.absolutePath
                                    codelensClasspathEntries  << dir.absolutePath
                                }
                            }
                            // Resources
                            if (sourceSet.output.resourcesDir?.exists()) {
                                codelensProjectOutputDirs << sourceSet.output.resourcesDir.absolutePath
                                codelensClasspathEntries  << sourceSet.output.resourcesDir.absolutePath
                            }
                            // Java source directories
                            sourceSet.java.srcDirs.each { dir ->
                                if (dir.exists()) {
                                    codelensSourceRoots << (dir.absolutePath + '|java|' + sourceSet.name + '|' + proj.path)
                                }
                            }
                            // Kotlin source directories (if the Kotlin plugin is applied)
                            try {
                                sourceSet.kotlin?.srcDirs?.each { dir ->
                                    if (dir.exists()) {
                                        codelensSourceRoots << (dir.absolutePath + '|kotlin|' + sourceSet.name + '|' + proj.path)
                                    }
                                }
                            } catch (Exception e) {
                                // Kotlin plugin not applied, ignore
                            }
                        }

                        // Runtime dependencies with artifact coordinates. Uses the modern
                        // ArtifactCollection API (incoming.artifacts) and only emits a
                        // coordinate mapping for module components; project/flat-dir/file
                        // dependencies contribute a classpath entry but no coordinate.
                        def collectArtifacts = { conf ->
                            conf.incoming.artifacts.artifacts.each { art ->
                                def f = art.file
                                codelensClasspathEntries << f.absolutePath
                                def cid = art.id.componentIdentifier
                                if (cid instanceof ModuleComponentIdentifier) {
                                    codelensArtifactMappings << (cid.group + ':' + cid.module + ':' + cid.version + '|' + f.absolutePath)
                                }
                            }
                        }
                        try {
                            if (proj.configurations.findByName('runtimeClasspath') != null) {
                                collectArtifacts(proj.configurations.runtimeClasspath)
                            } else {
                                throw new IllegalStateException('no runtimeClasspath configuration')
                            }
                        } catch (Exception e) {
                            // Fall back to compileClasspath
                            try {
                                if (proj.configurations.findByName('compileClasspath') != null) {
                                    collectArtifacts(proj.configurations.compileClasspath)
                                }
                            } catch (Exception e2) {
                                proj.logger.warn("CodeLens: Could not resolve classpath configurations for " + proj.name + ": " + e2.message)
                            }
                        }
                    }
                    println "CodeLens: Collected classpath from project: " + proj.name
                }

                // Materialize captured state into plain Strings/Lists for the task action.
                def outPath   = (gradle.rootProject.findProperty('codelensOutputFile') ?: 'build/codelens-classpath.txt').toString()
                def outDirs   = codelensProjectOutputDirs.toList()
                def cpEntries = codelensClasspathEntries.toList()
                def srcRoots  = codelensSourceRoots.toList()
                def artMaps   = codelensArtifactMappings.toList()

                // Single writer task on the root project. Its action captures ONLY the
                // Strings above, so it is safe to serialize into the configuration cache
                // and re-run on a cache-reuse build without re-reading the project model.
                gradle.rootProject.tasks.create('codelensWriteClasspath') {
                    doLast {
                        def outputFile = new File(outPath)
                        outputFile.parentFile?.mkdirs()

                        def content = new StringBuilder()
                        content.append("# CodeLens Classpath\n")
                        content.append("# PROJECT_OUTPUTS\n")
                        content.append(outDirs.join('\n') + '\n')
                        content.append("# CLASSPATH_ENTRIES\n")
                        content.append(cpEntries.join('\n') + '\n')
                        content.append("# SOURCE_ROOTS\n")
                        srcRoots.each { line -> content.append(line + '\n') }   // path|language|sourceSet|module
                        content.append("# ARTIFACT_MAPPINGS\n")
                        artMaps.each { line -> content.append(line + '\n') }    // group:name:version|jarPath

                        outputFile.text = content.toString()

                        println "CodeLens: Wrote aggregated classpath (" + cpEntries.size() + " entries, " + outDirs.size() + " project outputs, " + srcRoots.size() + " source roots, " + artMaps.size() + " artifact mappings) to " + outputFile.absolutePath
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
    private fun parseClasspathOutput(
        outputFile: File,
        projectDir: File,
    ): ResolvedClasspath {
        if (!outputFile.exists()) {
            throw ClasspathResolutionException("Classpath output file was not created")
        }

        val lines = outputFile.readLines()
        val projectOutputDirs = mutableSetOf<File>()
        val classpathEntries = mutableListOf<File>()
        val sourceRoots = mutableListOf<SourceRootInfo>()
        val artifactMappings = mutableListOf<ArtifactMapping>()

        var inProjectOutputs = false
        var inClasspathEntries = false
        var inSourceRoots = false
        var inArtifactMappings = false

        for (line in lines) {
            when {
                line.startsWith("#") -> {
                    inProjectOutputs = line.contains("PROJECT_OUTPUTS")
                    inClasspathEntries = line.contains("CLASSPATH_ENTRIES")
                    inSourceRoots = line.contains("SOURCE_ROOTS")
                    inArtifactMappings = line.contains("ARTIFACT_MAPPINGS")
                }
                line.isBlank() -> continue
                inArtifactMappings -> {
                    // Format: groupId:artifactId:version|jarPath
                    val parts = line.split("|")
                    if (parts.size >= 2) {
                        val coordinates = MavenCoordinates.parse(parts[0])
                        if (coordinates != null) {
                            artifactMappings.add(
                                ArtifactMapping(
                                    jarPath = parts[1],
                                    coordinates = coordinates,
                                ),
                            )
                        }
                    }
                }
                inSourceRoots -> {
                    // Format: path|language|sourceSet|module
                    val parts = line.split("|")
                    if (parts.size >= 4) {
                        val file = File(parts[0])
                        if (file.exists()) {
                            sourceRoots.add(
                                SourceRootInfo(
                                    path = file,
                                    language = parts[1],
                                    sourceSet = parts[2],
                                    module = parts[3],
                                ),
                            )
                        }
                    }
                }
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

        // Add standard source roots that might not be detected
        addStandardSourceRoots(projectDir, sourceRoots)

        logger.info(
            "Resolved ${classpathEntries.size} classpath entries, ${projectOutputDirs.size} project output dirs, ${sourceRoots.size} source roots, ${artifactMappings.size} artifact mappings",
        )

        return ResolvedClasspath(
            entries = classpathEntries.distinctBy { it.absolutePath },
            projectOutputDirs = projectOutputDirs,
            sourceRoots = sourceRoots.distinctBy { it.path.absolutePath },
            resolvedBy = "Gradle Tooling API",
            artifactMappings = artifactMappings.distinctBy { it.jarPath },
        )
    }

    /**
     * Adds standard Gradle output directories.
     */
    private fun addStandardOutputDirs(
        projectDir: File,
        projectOutputDirs: MutableSet<File>,
        classpathEntries: MutableList<File>,
    ) {
        val buildDir = projectDir.resolve("build")
        val classesDir = buildDir.resolve("classes")

        listOf(
            classesDir.resolve("java/main"),
            classesDir.resolve("kotlin/main"),
            classesDir.resolve("groovy/main"),
            buildDir.resolve("resources/main"),
        ).filter { it.exists() }.forEach {
            if (projectOutputDirs.add(it) && it !in classpathEntries) {
                classpathEntries.add(it)
            }
        }
    }

    /**
     * Adds standard Gradle source directories that might not be detected by the init script.
     */
    private fun addStandardSourceRoots(
        projectDir: File,
        sourceRoots: MutableList<SourceRootInfo>,
    ) {
        val srcDir = projectDir.resolve("src")
        val existingPaths = sourceRoots.map { it.path.absolutePath }.toSet()

        // Standard main source directories
        listOf(
            Triple(srcDir.resolve("main/java"), "java", "main"),
            Triple(srcDir.resolve("main/kotlin"), "kotlin", "main"),
            Triple(srcDir.resolve("test/java"), "java", "test"),
            Triple(srcDir.resolve("test/kotlin"), "kotlin", "test"),
        ).filter { (dir, _, _) -> dir.exists() && dir.absolutePath !in existingPaths }
            .forEach { (dir, language, sourceSet) ->
                sourceRoots.add(
                    SourceRootInfo(
                        path = dir,
                        language = language,
                        sourceSet = sourceSet,
                        module = ":",
                    ),
                )
            }
    }
}

package codelens.server.services

import codelens.classgraph.ClassGraphProvider
import codelens.classgraph.ClassGraphProviderImpl
import codelens.classgraph.projectGraphToDot
import codelens.classgraph.source.SourceResolver
import codelens.core.model.*
import codelens.core.model.MavenCoordinates
import codelens.core.model.source.*
import codelens.gradle.ClasspathFileResolver
import codelens.gradle.ClasspathResolutionException
import codelens.gradle.ClasspathResolver
import codelens.gradle.GradleProjectResolver
import codelens.gradle.ResolvedClasspath
import codelens.source.cache.SourceCache
import codelens.source.format.JavadocExtractor
import codelens.source.format.StubGenerator
import codelens.source.model.StubLanguage
import codelens.source.model.VisibilityFilter
import codelens.source.resolver.LibrarySourceResolver
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Service for analyzing project bytecode.
 *
 * Integrates:
 * - Gradle Tooling API or classpath file for classpath resolution
 * - ClassGraph for bytecode scanning and analysis
 */
class AnalysisService(
    private val projectDir: File,
    classpathFile: String? = null,
    projectJavaHome: String? = null,
    classpathResolverOverride: ClasspathResolver? = null,
    classGraphProviderOverride: ClassGraphProvider? = null,
) {
    private val logger = LoggerFactory.getLogger(AnalysisService::class.java)

    private val classpathResolver: ClasspathResolver
    private val projectJavaHomeFile: File? = projectJavaHome?.let { File(it) }
    private val classGraphProvider: ClassGraphProvider =
        classGraphProviderOverride ?: ClassGraphProviderImpl()

    private val scanExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "codelens-scan-${projectDir.name}").apply { isDaemon = true }
        }

    private val projectInfo: AtomicReference<ProjectInfo>
    private var resolvedClasspath: ResolvedClasspath? = null

    /**
     * Completes when the initial scan finishes (success or failure).
     * Holds the terminal [ProjectStatus] - either [ProjectStatus.READY] or
     * [ProjectStatus.ERROR]. Used by the server's startup contract to gate
     * the `CODELENS_READY` stdout signal on actual analysis readiness.
     *
     * One-shot: subsequent [refresh] calls do not affect this future.
     */
    private val initialScan = CompletableFuture<ProjectStatus>()

    /**
     * Captures the underlying failure from the initial scan, if any.
     * Only populated when the initial scan fails; later [refresh] failures
     * do not overwrite it.
     */
    private val initialScanError = AtomicReference<Throwable?>(null)

    init {
        // Choose classpath resolver based on configuration
        classpathResolver = classpathResolverOverride ?: run {
            if (classpathFile != null) {
                logger.info("Using classpath file resolver: $classpathFile")
                ClasspathFileResolver(File(classpathFile))
            } else {
                logger.info("Using Gradle Tooling API resolver")
                if (projectJavaHomeFile != null) {
                    logger.info("Will use project Java home: ${projectJavaHomeFile.absolutePath}")
                }
                GradleProjectResolver()
            }
        }

        projectInfo =
            AtomicReference(
                ProjectInfo(
                    name = projectDir.name,
                    path = projectDir.absolutePath,
                    status = ProjectStatus.LOADING,
                ),
            )

        // Start initial scan in background; complete the readiness future
        // exactly once, regardless of success/failure, so awaitInitialScan()
        // unblocks the server's startup path.
        scanExecutor.submit {
            try {
                performScan(captureInitialError = true)
            } finally {
                initialScan.complete(projectInfo.get().status)
            }
        }
    }

    /**
     * Performs the classpath resolution and bytecode scanning.
     *
     * @param captureInitialError When true, any thrown exception is also recorded
     *   in [initialScanError] so the startup path can surface a precise failure
     *   reason. Refresh-triggered scans pass false to avoid clobbering the
     *   original startup error.
     */
    private fun performScan(captureInitialError: Boolean = false) {
        try {
            logger.info("Starting scan for project: ${projectDir.name}")

            // Resolve classpath (pass project Java home for Gradle Tooling API)
            val classpath = classpathResolver.resolve(projectDir, projectJavaHomeFile)
            resolvedClasspath = classpath
            logger.info("Resolved ${classpath.entries.size} classpath entries using ${classpath.resolvedBy}")

            // Scan with ClassGraph, passing the resolver name for accurate stats reporting
            val scanResult = classGraphProvider.scan(classpath.entries, classpath.projectOutputDirs, classpath.resolvedBy)
            val stats = scanResult.statistics

            val now = Instant.now()
            projectInfo.updateAndGet { current ->
                current.copy(
                    status = ProjectStatus.READY,
                    classCount = stats.projectClassCount,
                    scannedAt = now.toString(),
                )
            }
            logger.info(
                "Scan completed for ${projectDir.name}: ${stats.projectClassCount} project classes, ${stats.libraryClassCount} library classes",
            )
            if (stats.projectClassCount == 0) {
                logger.warn("${projectDir.name}: $NO_PROJECT_CLASSES_WARNING")
            }
        } catch (e: ClasspathResolutionException) {
            logger.error("Classpath resolution failed for ${projectDir.name}: ${e.message}", e)
            projectInfo.updateAndGet { it.copy(status = ProjectStatus.ERROR) }
            if (captureInitialError) initialScanError.set(e)
        } catch (e: Exception) {
            logger.error("Scan failed for ${projectDir.name}", e)
            projectInfo.updateAndGet { it.copy(status = ProjectStatus.ERROR) }
            if (captureInitialError) initialScanError.set(e)
        }
    }

    /**
     * Blocks until the initial scan finishes and returns its terminal status.
     *
     * The server's startup path calls this before printing `CODELENS_READY`
     * so the stdout signal accurately reflects analysis readiness rather than
     * just "the HTTP listener is bound".
     *
     * @param timeout Optional upper bound on how long to wait.
     * @return [ProjectStatus.READY] on success or [ProjectStatus.ERROR] on failure.
     * @throws TimeoutException if [timeout] is non-null and elapses before the
     *   initial scan completes.
     */
    fun awaitInitialScan(timeout: Duration? = null): ProjectStatus =
        if (timeout != null) {
            initialScan.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
        } else {
            initialScan.get()
        }

    /**
     * Returns the underlying error from the initial scan, or null if the
     * initial scan succeeded or has not finished yet.
     */
    fun getInitialScanError(): Throwable? = initialScanError.get()

    /**
     * Gets the current project info.
     */
    fun getProjectInfo(): ProjectInfo = projectInfo.get()

    /**
     * Triggers a refresh of the project scan.
     */
    fun refresh() {
        projectInfo.updateAndGet { it.copy(status = ProjectStatus.LOADING) }
        scanExecutor.submit { performScan() }
    }

    /**
     * Shuts down the background scan executor.
     */
    fun shutdown() {
        scanExecutor.shutdown()
    }

    /**
     * Lists classes matching the given filter.
     */
    fun listClasses(filter: ClassFilter): List<ClassSummary> = classGraphProvider.listClasses(filter)

    /**
     * Gets full details for a specific class.
     */
    fun getClass(fqn: String): ClassInfo? = classGraphProvider.getClass(fqn)

    /**
     * Gets scan statistics.
     */
    fun getStatistics(): ScanStatistics? = classGraphProvider.getStatistics()

    /**
     * Checks if the scan is complete.
     */
    fun isReady(): Boolean = projectInfo.get().status == ProjectStatus.READY

    /**
     * Find all implementations of an interface or subclasses of a class.
     */
    fun getImplementations(
        fqn: String,
        includeLibraries: Boolean = false,
    ): Pair<List<ClassSummary>, List<ClassSummary>> = classGraphProvider.getImplementations(fqn, includeLibraries)

    /**
     * Get the class hierarchy for a given class.
     */
    fun getHierarchy(fqn: String): HierarchyNode? = classGraphProvider.getHierarchy(fqn)

    /**
     * Get dependencies for a class.
     */
    fun getDependencies(
        fqn: String,
        includeLibraries: Boolean = false,
    ): Pair<List<DependencyInfo>, List<DependencyInfo>> = classGraphProvider.getDependencies(fqn, includeLibraries)

    /**
     * Find every place a specific annotation is applied (class/method/field/param).
     */
    fun getAnnotationUsages(
        annotationFqn: String,
        scope: AnnotationScope = AnnotationScope.ALL,
        includeLibraries: Boolean = false,
    ): List<AnnotationUsage> = classGraphProvider.getAnnotationUsages(annotationFqn, scope, includeLibraries)

    /**
     * Search methods across all classes.
     */
    fun searchMethods(filter: MethodFilter): List<MethodSearchResult> = classGraphProvider.searchMethods(filter)

    /**
     * Extract the invocations a class's method bodies make, from bytecode.
     */
    fun getCalls(
        fqn: String,
        methodName: String? = null,
        descriptor: String? = null,
        inMethodsReturning: String? = null,
        inMethodsAnnotated: String? = null,
    ): CallSiteList = classGraphProvider.getCalls(fqn, methodName, descriptor, inMethodsReturning, inMethodsAnnotated)

    /**
     * Find every reference to a type across the scanned classes (inverse cross-reference).
     */
    fun getReferencesToType(
        typeFqn: String,
        includeLibraries: Boolean = false,
        scopeImplementing: String? = null,
    ): List<XrefReference> = classGraphProvider.getReferencesToType(typeFqn, includeLibraries, scopeImplementing)

    /**
     * Build the project-wide dependency graph.
     */
    fun getProjectGraph(): ProjectGraph = classGraphProvider.getProjectGraph()

    /**
     * Render the project-wide dependency graph as Graphviz DOT.
     */
    fun getProjectGraphDot(): String = projectGraphToDot(classGraphProvider.getProjectGraph())

    /**
     * Find foundation classes (most depended-on project classes).
     */
    fun getFoundationClasses(minDependents: Int = 2): List<FoundationClass> = classGraphProvider.getFoundationClasses(minDependents)

    /**
     * Lazily initialized source resolver for project sources.
     */
    private val sourceResolver: SourceResolver? by lazy {
        val classpath = resolvedClasspath ?: return@lazy null
        val classMap = classGraphProvider.getAllClasses()
        SourceResolver(classpath.sourceRoots, classMap)
    }

    /**
     * Shared source cache for library sources.
     */
    private val sourceCache = SourceCache()

    /**
     * Lazily initialized library source resolver.
     */
    private val librarySourceResolver: LibrarySourceResolver? by lazy {
        val classpath = resolvedClasspath ?: return@lazy null

        // Build artifact mappings from the resolved classpath
        val mappings = mutableMapOf<String, MavenCoordinates>()
        for (mapping in classpath.artifactMappings) {
            mappings[mapping.jarPath] = mapping.coordinates
        }

        logger.info("Initialized library source resolver with ${mappings.size} artifact mappings")
        LibrarySourceResolver(
            artifactMappings = mappings,
            cache = sourceCache,
        )
    }

    /**
     * Gets source code for a class by FQN.
     * Supports project classes, library classes (from source JARs or decompilation),
     * and JDK classes (from src.zip).
     *
     * @param fqn Fully qualified class name
     * @param allowDecompilation Whether to fall back to decompilation when source unavailable
     * @param forceRefresh Whether to re-download source JARs
     * @return SourceInfo or error
     */
    fun getSource(
        fqn: String,
        allowDecompilation: Boolean = true,
        forceRefresh: Boolean = false,
    ): Result<SourceInfo> {
        // First, check what type of class this is
        val classInfo = classGraphProvider.getClass(fqn)

        // If class not found in scan, check if it's a JDK class by package name
        // (JDK classes aren't included in the ClassGraph scan)
        if (classInfo == null) {
            if (isJdkPackage(fqn)) {
                return resolveJdkSource(fqn, allowDecompilation)
            }
            return Result.failure(
                SourceResolutionException(
                    fqn,
                    SourceResolutionErrorReason.CLASS_NOT_FOUND,
                    "Class not found: $fqn",
                ),
            )
        }

        return when (classInfo.source) {
            ClassSource.PROJECT -> {
                // Use project source resolver
                val resolver =
                    sourceResolver
                        ?: return Result.failure(
                            SourceResolutionException(
                                fqn,
                                SourceResolutionErrorReason.FILE_NOT_FOUND,
                                "Source resolver not initialized - scan may still be in progress",
                            ),
                        )
                resolver.resolveClass(fqn)
            }

            ClassSource.LIBRARY, ClassSource.JDK -> {
                // Use library source resolver
                val resolver =
                    librarySourceResolver
                        ?: return Result.failure(
                            SourceResolutionException(
                                fqn,
                                SourceResolutionErrorReason.FILE_NOT_FOUND,
                                "Library source resolver not initialized - scan may still be in progress",
                            ),
                        )

                val isJdk = classInfo.source == ClassSource.JDK
                resolver
                    .resolveSource(
                        fqn = fqn,
                        jarPath = classInfo.jarPath,
                        isJdkClass = isJdk,
                        allowDecompilation = allowDecompilation,
                        forceRefresh = forceRefresh,
                    ).map { libSourceInfo ->
                        // Convert LibrarySourceInfo to SourceInfo
                        SourceInfo(
                            fqn = fqn,
                            filePath = null,
                            language = if (libSourceInfo.language == "KOTLIN") SourceLanguage.KOTLIN else SourceLanguage.JAVA,
                            content = libSourceInfo.source,
                            lineCount = libSourceInfo.source.lines().size,
                            module = null,
                            sourceOrigin = libSourceInfo.sourceOrigin,
                            mavenCoordinates = libSourceInfo.mavenCoordinates?.toGradleNotation(),
                            isDecompiled = libSourceInfo.isDecompiled,
                            format = SourceFormat.FULL,
                        )
                    }.recoverCatching { error ->
                        throw SourceResolutionException(
                            fqn,
                            if (isJdk) SourceResolutionErrorReason.JDK_CLASS else SourceResolutionErrorReason.LIBRARY_CLASS,
                            error.message ?: "Unknown error",
                        )
                    }
            }
        }
    }

    /**
     * Checks if a fully qualified class name belongs to a JDK package.
     */
    private fun isJdkPackage(fqn: String): Boolean {
        val jdkPrefixes =
            listOf(
                "java.",
                "javax.",
                "sun.",
                "com.sun.",
                "jdk.",
                "org.w3c.",
                "org.xml.",
                "org.ietf.",
            )
        return jdkPrefixes.any { fqn.startsWith(it) }
    }

    /**
     * Resolves source for a JDK class that isn't in the ClassGraph scan.
     */
    private fun resolveJdkSource(
        fqn: String,
        allowDecompilation: Boolean,
    ): Result<SourceInfo> {
        val resolver =
            librarySourceResolver
                ?: return Result.failure(
                    SourceResolutionException(
                        fqn,
                        SourceResolutionErrorReason.FILE_NOT_FOUND,
                        "Library source resolver not initialized - scan may still be in progress",
                    ),
                )

        return resolver
            .resolveSource(
                fqn = fqn,
                jarPath = null, // JDK classes don't have a jar path
                isJdkClass = true,
                allowDecompilation = allowDecompilation,
                forceRefresh = false,
            ).map { libSourceInfo ->
                SourceInfo(
                    fqn = fqn,
                    filePath = null,
                    language = if (libSourceInfo.language == "KOTLIN") SourceLanguage.KOTLIN else SourceLanguage.JAVA,
                    content = libSourceInfo.source,
                    lineCount = libSourceInfo.source.lines().size,
                    module = null,
                    sourceOrigin = libSourceInfo.sourceOrigin,
                    mavenCoordinates = null,
                    isDecompiled = libSourceInfo.isDecompiled,
                    format = SourceFormat.FULL,
                )
            }.recoverCatching { error ->
                throw SourceResolutionException(
                    fqn,
                    SourceResolutionErrorReason.JDK_CLASS,
                    error.message ?: "Unknown error resolving JDK source",
                )
            }
    }

    /**
     * Gets source code for a specific method.
     *
     * @param fqn Fully qualified class name
     * @param methodName Method name
     * @param parameterTypes Optional list of parameter types for disambiguation
     * @param contextLines Number of context lines to include before/after
     * @return MethodSourceInfo or error
     */
    fun getMethodSource(
        fqn: String,
        methodName: String,
        parameterTypes: List<String>? = null,
        contextLines: Int = 0,
    ): Result<MethodSourceInfo> {
        val resolver =
            sourceResolver
                ?: return Result.failure(
                    SourceResolutionException(
                        fqn,
                        SourceResolutionErrorReason.FILE_NOT_FOUND,
                        "Source resolver not initialized - scan may still be in progress",
                    ),
                )
        return resolver.resolveMethod(fqn, methodName, parameterTypes, contextLines)
    }

    /**
     * Stub generator for creating source stubs from bytecode.
     */
    private val stubGenerator = StubGenerator()

    /**
     * Javadoc extractor for extracting signatures with doc comments.
     */
    private val javadocExtractor = JavadocExtractor()

    /**
     * Gets a source stub for a class.
     * Works for any class - no source code required.
     *
     * @param fqn Fully qualified class name
     * @param language Target language (JAVA or KOTLIN)
     * @param visibility Visibility filter (ALL, PUBLIC, PUBLIC_PROTECTED)
     * @param format Output format (STUB or SIGNATURES)
     * @return SourceInfo with generated stub
     */
    fun getStub(
        fqn: String,
        language: StubLanguage = StubLanguage.JAVA,
        visibility: VisibilityFilter = VisibilityFilter.ALL,
        format: SourceFormat = SourceFormat.STUB,
    ): Result<SourceInfo> {
        val classInfo =
            classGraphProvider.getClass(fqn)
                ?: return Result.failure(
                    SourceResolutionException(
                        fqn,
                        SourceResolutionErrorReason.CLASS_NOT_FOUND,
                        "Class not found: $fqn",
                    ),
                )

        val stubSource = stubGenerator.generateStub(classInfo, language, visibility, format)

        return Result.success(
            SourceInfo(
                fqn = fqn,
                filePath = null,
                language = if (language == StubLanguage.KOTLIN) SourceLanguage.KOTLIN else SourceLanguage.JAVA,
                content = stubSource,
                lineCount = stubSource.lines().size,
                module = null,
                sourceOrigin =
                    when (classInfo.source) {
                        ClassSource.PROJECT -> SourceOrigin.PROJECT_SOURCE
                        ClassSource.LIBRARY -> SourceOrigin.SOURCE_JAR // Stub from bytecode
                        ClassSource.JDK -> SourceOrigin.JDK_SOURCE
                    },
                mavenCoordinates = null,
                isDecompiled = false,
                format = format,
            ),
        )
    }

    /**
     * Gets source with javadoc comments only.
     * Requires actual source code (not bytecode).
     *
     * @param fqn Fully qualified class name
     * @param visibility Visibility filter
     * @param allowDecompilation Whether to allow decompilation fallback
     * @param forceRefresh Whether to force re-download of source JARs
     * @return SourceInfo with javadoc-only content
     */
    fun getSourceWithJavadoc(
        fqn: String,
        visibility: VisibilityFilter = VisibilityFilter.ALL,
        allowDecompilation: Boolean = true,
        forceRefresh: Boolean = false,
    ): Result<SourceInfo> {
        // First get the full source
        val sourceResult = getSource(fqn, allowDecompilation, forceRefresh)

        return sourceResult.map { sourceInfo ->
            val language = if (sourceInfo.language == SourceLanguage.KOTLIN) "kotlin" else "java"
            val extractedContent =
                javadocExtractor.extractWithDocs(
                    source = sourceInfo.content,
                    language = language,
                    visibility = visibility,
                )

            sourceInfo.copy(
                content = extractedContent,
                lineCount = extractedContent.lines().size,
                format = SourceFormat.JAVADOC,
            )
        }
    }

    companion object {
        /**
         * Advisory message surfaced when a scan resolves to zero project
         * classes. CodeLens analyzes compiled bytecode under `build/classes`, so
         * the overwhelmingly common cause is an uncompiled (or wrong) project.
         * Kept single-line and free of double quotes so it can be embedded
         * verbatim in the `CODELENS_WARNING` readiness line the CLI parses.
         */
        const val NO_PROJECT_CLASSES_WARNING: String =
            "project may not be compiled (0 project classes found); CodeLens analyzes compiled bytecode, " +
                "so run './gradlew build' (or 'classes testClasses'), then 'codelens refresh'"
    }
}

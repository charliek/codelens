package codelens.server.services

import codelens.classgraph.ClassGraphProvider
import codelens.classgraph.ClassGraphProviderImpl
import codelens.core.model.*
import codelens.gradle.ClasspathFileResolver
import codelens.gradle.ClasspathResolutionException
import codelens.gradle.ClasspathResolver
import codelens.gradle.GradleProjectResolver
import codelens.gradle.ResolvedClasspath
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
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
    classpathFile: String? = null
) {
    private val logger = LoggerFactory.getLogger(AnalysisService::class.java)

    private val classpathResolver: ClasspathResolver
    private val classGraphProvider: ClassGraphProvider = ClassGraphProviderImpl()

    private val projectInfo: AtomicReference<ProjectInfo>
    private var resolvedClasspath: ResolvedClasspath? = null

    init {
        // Choose classpath resolver based on configuration
        classpathResolver = if (classpathFile != null) {
            logger.info("Using classpath file resolver: $classpathFile")
            ClasspathFileResolver(File(classpathFile))
        } else {
            logger.info("Using Gradle Tooling API resolver")
            GradleProjectResolver()
        }

        projectInfo = AtomicReference(ProjectInfo(
            name = projectDir.name,
            path = projectDir.absolutePath,
            status = ProjectStatus.LOADING
        ))

        // Start initial scan in background
        Thread {
            performScan()
        }.start()
    }

    /**
     * Performs the classpath resolution and bytecode scanning.
     */
    private fun performScan() {
        try {
            logger.info("Starting scan for project: ${projectDir.name}")

            // Resolve classpath
            val classpath = classpathResolver.resolve(projectDir)
            resolvedClasspath = classpath
            logger.info("Resolved ${classpath.entries.size} classpath entries using ${classpath.resolvedBy}")

            // Scan with ClassGraph
            val scanResult = classGraphProvider.scan(classpath.entries, classpath.projectOutputDirs)
            val stats = scanResult.statistics

            val now = Instant.now()
            projectInfo.updateAndGet { current ->
                current.copy(
                    status = ProjectStatus.READY,
                    classCount = stats.projectClassCount,
                    handlerCount = countHandlers(), // Count Ratpack handlers
                    scannedAt = now.toString()
                )
            }
            logger.info("Scan completed for ${projectDir.name}: ${stats.projectClassCount} project classes, ${stats.libraryClassCount} library classes")
        } catch (e: ClasspathResolutionException) {
            logger.error("Classpath resolution failed for ${projectDir.name}: ${e.message}", e)
            projectInfo.updateAndGet { it.copy(status = ProjectStatus.ERROR) }
        } catch (e: Exception) {
            logger.error("Scan failed for ${projectDir.name}", e)
            projectInfo.updateAndGet { it.copy(status = ProjectStatus.ERROR) }
        }
    }

    /**
     * Counts Ratpack Handler implementations in the project.
     */
    private fun countHandlers(): Int {
        return listClasses(ClassFilter(
            implementsInterface = "ratpack.handling.Handler",
            includeLibraries = false
        )).size
    }

    /**
     * Gets the current project info.
     */
    fun getProjectInfo(): ProjectInfo = projectInfo.get()

    /**
     * Triggers a refresh of the project scan.
     */
    fun refresh() {
        projectInfo.updateAndGet { it.copy(status = ProjectStatus.LOADING) }
        Thread {
            performScan()
        }.start()
    }

    /**
     * Lists classes matching the given filter.
     */
    fun listClasses(filter: ClassFilter): List<ClassSummary> {
        return classGraphProvider.listClasses(filter)
    }

    /**
     * Gets full details for a specific class.
     */
    fun getClass(fqn: String): ClassInfo? {
        return classGraphProvider.getClass(fqn)
    }

    /**
     * Gets scan statistics.
     */
    fun getStatistics(): ScanStatistics? {
        return classGraphProvider.getStatistics()
    }

    /**
     * Checks if the scan is complete.
     */
    fun isReady(): Boolean {
        return projectInfo.get().status == ProjectStatus.READY
    }

    /**
     * Find all implementations of an interface or subclasses of a class.
     */
    fun getImplementations(fqn: String, includeLibraries: Boolean = false): Pair<List<ClassSummary>, List<ClassSummary>> {
        return classGraphProvider.getImplementations(fqn, includeLibraries)
    }

    /**
     * Get the class hierarchy for a given class.
     */
    fun getHierarchy(fqn: String): HierarchyNode? {
        return classGraphProvider.getHierarchy(fqn)
    }

    /**
     * Get dependencies for a class.
     */
    fun getDependencies(fqn: String, includeLibraries: Boolean = false): Pair<List<DependencyInfo>, List<DependencyInfo>> {
        return classGraphProvider.getDependencies(fqn, includeLibraries)
    }

    /**
     * Find all classes using a specific annotation.
     */
    fun getAnnotationUsages(annotationFqn: String, includeLibraries: Boolean = false): List<ClassSummary> {
        return classGraphProvider.getAnnotationUsages(annotationFqn, includeLibraries)
    }

    /**
     * Search methods across all classes.
     */
    fun searchMethods(filter: MethodFilter): List<MethodSearchResult> {
        return classGraphProvider.searchMethods(filter)
    }
}

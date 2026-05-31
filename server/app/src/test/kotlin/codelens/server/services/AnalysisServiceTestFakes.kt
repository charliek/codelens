package codelens.server.services

import codelens.classgraph.ClassGraphProvider
import codelens.classgraph.ScanResult
import codelens.core.model.*
import codelens.gradle.ClasspathResolver
import codelens.gradle.ResolvedClasspath
import java.io.File

// Shared fakes used by AnalysisService / startup-readiness tests.
//
// Kept internal to this test module so the two test classes can drive a real
// AnalysisService (and a real Netty server) without needing a Gradle project
// or a populated ClassGraph index.

/** Resolver that sleeps before returning an empty classpath. */
internal class DelayingResolver(
    private val delayMs: Long,
) : ClasspathResolver {
    override fun resolve(
        projectDir: File,
        javaHome: File?,
    ): ResolvedClasspath {
        Thread.sleep(delayMs)
        return emptyResolved()
    }
}

/** Resolver that always throws. */
internal class ThrowingResolver(
    private val error: Throwable,
) : ClasspathResolver {
    override fun resolve(
        projectDir: File,
        javaHome: File?,
    ): ResolvedClasspath = throw error
}

/** Resolver that returns an empty result without delay. */
internal class StaticResolver : ClasspathResolver {
    override fun resolve(
        projectDir: File,
        javaHome: File?,
    ): ResolvedClasspath = emptyResolved()
}

internal fun emptyResolved(): ResolvedClasspath =
    ResolvedClasspath(
        entries = emptyList(),
        projectOutputDirs = emptySet(),
        sourceRoots = emptyList(),
        resolvedBy = "test",
    )

/** ClassGraphProvider that returns empty results from every operation. */
internal open class EmptyClassGraphProvider : ClassGraphProvider {
    override fun scan(
        classpathEntries: List<File>,
        projectOutputDirs: Set<File>,
        resolvedBy: String,
    ): ScanResult =
        ScanResult(
            classes = emptyMap(),
            statistics = emptyStats(resolvedBy, classpathEntries.size),
            projectOutputDirs = projectOutputDirs,
        )

    override fun listClasses(filter: ClassFilter): List<ClassSummary> = emptyList()

    override fun getClass(fqn: String): ClassInfo? = null

    override fun getStatistics(): ScanStatistics? = null

    override fun isScanned(): Boolean = true

    override fun getImplementations(
        fqn: String,
        includeLibraries: Boolean,
    ): Pair<List<ClassSummary>, List<ClassSummary>> = emptyList<ClassSummary>() to emptyList()

    override fun getHierarchy(fqn: String): HierarchyNode? = null

    override fun getDependencies(
        fqn: String,
        includeLibraries: Boolean,
    ): Pair<List<DependencyInfo>, List<DependencyInfo>> = emptyList<DependencyInfo>() to emptyList()

    override fun getAnnotationUsages(
        annotationFqn: String,
        scope: AnnotationScope,
        includeLibraries: Boolean,
    ): List<AnnotationUsage> = emptyList()

    override fun searchMethods(filter: MethodFilter): List<MethodSearchResult> = emptyList()

    override fun getAllClasses(): Map<String, ClassInfo> = emptyMap()

    override fun getClassBytes(fqn: String): ByteArray? = null

    override fun getCalls(
        fqn: String,
        methodName: String?,
        descriptor: String?,
        inMethodsReturning: String?,
        inMethodsAnnotated: String?,
    ): CallSiteList = CallSiteList(fqn, emptyList())

    override fun getReferencesToType(
        typeFqn: String,
        includeLibraries: Boolean,
        scopeImplementing: String?,
    ): List<XrefReference> = emptyList()

    override fun getProjectGraph(): ProjectGraph = ProjectGraph(emptyList(), emptyList(), 0, 0)

    override fun getFoundationClasses(minDependents: Int): List<FoundationClass> = emptyList()
}

/** ClassGraphProvider that throws on scan; otherwise behaves like the empty provider. */
internal class ThrowingClassGraphProvider(
    private val error: Throwable,
) : EmptyClassGraphProvider() {
    override fun scan(
        classpathEntries: List<File>,
        projectOutputDirs: Set<File>,
        resolvedBy: String,
    ): ScanResult = throw error
}

/**
 * ClassGraphProvider whose scan reports a non-zero project class count, so the
 * "no project classes" advisory must NOT fire. Otherwise behaves like the
 * empty provider.
 */
internal class NonEmptyClassGraphProvider(
    private val projectClassCount: Int = 3,
) : EmptyClassGraphProvider() {
    override fun scan(
        classpathEntries: List<File>,
        projectOutputDirs: Set<File>,
        resolvedBy: String,
    ): ScanResult =
        ScanResult(
            classes = emptyMap(),
            statistics = statsWithProjectClasses(resolvedBy, classpathEntries.size, projectClassCount),
            projectOutputDirs = projectOutputDirs,
        )
}

internal fun emptyStats(
    resolvedBy: String,
    entryCount: Int,
): ScanStatistics = statsWithProjectClasses(resolvedBy, entryCount, 0)

internal fun statsWithProjectClasses(
    resolvedBy: String,
    entryCount: Int,
    projectClassCount: Int,
): ScanStatistics =
    ScanStatistics(
        projectClassCount = projectClassCount,
        libraryClassCount = 0,
        jdkClassCount = 0,
        projectInterfaceCount = 0,
        projectAbstractClassCount = 0,
        projectEnumCount = 0,
        projectAnnotationCount = 0,
        projectMethodCount = 0,
        projectFieldCount = 0,
        classpathResolvedBy = resolvedBy,
        classpathEntryCount = entryCount,
        scanDurationMs = 0,
        scannedAt = "1970-01-01T00:00:00Z",
    )

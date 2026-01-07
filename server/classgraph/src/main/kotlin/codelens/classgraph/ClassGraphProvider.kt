package codelens.classgraph

import codelens.core.model.*
import java.io.File

/**
 * Result of a ClassGraph scan.
 */
data class ScanResult(
    /** All scanned class information */
    val classes: Map<String, ClassInfo>,
    /** Statistics about the scan */
    val statistics: ScanStatistics,
    /** Project output directories (used to classify PROJECT vs LIBRARY) */
    val projectOutputDirs: Set<File>
)

/**
 * Interface for ClassGraph-based bytecode analysis.
 */
interface ClassGraphProvider {
    /**
     * Scan the classpath and build class information.
     *
     * @param classpathEntries List of JAR files and directories to scan
     * @param projectOutputDirs Directories containing project output (used to classify PROJECT vs LIBRARY)
     * @param resolvedBy Name of the resolver that produced the classpath (e.g., "Gradle Tooling API")
     * @return Scan result with all class information
     */
    fun scan(classpathEntries: List<File>, projectOutputDirs: Set<File>, resolvedBy: String = "ClassGraph"): ScanResult

    /**
     * List classes matching the given filter.
     *
     * @param filter Filter criteria
     * @return List of matching class summaries
     */
    fun listClasses(filter: ClassFilter): List<ClassSummary>

    /**
     * Get full details for a specific class.
     *
     * @param fqn Fully qualified class name
     * @return Class information, or null if not found
     */
    fun getClass(fqn: String): ClassInfo?

    /**
     * Get scan statistics.
     *
     * @return Statistics about the scanned codebase
     */
    fun getStatistics(): ScanStatistics?

    /**
     * Check if a scan has been completed.
     */
    fun isScanned(): Boolean

    /**
     * Find all implementations of an interface or subclasses of a class.
     *
     * @param fqn Fully qualified name of the interface or class
     * @param includeLibraries Include library classes in results
     * @return Pair of (direct implementations, indirect implementations)
     */
    fun getImplementations(fqn: String, includeLibraries: Boolean = false): Pair<List<ClassSummary>, List<ClassSummary>>

    /**
     * Get the class hierarchy for a given class.
     *
     * @param fqn Fully qualified class name
     * @return Hierarchy node with parent chain and children
     */
    fun getHierarchy(fqn: String): HierarchyNode?

    /**
     * Get dependencies for a class (both incoming and outgoing).
     *
     * @param fqn Fully qualified class name
     * @param includeLibraries Include library classes in dependencies
     * @return Pair of (outgoing dependencies, incoming dependencies)
     */
    fun getDependencies(fqn: String, includeLibraries: Boolean = false): Pair<List<DependencyInfo>, List<DependencyInfo>>

    /**
     * Find all classes using a specific annotation.
     *
     * @param annotationFqn Fully qualified name of the annotation
     * @param includeLibraries Include library classes in results
     * @return List of classes using the annotation
     */
    fun getAnnotationUsages(annotationFqn: String, includeLibraries: Boolean = false): List<ClassSummary>

    /**
     * Search methods across all classes.
     *
     * @param filter Method filter criteria
     * @return List of methods matching the filter with their containing class
     */
    fun searchMethods(filter: MethodFilter): List<MethodSearchResult>
}

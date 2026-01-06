package codelens.core.model

import kotlinx.serialization.Serializable

/**
 * Statistics about the scanned codebase.
 */
@Serializable
data class ScanStatistics(
    /** Total number of project classes */
    val projectClassCount: Int,
    /** Total number of library classes */
    val libraryClassCount: Int,
    /** Total number of JDK classes */
    val jdkClassCount: Int,
    /** Number of interfaces in project */
    val projectInterfaceCount: Int,
    /** Number of abstract classes in project */
    val projectAbstractClassCount: Int,
    /** Number of enums in project */
    val projectEnumCount: Int,
    /** Number of annotations in project */
    val projectAnnotationCount: Int,
    /** Total methods in project classes */
    val projectMethodCount: Int,
    /** Total fields in project classes */
    val projectFieldCount: Int,
    /** How the classpath was resolved */
    val classpathResolvedBy: String,
    /** Number of classpath entries */
    val classpathEntryCount: Int,
    /** Scan duration in milliseconds */
    val scanDurationMs: Long,
    /** Timestamp when scan completed (ISO-8601) */
    val scannedAt: String
)

/**
 * Response for class list endpoint.
 */
@Serializable
data class ClassListResponse(
    /** List of class summaries */
    val classes: List<ClassSummary>,
    /** Total count (before pagination) */
    val totalCount: Int,
    /** Current page (0-based) */
    val page: Int,
    /** Page size */
    val pageSize: Int,
    /** Total pages */
    val totalPages: Int,
    /** Filter that was applied */
    val appliedFilter: ClassFilterSummary
)

/**
 * Summary of the filter that was applied (for display purposes).
 */
@Serializable
data class ClassFilterSummary(
    val packagePattern: String?,
    val namePattern: String?,
    val source: String?,
    val hasAnnotation: String?,
    val extendsClass: String?,
    val implementsInterface: String?
)

/**
 * Response for class details endpoint.
 */
@Serializable
data class ClassDetailResponse(
    /** Full class information */
    val classInfo: ClassInfo
)

/**
 * Response for implementations endpoint.
 */
@Serializable
data class ImplementationsResponse(
    /** The interface/class being queried */
    val targetClass: String,
    /** Direct implementations */
    val directImplementations: List<ClassSummary>,
    /** Indirect implementations (implementations of implementations) */
    val indirectImplementations: List<ClassSummary>,
    /** Total count */
    val totalCount: Int
)

/**
 * Response for dependencies endpoint.
 */
@Serializable
data class DependenciesResponse(
    /** The class being queried */
    val targetClass: String,
    /** Classes this class depends on (outgoing) */
    val outgoing: List<DependencyInfo>,
    /** Classes that depend on this class (incoming) */
    val incoming: List<DependencyInfo>
)

/**
 * Information about a single dependency.
 */
@Serializable
data class DependencyInfo(
    /** The dependent/dependency class FQN */
    val classFqn: String,
    /** Type of dependency */
    val dependencyType: DependencyType,
    /** Source of the class */
    val source: ClassSource,
    /** Location (method/field name) where dependency occurs */
    val location: String? = null
)

/**
 * Type of dependency relationship.
 */
@Serializable
enum class DependencyType {
    /** Class extends another class */
    EXTENDS,
    /** Class implements an interface */
    IMPLEMENTS,
    /** Field type */
    FIELD_TYPE,
    /** Method return type */
    METHOD_RETURN_TYPE,
    /** Method parameter type */
    METHOD_PARAMETER,
    /** Local variable or other reference */
    TYPE_REFERENCE
}

/**
 * Response for hierarchy endpoint.
 */
@Serializable
data class HierarchyResponse(
    /** The class being queried */
    val targetClass: String,
    /** Hierarchy tree */
    val hierarchy: HierarchyNode
)

/**
 * Node in a class hierarchy tree.
 */
@Serializable
data class HierarchyNode(
    /** Class FQN */
    val classFqn: String,
    /** Simple name */
    val simpleName: String,
    /** Source of the class */
    val source: ClassSource,
    /** Is this an interface? */
    val isInterface: Boolean,
    /** Parent class (null for java.lang.Object) */
    val parent: HierarchyNode? = null,
    /** Implemented interfaces */
    val interfaces: List<HierarchyNode> = emptyList(),
    /** Child classes/implementations */
    val children: List<HierarchyNode> = emptyList()
)

/**
 * Result of a method search, containing method info with its containing class.
 */
@Serializable
data class MethodSearchResult(
    /** Fully qualified class name containing the method */
    val classFqn: String,
    /** Simple name of the containing class */
    val classSimpleName: String,
    /** Source of the containing class */
    val classSource: ClassSource,
    /** The method information */
    val method: MethodInfo
)

/**
 * Response for method search endpoint.
 */
@Serializable
data class MethodSearchResponse(
    /** Methods matching the search criteria */
    val methods: List<MethodSearchResult>,
    /** Total count */
    val totalCount: Int,
    /** Current page (0-based) */
    val page: Int,
    /** Page size */
    val pageSize: Int,
    /** Total pages */
    val totalPages: Int
)

/**
 * Response for annotation usages endpoint.
 */
@Serializable
data class AnnotationUsagesResponse(
    /** The annotation being queried */
    val annotationFqn: String,
    /** Classes using this annotation */
    val usages: List<ClassSummary>,
    /** Total count */
    val totalCount: Int
)

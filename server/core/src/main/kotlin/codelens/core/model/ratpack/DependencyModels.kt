package codelens.core.model.ratpack

import kotlinx.serialization.Serializable

// ============================================================================
// Dependency Analysis Models
// ============================================================================

/**
 * Classification of class types for dependency analysis.
 */
@Serializable
enum class ClassType {
    /** Ratpack handler class */
    HANDLER,
    /** Service class (business logic) */
    SERVICE,
    /** Repository/DAO class */
    REPOSITORY,
    /** Utility/helper class */
    UTILITY,
    /** Other project class */
    OTHER
}

/**
 * Complete dependency analysis result for the project.
 */
@Serializable
data class DependencyAnalysis(
    /** Classes that many handlers depend on - migrate these first */
    val foundationClasses: List<FoundationClass>,
    /** Handlers with few dependencies - easy starting points */
    val quickWins: List<QuickWinHandler>,
    /** Circular dependencies detected */
    val cycles: List<DependencyCycle>,
    /** Handlers grouped by dependency depth */
    val handlerTiers: List<DependencyTier>,
    /** Overall statistics */
    val stats: DependencyStats
)

/**
 * A class that many handlers depend on (foundation for migration).
 */
@Serializable
data class FoundationClass(
    /** Fully qualified class name */
    val fqn: String,
    /** Simple class name */
    val simpleName: String,
    /** Classification of this class */
    val type: ClassType,
    /** Number of handlers that depend on this class */
    val dependentCount: Int,
    /** Simple names of handlers that depend on this class */
    val dependentHandlers: List<String>
)

/**
 * A handler that is easy to migrate (few dependencies).
 */
@Serializable
data class QuickWinHandler(
    /** Fully qualified class name */
    val fqn: String,
    /** Simple class name */
    val simpleName: String,
    /** Number of project-level dependencies */
    val dependencyCount: Int,
    /** Complexity tier from ComplexityCalculator */
    val complexity: ComplexityTier
)

/**
 * A circular dependency detected in the codebase.
 */
@Serializable
data class DependencyCycle(
    /** FQNs of classes in the cycle, in order */
    val classes: List<String>,
    /** Human-readable description (e.g., "A -> B -> C -> A") */
    val description: String
)

/**
 * A tier of handlers grouped by dependency depth.
 */
@Serializable
data class DependencyTier(
    /** Tier number (0 = no dependencies, 1 = depends only on tier 0, etc.) */
    val tier: Int,
    /** Human-readable description */
    val description: String,
    /** Simple names of handlers in this tier */
    val handlers: List<String>,
    /** Number of handlers in this tier */
    val count: Int
)

/**
 * Overall dependency statistics.
 */
@Serializable
data class DependencyStats(
    /** Total handlers analyzed */
    val totalHandlers: Int,
    /** Total unique dependencies (edges in the graph) */
    val totalDependencies: Int,
    /** Average dependencies per handler */
    val avgDependenciesPerHandler: Double,
    /** Maximum dependencies for any handler */
    val maxDependencies: Int,
    /** Number of circular dependencies detected */
    val cycleCount: Int
)

// ============================================================================
// Graph Data Structures (for DOT output)
// ============================================================================

/**
 * A node in the dependency graph.
 */
@Serializable
data class DependencyNode(
    /** Fully qualified class name */
    val fqn: String,
    /** Simple class name (for display) */
    val label: String,
    /** Classification of this class */
    val type: ClassType,
    /** Complexity tier (null for non-handlers) */
    val complexity: ComplexityTier?,
    /** Number of classes that depend on this */
    val inDegree: Int,
    /** Number of classes this depends on */
    val outDegree: Int
)

/**
 * An edge in the dependency graph.
 */
@Serializable
data class DependencyEdge(
    /** Source class FQN */
    val source: String,
    /** Target class FQN (the dependency) */
    val target: String,
    /** Type of dependency */
    val type: DependencyEdgeType
)

/**
 * How one class depends on another.
 */
@Serializable
enum class DependencyEdgeType {
    /** Field injection or direct field reference */
    FIELD,
    /** Constructor parameter */
    CONSTRUCTOR,
    /** Method parameter */
    METHOD_PARAM,
    /** Extends (inheritance) */
    EXTENDS,
    /** Implements (interface) */
    IMPLEMENTS
}

/**
 * Full dependency graph for visualization.
 */
@Serializable
data class DependencyGraph(
    /** All nodes in the graph */
    val nodes: List<DependencyNode>,
    /** All edges in the graph */
    val edges: List<DependencyEdge>,
    /** Detected cycles */
    val cycles: List<DependencyCycle>,
    /** Is the graph acyclic? */
    val isAcyclic: Boolean
)

// ============================================================================
// API Response Wrappers
// ============================================================================

@Serializable
data class DependencyAnalysisResponse(
    val analysis: DependencyAnalysis
)

@Serializable
data class FoundationClassesResponse(
    val foundationClasses: List<FoundationClass>,
    val count: Int
)

@Serializable
data class QuickWinsResponse(
    val quickWins: List<QuickWinHandler>,
    val count: Int
)

@Serializable
data class DependencyGraphResponse(
    val graph: DependencyGraph
)

package codelens.core.model

import kotlinx.serialization.Serializable

// Models for the general, project-wide dependency graph and its "foundation"
// (most depended-on) view. Framework-agnostic: nodes are project classes and
// edges are project-to-project dependencies derived from class signatures (no
// handler / framework classification, no complexity scoring).

/** A node in the project dependency graph. */
@Serializable
data class GraphNode(
    /** Fully qualified class name. */
    val fqn: String,
    /** Simple class name. */
    val simpleName: String,
    /** Package name. */
    val packageName: String,
    /** Number of project classes that depend on this one. */
    val inDegree: Int,
    /** Number of project classes this one depends on. */
    val outDegree: Int,
)

/** A directed edge: [source] depends on [target]. */
@Serializable
data class GraphEdge(
    /** Dependent class FQN. */
    val source: String,
    /** Dependency class FQN. */
    val target: String,
    /** The (strongest) kind of dependency between them. */
    val type: DependencyType,
)

/** The project-wide dependency graph. */
@Serializable
data class ProjectGraph(
    /** All project class nodes, sorted by FQN. */
    val nodes: List<GraphNode>,
    /** All project-to-project edges, sorted by (source, target). */
    val edges: List<GraphEdge>,
    /** Number of nodes. */
    val nodeCount: Int,
    /** Number of edges. */
    val edgeCount: Int,
)

/** A "foundation" class: one that many other project classes depend on. */
@Serializable
data class FoundationClass(
    /** Fully qualified class name. */
    val fqn: String,
    /** Simple class name. */
    val simpleName: String,
    /** Package name. */
    val packageName: String,
    /** Number of project classes that depend on this one (its in-degree). */
    val dependentCount: Int,
    /** FQNs of the project classes that depend on this one, sorted. */
    val dependents: List<String>,
)

/** Response for the foundation endpoint. */
@Serializable
data class FoundationResponse(
    /** Foundation classes, most depended-on first. */
    val foundationClasses: List<FoundationClass>,
    /** Number of foundation classes. */
    val count: Int,
)

package codelens.classgraph.ratpack

import codelens.classgraph.ClassGraphProvider
import codelens.core.model.ClassInfo
import codelens.core.model.ClassSource
import codelens.core.model.ratpack.*
import org.slf4j.LoggerFactory

/**
 * Analyzes project-wide dependencies between handlers and services.
 *
 * Provides:
 * - Foundation class detection (most depended-on classes)
 * - Quick win identification (handlers with few dependencies)
 * - Circular dependency detection
 * - Handler tier grouping by dependency depth
 * - DOT graph generation for visualization
 */
class DependencyAnalyzer(
    private val classGraphProvider: ClassGraphProvider,
    // Optional parameters for testing
    ratpackDetectorOverride: RatpackDetector? = null,
    complexityCalculatorOverride: ComplexityCalculator? = null,
) {
    private val logger = LoggerFactory.getLogger(DependencyAnalyzer::class.java)

    private val ratpackDetector = ratpackDetectorOverride ?: RatpackDetector(classGraphProvider)
    private val complexityCalculator = complexityCalculatorOverride ?: ComplexityCalculator(classGraphProvider)

    companion object {
        // Minimum dependents to be considered a "foundation" class
        private const val FOUNDATION_MIN_DEPENDENTS = 3

        // Maximum dependencies for a "quick win" handler
        private const val QUICK_WIN_MAX_DEPS = 1
    }

    /**
     * Perform full dependency analysis for all handlers.
     */
    fun analyze(): DependencyAnalysis {
        val handlers = ratpackDetector.findAllHandlers(includeLibraries = false)
        val handlerFqns = handlers.map { it.fqn }.toSet()

        // Build the dependency graph
        val graph = buildDependencyGraph(handlerFqns)

        // Find foundation classes
        val foundationClasses = findFoundationClasses(graph, handlerFqns)

        // Find quick wins
        val quickWins = findQuickWins(handlers, graph)

        // Detect cycles
        val cycles = detectCycles(graph)

        // Group into tiers
        val tiers = groupIntoTiers(handlers, graph)

        // Calculate stats
        val stats = calculateStats(handlers, graph, cycles)

        return DependencyAnalysis(
            foundationClasses = foundationClasses,
            quickWins = quickWins,
            cycles = cycles,
            handlerTiers = tiers,
            stats = stats,
        )
    }

    /**
     * Get just the foundation classes.
     */
    fun getFoundationClasses(): List<FoundationClass> {
        val handlers = ratpackDetector.findAllHandlers(includeLibraries = false)
        val handlerFqns = handlers.map { it.fqn }.toSet()
        val graph = buildDependencyGraph(handlerFqns)
        return findFoundationClasses(graph, handlerFqns)
    }

    /**
     * Get just the quick wins.
     */
    fun getQuickWins(): List<QuickWinHandler> {
        val handlers = ratpackDetector.findAllHandlers(includeLibraries = false)
        val handlerFqns = handlers.map { it.fqn }.toSet()
        val graph = buildDependencyGraph(handlerFqns)
        return findQuickWins(handlers, graph)
    }

    /**
     * Get the full dependency graph for visualization.
     */
    fun getDependencyGraph(): DependencyGraph {
        val handlers = ratpackDetector.findAllHandlers(includeLibraries = false)
        val handlerFqns = handlers.map { it.fqn }.toSet()
        val graph = buildDependencyGraph(handlerFqns)
        val cycles = detectCycles(graph)

        return buildVisualGraph(graph, handlerFqns, cycles)
    }

    /**
     * Generate DOT format for Graphviz visualization.
     */
    fun toDotFormat(): String {
        val graph = getDependencyGraph()
        return generateDotFormat(graph)
    }

    // ========================================================================
    // Graph Building
    // ========================================================================

    /**
     * Internal representation of the dependency graph.
     */
    private data class InternalGraph(
        /** All nodes in the graph (FQNs) */
        val nodes: Set<String>,
        /** Adjacency list: source -> set of dependencies */
        val edges: Map<String, Set<String>>,
        /** Reverse adjacency: target -> set of dependents */
        val reverseEdges: Map<String, Set<String>>,
        /** Edge metadata */
        val edgeTypes: Map<Pair<String, String>, DependencyEdgeType>,
    )

    /**
     * Build the dependency graph from handlers.
     */
    private fun buildDependencyGraph(handlerFqns: Set<String>): InternalGraph {
        val nodes = mutableSetOf<String>()
        val edges = mutableMapOf<String, MutableSet<String>>()
        val reverseEdges = mutableMapOf<String, MutableSet<String>>()
        val edgeTypes = mutableMapOf<Pair<String, String>, DependencyEdgeType>()

        // Start with all handlers as nodes
        nodes.addAll(handlerFqns)

        // Analyze each handler's dependencies
        for (handlerFqn in handlerFqns) {
            val classInfo = classGraphProvider.getClass(handlerFqn) ?: continue
            edges[handlerFqn] = mutableSetOf()

            // Find project-level dependencies
            val deps = findProjectDependencies(classInfo)

            for ((depFqn, edgeType) in deps) {
                // Add the dependency as a node (even if not a handler)
                nodes.add(depFqn)
                edges[handlerFqn]!!.add(depFqn)

                // Update reverse edges
                reverseEdges.getOrPut(depFqn) { mutableSetOf() }.add(handlerFqn)

                // Store edge type
                edgeTypes[handlerFqn to depFqn] = edgeType
            }
        }

        return InternalGraph(
            nodes = nodes,
            edges = edges,
            reverseEdges = reverseEdges,
            edgeTypes = edgeTypes,
        )
    }

    /**
     * Find all project-level dependencies for a class.
     */
    private fun findProjectDependencies(classInfo: ClassInfo): List<Pair<String, DependencyEdgeType>> {
        val deps = mutableListOf<Pair<String, DependencyEdgeType>>()

        // Check constructor parameters (for final fields - likely injected)
        for (field in classInfo.fields) {
            if (field.isFinal && !field.isStatic) {
                val fieldType = extractBaseType(field.type)
                val fieldClass = classGraphProvider.getClass(fieldType)
                if (fieldClass != null && fieldClass.source == ClassSource.PROJECT) {
                    deps.add(fieldType to DependencyEdgeType.CONSTRUCTOR)
                }
            }
        }

        // Check non-final fields (potentially field-injected)
        for (field in classInfo.fields) {
            if (!field.isFinal && !field.isStatic) {
                val fieldType = extractBaseType(field.type)
                val fieldClass = classGraphProvider.getClass(fieldType)
                if (fieldClass != null && fieldClass.source == ClassSource.PROJECT) {
                    if (fieldType to DependencyEdgeType.CONSTRUCTOR !in deps) {
                        deps.add(fieldType to DependencyEdgeType.FIELD)
                    }
                }
            }
        }

        // Check superclass
        classInfo.superclass?.let { superclass ->
            val superType = extractBaseType(superclass)
            val superClass = classGraphProvider.getClass(superType)
            if (superClass != null && superClass.source == ClassSource.PROJECT) {
                deps.add(superType to DependencyEdgeType.EXTENDS)
            }
        }

        // Check interfaces
        for (iface in classInfo.interfaces) {
            val ifaceType = extractBaseType(iface)
            val ifaceClass = classGraphProvider.getClass(ifaceType)
            if (ifaceClass != null && ifaceClass.source == ClassSource.PROJECT) {
                deps.add(ifaceType to DependencyEdgeType.IMPLEMENTS)
            }
        }

        return deps.distinctBy { it.first }
    }

    /**
     * Extract base type from potentially generic type string.
     */
    private fun extractBaseType(type: String): String = type.substringBefore("<").substringBefore("[").trim()

    // ========================================================================
    // Foundation Classes
    // ========================================================================

    private fun findFoundationClasses(
        graph: InternalGraph,
        handlerFqns: Set<String>,
    ): List<FoundationClass> {
        val foundationClasses = mutableListOf<FoundationClass>()

        // Find classes with many handler dependents
        for ((fqn, dependents) in graph.reverseEdges) {
            // Only count handler dependents
            val handlerDependents = dependents.filter { it in handlerFqns }

            if (handlerDependents.size >= FOUNDATION_MIN_DEPENDENTS) {
                val classInfo = classGraphProvider.getClass(fqn)
                val classType = classifyClassType(fqn, classInfo)

                foundationClasses.add(
                    FoundationClass(
                        fqn = fqn,
                        simpleName = classInfo?.name?.simpleName ?: fqn.substringAfterLast("."),
                        type = classType,
                        dependentCount = handlerDependents.size,
                        dependentHandlers =
                            handlerDependents
                                .map {
                                    classGraphProvider.getClass(it)?.name?.simpleName ?: it.substringAfterLast(".")
                                }.sorted(),
                    ),
                )
            }
        }

        return foundationClasses.sortedByDescending { it.dependentCount }
    }

    /**
     * Classify a class type based on naming conventions.
     */
    private fun classifyClassType(
        fqn: String,
        classInfo: ClassInfo?,
    ): ClassType {
        val simpleName = classInfo?.name?.simpleName ?: fqn.substringAfterLast(".")
        val lowerName = simpleName.lowercase()
        val packageName = classInfo?.name?.packageName ?: ""

        return when {
            // Check if it's a Ratpack handler
            classInfo?.interfaces?.any { it == "ratpack.handling.Handler" } == true -> ClassType.HANDLER
            lowerName.endsWith("handler") -> ClassType.HANDLER

            // Check for repository/DAO
            lowerName.endsWith("repository") -> ClassType.REPOSITORY
            lowerName.endsWith("dao") -> ClassType.REPOSITORY
            packageName.contains("repository") -> ClassType.REPOSITORY
            packageName.contains("dao") -> ClassType.REPOSITORY

            // Check for service
            lowerName.endsWith("service") -> ClassType.SERVICE
            packageName.contains("service") -> ClassType.SERVICE

            // Check for utility
            lowerName.endsWith("util") -> ClassType.UTILITY
            lowerName.endsWith("utils") -> ClassType.UTILITY
            lowerName.endsWith("helper") -> ClassType.UTILITY
            packageName.contains("util") -> ClassType.UTILITY

            else -> ClassType.OTHER
        }
    }

    // ========================================================================
    // Quick Wins
    // ========================================================================

    private fun findQuickWins(
        handlers: List<HandlerSummary>,
        graph: InternalGraph,
    ): List<QuickWinHandler> {
        val quickWins = mutableListOf<QuickWinHandler>()

        for (handler in handlers) {
            val deps = graph.edges[handler.fqn] ?: emptySet()

            // Quick win: few dependencies and low complexity
            if (deps.size <= QUICK_WIN_MAX_DEPS &&
                handler.complexityTier in listOf(ComplexityTier.LOW, ComplexityTier.MEDIUM)
            ) {
                quickWins.add(
                    QuickWinHandler(
                        fqn = handler.fqn,
                        simpleName = handler.simpleName,
                        dependencyCount = deps.size,
                        complexity = handler.complexityTier,
                    ),
                )
            }
        }

        // Sort by dependency count, then by complexity
        return quickWins.sortedWith(
            compareBy({ it.dependencyCount }, { it.complexity.ordinal }),
        )
    }

    // ========================================================================
    // Cycle Detection
    // ========================================================================

    /**
     * Detect cycles in the dependency graph using DFS.
     */
    private fun detectCycles(graph: InternalGraph): List<DependencyCycle> {
        val cycles = mutableListOf<DependencyCycle>()
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()
        val path = mutableListOf<String>()

        fun dfs(node: String) {
            visited.add(node)
            recursionStack.add(node)
            path.add(node)

            for (neighbor in graph.edges[node] ?: emptySet()) {
                if (neighbor !in visited) {
                    dfs(neighbor)
                } else if (neighbor in recursionStack) {
                    // Found a cycle - extract it from path
                    val cycleStart = path.indexOf(neighbor)
                    if (cycleStart >= 0) {
                        val cyclePath = path.subList(cycleStart, path.size).toMutableList()
                        cyclePath.add(neighbor) // Close the cycle

                        // Create description
                        val simpleNames =
                            cyclePath.map {
                                classGraphProvider.getClass(it)?.name?.simpleName ?: it.substringAfterLast(".")
                            }
                        val description = simpleNames.joinToString(" -> ")

                        // Avoid duplicate cycles (same cycle starting from different nodes)
                        val normalizedCycle = cyclePath.dropLast(1).sorted()
                        if (cycles.none { it.classes.sorted() == normalizedCycle }) {
                            cycles.add(
                                DependencyCycle(
                                    classes = cyclePath,
                                    description = description,
                                ),
                            )
                        }
                    }
                }
            }

            path.removeAt(path.size - 1)
            recursionStack.remove(node)
        }

        // Run DFS from each node
        for (node in graph.nodes) {
            if (node !in visited) {
                dfs(node)
            }
        }

        return cycles
    }

    // ========================================================================
    // Tier Grouping
    // ========================================================================

    /**
     * Group handlers into tiers based on dependency depth.
     *
     * Tier 0: No project dependencies
     * Tier 1: Depends only on non-handlers or tier 0 handlers
     * Tier N: Depends on tier N-1 or lower handlers
     */
    private fun groupIntoTiers(
        handlers: List<HandlerSummary>,
        graph: InternalGraph,
    ): List<DependencyTier> {
        val handlerFqns = handlers.map { it.fqn }.toSet()
        val tierAssignments = mutableMapOf<String, Int>()
        val maxIterations = handlers.size + 1 // Prevent infinite loops

        // Initialize: handlers with no handler dependencies are tier 0
        for (handler in handlers) {
            val deps = graph.edges[handler.fqn] ?: emptySet()
            val handlerDeps = deps.filter { it in handlerFqns }

            if (handlerDeps.isEmpty()) {
                tierAssignments[handler.fqn] = 0
            }
        }

        // Iteratively assign tiers
        var changed = true
        var iteration = 0
        while (changed && iteration < maxIterations) {
            changed = false
            iteration++

            for (handler in handlers) {
                if (handler.fqn in tierAssignments) continue

                val deps = graph.edges[handler.fqn] ?: emptySet()
                val handlerDeps = deps.filter { it in handlerFqns }

                // Check if all handler dependencies have been assigned
                if (handlerDeps.all { it in tierAssignments }) {
                    val maxDepTier = handlerDeps.maxOfOrNull { tierAssignments[it]!! } ?: -1
                    tierAssignments[handler.fqn] = maxDepTier + 1
                    changed = true
                }
            }
        }

        // Assign remaining handlers (in cycles) to a high tier
        val maxTier = tierAssignments.values.maxOrNull() ?: 0
        for (handler in handlers) {
            if (handler.fqn !in tierAssignments) {
                tierAssignments[handler.fqn] = maxTier + 1
            }
        }

        // Group by tier
        val tierGroups = tierAssignments.entries.groupBy { it.value }

        return tierGroups
            .map { (tier, entries) ->
                val description =
                    when (tier) {
                        0 -> "No handler dependencies"
                        1 -> "Depends only on Tier 0 handlers"
                        else -> "Depends on Tier ${tier - 1} or lower handlers"
                    }

                DependencyTier(
                    tier = tier,
                    description = description,
                    handlers =
                        entries
                            .map {
                                classGraphProvider.getClass(it.key)?.name?.simpleName ?: it.key.substringAfterLast(".")
                            }.sorted(),
                    count = entries.size,
                )
            }.sortedBy { it.tier }
    }

    // ========================================================================
    // Statistics
    // ========================================================================

    private fun calculateStats(
        handlers: List<HandlerSummary>,
        graph: InternalGraph,
        cycles: List<DependencyCycle>,
    ): DependencyStats {
        val totalDeps = graph.edges.values.sumOf { it.size }
        val maxDeps = graph.edges.values.maxOfOrNull { it.size } ?: 0
        val avgDeps =
            if (handlers.isNotEmpty()) {
                totalDeps.toDouble() / handlers.size
            } else {
                0.0
            }

        return DependencyStats(
            totalHandlers = handlers.size,
            totalDependencies = totalDeps,
            avgDependenciesPerHandler = kotlin.math.round(avgDeps * 100) / 100,
            maxDependencies = maxDeps,
            cycleCount = cycles.size,
        )
    }

    // ========================================================================
    // DOT Format Generation
    // ========================================================================

    private fun buildVisualGraph(
        graph: InternalGraph,
        handlerFqns: Set<String>,
        cycles: List<DependencyCycle>,
    ): DependencyGraph {
        val nodes =
            graph.nodes.map { fqn ->
                val classInfo = classGraphProvider.getClass(fqn)
                val inDegree = graph.reverseEdges[fqn]?.size ?: 0
                val outDegree = graph.edges[fqn]?.size ?: 0

                val complexity =
                    if (fqn in handlerFqns) {
                        classInfo?.let {
                            complexityCalculator.calculate(fqn).tier
                        }
                    } else {
                        null
                    }

                DependencyNode(
                    fqn = fqn,
                    label = classInfo?.name?.simpleName ?: fqn.substringAfterLast("."),
                    type = classifyClassType(fqn, classInfo),
                    complexity = complexity,
                    inDegree = inDegree,
                    outDegree = outDegree,
                )
            }

        val edges =
            graph.edgeTypes.map { (pair, type) ->
                DependencyEdge(
                    source = pair.first,
                    target = pair.second,
                    type = type,
                )
            }

        return DependencyGraph(
            nodes = nodes,
            edges = edges,
            cycles = cycles,
            isAcyclic = cycles.isEmpty(),
        )
    }

    /**
     * Escape special characters for DOT format strings.
     */
    private fun escapeDotString(s: String): String =
        s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")

    private fun generateDotFormat(graph: DependencyGraph): String =
        buildString {
            appendLine("digraph Dependencies {")
            appendLine("  rankdir=TB;")
            appendLine("  node [shape=box, style=rounded];")
            appendLine()

            // Define node colors based on type and complexity
            for (node in graph.nodes) {
                val color =
                    when {
                        node.type == ClassType.HANDLER ->
                            when (node.complexity) {
                                ComplexityTier.LOW -> "lightgreen"
                                ComplexityTier.MEDIUM -> "lightyellow"
                                ComplexityTier.HIGH -> "orange"
                                ComplexityTier.CRITICAL -> "lightcoral"
                                null -> "lightblue"
                            }
                        node.type == ClassType.SERVICE -> "lightblue"
                        node.type == ClassType.REPOSITORY -> "lavender"
                        else -> "lightgray"
                    }

                val shape =
                    when (node.type) {
                        ClassType.HANDLER -> "box"
                        ClassType.SERVICE -> "ellipse"
                        ClassType.REPOSITORY -> "cylinder"
                        else -> "box"
                    }

                val escapedFqn = escapeDotString(node.fqn)
                val escapedLabel = escapeDotString(node.label)
                appendLine("  \"$escapedFqn\" [label=\"$escapedLabel\", fillcolor=$color, style=filled, shape=$shape];")
            }

            appendLine()

            // Define edges
            for (edge in graph.edges) {
                val style =
                    when (edge.type) {
                        DependencyEdgeType.EXTENDS -> "bold"
                        DependencyEdgeType.IMPLEMENTS -> "dashed"
                        else -> "solid"
                    }
                val escapedSource = escapeDotString(edge.source)
                val escapedTarget = escapeDotString(edge.target)
                appendLine("  \"$escapedSource\" -> \"$escapedTarget\" [style=$style];")
            }

            appendLine("}")
        }
}

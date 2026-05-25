package codelens.classgraph

import codelens.core.model.*

/**
 * Analyzes bidirectional dependencies between classes.
 *
 * Finds both outgoing dependencies (what the target class depends on) and
 * incoming dependencies (what depends on the target class).
 */
class DependencyAnalyzer(
    private val classes: Map<String, ClassInfo>,
) {
    /**
     * Analyzes dependencies for the given class.
     *
     * @param fqn The fully qualified name of the class to analyze
     * @param includeLibraries Whether to include library classes in results
     * @return Pair of (outgoing dependencies, incoming dependencies)
     */
    fun analyze(
        fqn: String,
        includeLibraries: Boolean,
    ): Pair<List<DependencyInfo>, List<DependencyInfo>> {
        val targetClass = classes[fqn] ?: return Pair(emptyList(), emptyList())

        val outgoing = mutableListOf<DependencyInfo>()
        val incoming = mutableListOf<DependencyInfo>()

        // Outgoing dependencies (what this class depends on)
        collectOutgoingDependencies(targetClass, fqn, includeLibraries, outgoing)

        // Incoming dependencies (what depends on this class)
        collectIncomingDependencies(fqn, includeLibraries, incoming)

        // Remove duplicates and sort
        return Pair(
            outgoing.distinctBy { "${it.classFqn}:${it.dependencyType}:${it.location}" }.sortedBy { it.classFqn },
            incoming.distinctBy { "${it.classFqn}:${it.dependencyType}:${it.location}" }.sortedBy { it.classFqn },
        )
    }

    private fun collectOutgoingDependencies(
        targetClass: ClassInfo,
        fqn: String,
        includeLibraries: Boolean,
        outgoing: MutableList<DependencyInfo>,
    ) {
        // 1. Superclass (base + any generic type arguments, e.g. Foo in `extends Base<Foo>`)
        targetClass.superclass?.let { superclass ->
            if (superclass != "java.lang.Object") {
                addOutgoing(outgoing, superclass, DependencyType.EXTENDS, fqn, includeLibraries)
            }
        }
        targetClass.superclassTypeArgs.forEach { argFqn ->
            addOutgoing(outgoing, argFqn, DependencyType.EXTENDS, fqn, includeLibraries)
        }

        // 2. Implemented interfaces (base + generic type arguments)
        targetClass.interfaces.forEach { iface ->
            addOutgoing(outgoing, iface, DependencyType.IMPLEMENTS, fqn, includeLibraries)
        }
        targetClass.interfaceTypeArgs.forEach { argFqn ->
            addOutgoing(outgoing, argFqn, DependencyType.IMPLEMENTS, fqn, includeLibraries)
        }

        // 3. Field types (container + type arguments)
        targetClass.fields.forEach { field ->
            referencedTypes(field.type, field.typeRefs).forEach { fieldType ->
                addOutgoing(outgoing, fieldType, DependencyType.FIELD_TYPE, fqn, includeLibraries, field.name)
            }
        }

        // 4. Method return types and parameters (container + type arguments)
        targetClass.methods.filter { !it.isSynthetic }.forEach { method ->
            referencedTypes(method.returnType, method.returnTypeRefs).forEach { returnType ->
                addOutgoing(outgoing, returnType, DependencyType.METHOD_RETURN_TYPE, fqn, includeLibraries, "${method.name}()")
            }
            method.parameters.forEach { param ->
                referencedTypes(param.type, param.typeRefs).forEach { paramType ->
                    addOutgoing(outgoing, paramType, DependencyType.METHOD_PARAMETER, fqn, includeLibraries, "${method.name}()")
                }
            }
        }
    }

    /**
     * Adds an outgoing dependency on [classFqn] unless it is the analyzed class
     * itself or (when [includeLibraries] is false) a non-project class.
     */
    private fun addOutgoing(
        outgoing: MutableList<DependencyInfo>,
        classFqn: String,
        dependencyType: DependencyType,
        selfFqn: String,
        includeLibraries: Boolean,
        location: String? = null,
    ) {
        if (classFqn == selfFqn) return
        val info = classes[classFqn]
        if (includeLibraries || info?.source == ClassSource.PROJECT) {
            outgoing.add(
                DependencyInfo(
                    classFqn = classFqn,
                    dependencyType = dependencyType,
                    source = info?.source ?: ClassSource.LIBRARY,
                    location = location,
                ),
            )
        }
    }

    private fun collectIncomingDependencies(
        fqn: String,
        includeLibraries: Boolean,
        incoming: MutableList<DependencyInfo>,
    ) {
        for (classInfo in classes.values) {
            if (classInfo.name.fqn == fqn) continue
            if (!includeLibraries && classInfo.source != ClassSource.PROJECT) continue

            // Check superclass (base + generic type arguments)
            if (classInfo.superclass == fqn || fqn in classInfo.superclassTypeArgs) {
                incoming.add(
                    DependencyInfo(
                        classFqn = classInfo.name.fqn,
                        dependencyType = DependencyType.EXTENDS,
                        source = classInfo.source,
                    ),
                )
            }

            // Check interfaces (base + generic type arguments)
            if (classInfo.interfaces.contains(fqn) || fqn in classInfo.interfaceTypeArgs) {
                incoming.add(
                    DependencyInfo(
                        classFqn = classInfo.name.fqn,
                        dependencyType = DependencyType.IMPLEMENTS,
                        source = classInfo.source,
                    ),
                )
            }

            // Check fields (container + type arguments)
            classInfo.fields.forEach { field ->
                if (fqn in referencedTypes(field.type, field.typeRefs)) {
                    incoming.add(
                        DependencyInfo(
                            classFqn = classInfo.name.fqn,
                            dependencyType = DependencyType.FIELD_TYPE,
                            source = classInfo.source,
                            location = field.name,
                        ),
                    )
                }
            }

            // Check methods (return + parameter containers and their type arguments)
            classInfo.methods.filter { !it.isSynthetic }.forEach { method ->
                if (fqn in referencedTypes(method.returnType, method.returnTypeRefs)) {
                    incoming.add(
                        DependencyInfo(
                            classFqn = classInfo.name.fqn,
                            dependencyType = DependencyType.METHOD_RETURN_TYPE,
                            source = classInfo.source,
                            location = "${method.name}()",
                        ),
                    )
                }
                method.parameters.forEach { param ->
                    if (fqn in referencedTypes(param.type, param.typeRefs)) {
                        incoming.add(
                            DependencyInfo(
                                classFqn = classInfo.name.fqn,
                                dependencyType = DependencyType.METHOD_PARAMETER,
                                source = classInfo.source,
                                location = "${method.name}()",
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * The class FQNs a (possibly generic) type references. [typeRefs] holds the
     * FQNs captured from the bytecode signature (container + type arguments);
     * for hand-built [ClassInfo] that list is empty, so the erased base type is
     * also parsed from the [type] display string. The union keeps results
     * correct whether or not a generic signature was captured. Type variables
     * and primitives contribute nothing.
     */
    private fun referencedTypes(
        type: String,
        typeRefs: List<String>,
    ): Set<String> {
        val refs = LinkedHashSet(typeRefs)
        extractTypeFqn(type)?.let { refs.add(it) }
        return refs
    }

    /**
     * Extracts the base type FQN from a type descriptor, handling arrays and generics.
     */
    private fun extractTypeFqn(type: String): String? {
        // Remove array brackets
        var cleanType = type.replace("[]", "").trim()

        // Handle generics - extract base type
        if (cleanType.contains("<")) {
            cleanType = cleanType.substringBefore("<")
        }

        // Skip primitive types
        if (cleanType in listOf("void", "int", "long", "double", "float", "boolean", "char", "byte", "short")) {
            return null
        }

        return cleanType.ifBlank { null }
    }

    /**
     * Build the project-wide dependency graph: nodes are project classes, edges
     * are project-to-project dependencies (one edge per (source, target) pair,
     * labelled with the strongest dependency kind). Library/JDK targets are
     * excluded so the graph reflects internal structure.
     */
    fun buildProjectGraph(): ProjectGraph {
        val projectClasses = classes.values.filter { it.source == ClassSource.PROJECT }

        // Collapse the per-class dependency facts into one edge per (source, target),
        // keeping the highest-priority dependency type (EXTENDS < IMPLEMENTS < ...).
        val edgeType = LinkedHashMap<Pair<String, String>, DependencyType>()
        for (classInfo in projectClasses) {
            val (outgoing, _) = analyze(classInfo.name.fqn, includeLibraries = false)
            for (dep in outgoing) {
                if (dep.classFqn == classInfo.name.fqn) continue
                val key = classInfo.name.fqn to dep.classFqn
                val existing = edgeType[key]
                if (existing == null || dep.dependencyType.ordinal < existing.ordinal) {
                    edgeType[key] = dep.dependencyType
                }
            }
        }

        val inDegree = HashMap<String, Int>()
        val outDegree = HashMap<String, Int>()
        for ((source, target) in edgeType.keys) {
            outDegree[source] = (outDegree[source] ?: 0) + 1
            inDegree[target] = (inDegree[target] ?: 0) + 1
        }

        val nodes =
            projectClasses
                .map { ci ->
                    GraphNode(
                        fqn = ci.name.fqn,
                        simpleName = ci.name.simpleName,
                        packageName = ci.name.packageName,
                        inDegree = inDegree[ci.name.fqn] ?: 0,
                        outDegree = outDegree[ci.name.fqn] ?: 0,
                    )
                }.sortedBy { it.fqn }

        val edges =
            edgeType
                .map { (key, type) -> GraphEdge(source = key.first, target = key.second, type = type) }
                .sortedWith(compareBy({ it.source }, { it.target }))

        return ProjectGraph(nodes = nodes, edges = edges, nodeCount = nodes.size, edgeCount = edges.size)
    }

    /**
     * Find "foundation" classes — those with at least [minDependents] project
     * classes depending on them — most depended-on first.
     */
    fun foundationClasses(minDependents: Int): List<FoundationClass> {
        val graph = buildProjectGraph()
        val dependentsByTarget = graph.edges.groupBy({ it.target }, { it.source })

        return graph.nodes
            .filter { it.inDegree >= minDependents }
            .map { node ->
                FoundationClass(
                    fqn = node.fqn,
                    simpleName = node.simpleName,
                    packageName = node.packageName,
                    dependentCount = node.inDegree,
                    dependents = (dependentsByTarget[node.fqn] ?: emptyList()).distinct().sorted(),
                )
            }.sortedWith(compareByDescending<FoundationClass> { it.dependentCount }.thenBy { it.fqn })
    }
}

/**
 * Render a [ProjectGraph] as Graphviz DOT. Node/edge order follows the graph's
 * (already deterministic) ordering so the output is stable.
 */
fun projectGraphToDot(graph: ProjectGraph): String =
    buildString {
        appendLine("digraph dependencies {")
        appendLine("  rankdir=LR;")
        appendLine("  node [shape=box, style=rounded];")
        appendLine()
        for (node in graph.nodes) {
            appendLine("  \"${escapeDot(node.fqn)}\" [label=\"${escapeDot(node.simpleName)}\"];")
        }
        appendLine()
        for (edge in graph.edges) {
            val style =
                when (edge.type) {
                    DependencyType.EXTENDS -> "bold"
                    DependencyType.IMPLEMENTS -> "dashed"
                    else -> "solid"
                }
            appendLine("  \"${escapeDot(edge.source)}\" -> \"${escapeDot(edge.target)}\" [style=$style];")
        }
        appendLine("}")
    }

private fun escapeDot(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

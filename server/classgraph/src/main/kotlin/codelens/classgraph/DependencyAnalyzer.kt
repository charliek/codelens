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
        // 1. Superclass
        targetClass.superclass?.let { superclass ->
            if (superclass != "java.lang.Object") {
                val superclassInfo = classes[superclass]
                if (includeLibraries || superclassInfo?.source == ClassSource.PROJECT) {
                    outgoing.add(
                        DependencyInfo(
                            classFqn = superclass,
                            dependencyType = DependencyType.EXTENDS,
                            source = superclassInfo?.source ?: ClassSource.LIBRARY,
                        ),
                    )
                }
            }
        }

        // 2. Implemented interfaces
        targetClass.interfaces.forEach { iface ->
            val ifaceInfo = classes[iface]
            if (includeLibraries || ifaceInfo?.source == ClassSource.PROJECT) {
                outgoing.add(
                    DependencyInfo(
                        classFqn = iface,
                        dependencyType = DependencyType.IMPLEMENTS,
                        source = ifaceInfo?.source ?: ClassSource.LIBRARY,
                    ),
                )
            }
        }

        // 3. Field types
        targetClass.fields.forEach { field ->
            val fieldType = extractTypeFqn(field.type)
            if (fieldType != null && fieldType != fqn) {
                val fieldTypeInfo = classes[fieldType]
                if (includeLibraries || fieldTypeInfo?.source == ClassSource.PROJECT) {
                    outgoing.add(
                        DependencyInfo(
                            classFqn = fieldType,
                            dependencyType = DependencyType.FIELD_TYPE,
                            source = fieldTypeInfo?.source ?: ClassSource.LIBRARY,
                            location = field.name,
                        ),
                    )
                }
            }
        }

        // 4. Method return types and parameters
        targetClass.methods.filter { !it.isSynthetic }.forEach { method ->
            val returnType = extractTypeFqn(method.returnType)
            if (returnType != null && returnType != fqn && returnType != "void") {
                val returnTypeInfo = classes[returnType]
                if (includeLibraries || returnTypeInfo?.source == ClassSource.PROJECT) {
                    outgoing.add(
                        DependencyInfo(
                            classFqn = returnType,
                            dependencyType = DependencyType.METHOD_RETURN_TYPE,
                            source = returnTypeInfo?.source ?: ClassSource.LIBRARY,
                            location = "${method.name}()",
                        ),
                    )
                }
            }

            method.parameters.forEach { param ->
                val paramType = extractTypeFqn(param.type)
                if (paramType != null && paramType != fqn) {
                    val paramTypeInfo = classes[paramType]
                    if (includeLibraries || paramTypeInfo?.source == ClassSource.PROJECT) {
                        outgoing.add(
                            DependencyInfo(
                                classFqn = paramType,
                                dependencyType = DependencyType.METHOD_PARAMETER,
                                source = paramTypeInfo?.source ?: ClassSource.LIBRARY,
                                location = "${method.name}()",
                            ),
                        )
                    }
                }
            }
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

            // Check superclass
            if (classInfo.superclass == fqn) {
                incoming.add(
                    DependencyInfo(
                        classFqn = classInfo.name.fqn,
                        dependencyType = DependencyType.EXTENDS,
                        source = classInfo.source,
                    ),
                )
            }

            // Check interfaces
            if (classInfo.interfaces.contains(fqn)) {
                incoming.add(
                    DependencyInfo(
                        classFqn = classInfo.name.fqn,
                        dependencyType = DependencyType.IMPLEMENTS,
                        source = classInfo.source,
                    ),
                )
            }

            // Check fields
            classInfo.fields.forEach { field ->
                if (extractTypeFqn(field.type) == fqn) {
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

            // Check methods
            classInfo.methods.filter { !it.isSynthetic }.forEach { method ->
                if (extractTypeFqn(method.returnType) == fqn) {
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
                    if (extractTypeFqn(param.type) == fqn) {
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
}

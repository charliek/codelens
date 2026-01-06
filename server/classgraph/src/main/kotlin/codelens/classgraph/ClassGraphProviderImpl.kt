package codelens.classgraph

import codelens.core.model.*
import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo as CGClassInfo
import io.github.classgraph.FieldInfo as CGFieldInfo
import io.github.classgraph.MethodInfo as CGMethodInfo
import io.github.classgraph.AnnotationInfo as CGAnnotationInfo
import io.github.classgraph.ScanResult as CGScanResult
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementation of ClassGraphProvider using the ClassGraph library.
 */
class ClassGraphProviderImpl : ClassGraphProvider {
    private val logger = LoggerFactory.getLogger(ClassGraphProviderImpl::class.java)

    private val classes = ConcurrentHashMap<String, ClassInfo>()
    private var statistics: ScanStatistics? = null
    private var projectOutputDirs: Set<File> = emptySet()

    override fun scan(classpathEntries: List<File>, projectOutputDirs: Set<File>): ScanResult {
        logger.info("Starting ClassGraph scan with ${classpathEntries.size} classpath entries")
        val startTime = System.currentTimeMillis()

        this.projectOutputDirs = projectOutputDirs
        this.classes.clear()

        // Build the classpath string
        val classpathStr = classpathEntries
            .filter { it.exists() }
            .joinToString(File.pathSeparator) { it.absolutePath }

        if (classpathStr.isEmpty()) {
            logger.warn("No valid classpath entries found")
            val stats = createEmptyStatistics(startTime, "No classpath")
            this.statistics = stats
            return ScanResult(emptyMap(), stats, projectOutputDirs)
        }

        val projectOutputPaths = projectOutputDirs.map { it.absolutePath }.toSet()

        var cgScanResult: CGScanResult? = null
        try {
            cgScanResult = ClassGraph()
                .overrideClasspath(classpathStr)
                .enableAllInfo()
                .scan()

            logger.info("ClassGraph scan found ${cgScanResult.allClasses.size} classes")

            // Convert all classes
            for (cgClass in cgScanResult.allClasses) {
                try {
                    val classInfo = convertClassInfo(cgClass, projectOutputPaths)
                    classes[classInfo.name.fqn] = classInfo
                } catch (e: Exception) {
                    logger.warn("Failed to convert class ${cgClass.name}: ${e.message}")
                }
            }

            // Build statistics
            val stats = buildStatistics(startTime, classpathEntries.size, "ClassGraph")
            this.statistics = stats

            logger.info("Scan complete. ${classes.size} classes processed in ${System.currentTimeMillis() - startTime}ms")

            return ScanResult(classes.toMap(), stats, projectOutputDirs)
        } finally {
            cgScanResult?.close()
        }
    }

    override fun listClasses(filter: ClassFilter): List<ClassSummary> {
        val effectiveSource = filter.effectiveSourceFilter()

        return classes.values
            .asSequence()
            .filter { matchesFilter(it, filter, effectiveSource) }
            .map { it.toSummary() }
            .sortedBy { it.fqn }
            .toList()
    }

    override fun getClass(fqn: String): ClassInfo? {
        return classes[fqn]
    }

    override fun getStatistics(): ScanStatistics? {
        return statistics
    }

    override fun isScanned(): Boolean {
        return statistics != null
    }

    override fun getImplementations(fqn: String, includeLibraries: Boolean): Pair<List<ClassSummary>, List<ClassSummary>> {
        val targetClass = classes[fqn] ?: return Pair(emptyList(), emptyList())

        // Build a map of class/interface -> direct implementors/subclasses
        // Uses Set to prevent duplicates
        val directImplementorsMap = mutableMapOf<String, MutableSet<String>>()

        for (classInfo in classes.values) {
            // Skip based on source filter
            if (!includeLibraries && classInfo.source != ClassSource.PROJECT) continue

            // Track all interface implementations
            classInfo.interfaces.forEach { iface ->
                directImplementorsMap.getOrPut(iface) { mutableSetOf() }.add(classInfo.name.fqn)
            }

            // Track all superclass relationships
            classInfo.superclass?.let { superclass ->
                directImplementorsMap.getOrPut(superclass) { mutableSetOf() }.add(classInfo.name.fqn)
            }
        }

        // Get direct implementations of the target
        val directImplFqns = directImplementorsMap[fqn] ?: emptySet()
        val directImpls = directImplFqns.mapNotNull { implFqn ->
            classes[implFqn]?.toSummary()
        }

        // Find indirect implementations (BFS through the hierarchy)
        val visited = mutableSetOf(fqn)
        visited.addAll(directImplFqns)
        val queue = ArrayDeque(directImplFqns)
        val indirectImpls = mutableListOf<ClassSummary>()

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()

            directImplementorsMap[current]?.forEach { implFqn ->
                if (implFqn !in visited) {
                    visited.add(implFqn)
                    classes[implFqn]?.let { classInfo ->
                        indirectImpls.add(classInfo.toSummary())
                        queue.add(implFqn)
                    }
                }
            }
        }

        return Pair(
            directImpls.sortedBy { it.fqn },
            indirectImpls.sortedBy { it.fqn }
        )
    }

    override fun getHierarchy(fqn: String): HierarchyNode? {
        val targetClass = classes[fqn] ?: return null

        // Build parent chain
        val parentChain = buildParentChain(targetClass)

        // Build children tree
        val childrenTree = buildChildrenTree(fqn)

        // Build interface implementations
        val interfaceNodes = targetClass.interfaces.mapNotNull { ifaceFqn ->
            classes[ifaceFqn]?.let { ifaceInfo ->
                HierarchyNode(
                    classFqn = ifaceFqn,
                    simpleName = ifaceInfo.name.simpleName,
                    source = ifaceInfo.source,
                    isInterface = true
                )
            }
        }

        return HierarchyNode(
            classFqn = fqn,
            simpleName = targetClass.name.simpleName,
            source = targetClass.source,
            isInterface = targetClass.isInterface,
            parent = parentChain,
            interfaces = interfaceNodes,
            children = childrenTree
        )
    }

    private fun buildParentChain(classInfo: ClassInfo): HierarchyNode? {
        val superclassFqn = classInfo.superclass ?: return null

        // Handle java.lang.Object explicitly - it's the root of the hierarchy
        if (superclassFqn == "java.lang.Object") {
            return HierarchyNode(
                classFqn = "java.lang.Object",
                simpleName = "Object",
                source = ClassSource.JDK,
                isInterface = false,
                parent = null  // Object is the root
            )
        }

        val superclassInfo = classes[superclassFqn] ?: return HierarchyNode(
            classFqn = superclassFqn,
            simpleName = superclassFqn.substringAfterLast('.'),
            source = ClassSource.LIBRARY,
            isInterface = false,
            parent = null  // Can't determine further ancestry for unscanned classes
        )

        return HierarchyNode(
            classFqn = superclassFqn,
            simpleName = superclassInfo.name.simpleName,
            source = superclassInfo.source,
            isInterface = superclassInfo.isInterface,
            parent = buildParentChain(superclassInfo)
        )
    }

    private fun buildChildrenTree(fqn: String): List<HierarchyNode> {
        val children = mutableListOf<HierarchyNode>()

        for (classInfo in classes.values) {
            val isDirectChild = if (classes[fqn]?.isInterface == true) {
                classInfo.interfaces.contains(fqn)
            } else {
                classInfo.superclass == fqn
            }

            if (isDirectChild) {
                children.add(
                    HierarchyNode(
                        classFqn = classInfo.name.fqn,
                        simpleName = classInfo.name.simpleName,
                        source = classInfo.source,
                        isInterface = classInfo.isInterface,
                        children = buildChildrenTree(classInfo.name.fqn)
                    )
                )
            }
        }

        return children.sortedBy { it.classFqn }
    }

    override fun getDependencies(fqn: String, includeLibraries: Boolean): Pair<List<DependencyInfo>, List<DependencyInfo>> {
        val targetClass = classes[fqn] ?: return Pair(emptyList(), emptyList())

        val outgoing = mutableListOf<DependencyInfo>()
        val incoming = mutableListOf<DependencyInfo>()

        // Outgoing dependencies (what this class depends on)
        // 1. Superclass
        targetClass.superclass?.let { superclass ->
            if (superclass != "java.lang.Object") {
                val superclassInfo = classes[superclass]
                if (includeLibraries || superclassInfo?.source == ClassSource.PROJECT) {
                    outgoing.add(
                        DependencyInfo(
                            classFqn = superclass,
                            dependencyType = DependencyType.EXTENDS,
                            source = superclassInfo?.source ?: ClassSource.LIBRARY
                        )
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
                        source = ifaceInfo?.source ?: ClassSource.LIBRARY
                    )
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
                            location = field.name
                        )
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
                            location = "${method.name}()"
                        )
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
                                location = "${method.name}()"
                            )
                        )
                    }
                }
            }
        }

        // Incoming dependencies (what depends on this class)
        for (classInfo in classes.values) {
            if (classInfo.name.fqn == fqn) continue
            if (!includeLibraries && classInfo.source != ClassSource.PROJECT) continue

            // Check superclass
            if (classInfo.superclass == fqn) {
                incoming.add(
                    DependencyInfo(
                        classFqn = classInfo.name.fqn,
                        dependencyType = DependencyType.EXTENDS,
                        source = classInfo.source
                    )
                )
            }

            // Check interfaces
            if (classInfo.interfaces.contains(fqn)) {
                incoming.add(
                    DependencyInfo(
                        classFqn = classInfo.name.fqn,
                        dependencyType = DependencyType.IMPLEMENTS,
                        source = classInfo.source
                    )
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
                            location = field.name
                        )
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
                            location = "${method.name}()"
                        )
                    )
                }
                method.parameters.forEach { param ->
                    if (extractTypeFqn(param.type) == fqn) {
                        incoming.add(
                            DependencyInfo(
                                classFqn = classInfo.name.fqn,
                                dependencyType = DependencyType.METHOD_PARAMETER,
                                source = classInfo.source,
                                location = "${method.name}()"
                            )
                        )
                    }
                }
            }
        }

        // Remove duplicates and sort
        return Pair(
            outgoing.distinctBy { "${it.classFqn}:${it.dependencyType}:${it.location}" }.sortedBy { it.classFqn },
            incoming.distinctBy { "${it.classFqn}:${it.dependencyType}:${it.location}" }.sortedBy { it.classFqn }
        )
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

    override fun getAnnotationUsages(annotationFqn: String, includeLibraries: Boolean): List<ClassSummary> {
        return classes.values
            .asSequence()
            .filter { classInfo ->
                (includeLibraries || classInfo.source == ClassSource.PROJECT) &&
                    classInfo.annotations.any { it.type == annotationFqn }
            }
            .map { it.toSummary() }
            .sortedBy { it.fqn }
            .toList()
    }

    override fun searchMethods(filter: MethodFilter): List<MethodSearchResult> {
        val results = mutableListOf<MethodSearchResult>()

        for (classInfo in classes.values) {
            // Apply class-level filters
            if (!filter.includeLibraries && classInfo.source != ClassSource.PROJECT) continue

            filter.inClass?.let { classFqn ->
                if (classInfo.name.fqn != classFqn) return@searchMethods results
            }

            filter.inPackage?.let { packagePattern ->
                if (!matchesPattern(classInfo.name.packageName, packagePattern)) return@searchMethods results
            }

            // Search methods in this class
            for (method in classInfo.methods) {
                if (method.isSynthetic) continue

                var matches = true

                // Name pattern filter
                filter.namePattern?.let { pattern ->
                    if (!matchesPattern(method.name, pattern)) {
                        matches = false
                    }
                }

                // Return type filter
                filter.returnType?.let { returnType ->
                    val methodReturnType = extractTypeFqn(method.returnType)
                    if (methodReturnType != returnType && !method.returnType.contains(returnType)) {
                        matches = false
                    }
                }

                // Annotation filter
                filter.hasAnnotation?.let { annotationFqn ->
                    if (!method.annotations.any { it.type == annotationFqn }) {
                        matches = false
                    }
                }

                if (matches) {
                    results.add(
                        MethodSearchResult(
                            classFqn = classInfo.name.fqn,
                            classSimpleName = classInfo.name.simpleName,
                            classSource = classInfo.source,
                            method = method
                        )
                    )
                }
            }
        }

        return results.sortedWith(compareBy({ it.classFqn }, { it.method.name }))
    }

    /**
     * Converts a ClassGraph ClassInfo to our model.
     */
    private fun convertClassInfo(cgClass: CGClassInfo, projectOutputPaths: Set<String>): ClassInfo {
        val source = classifySource(cgClass, projectOutputPaths)

        return ClassInfo(
            name = ClassName(
                fqn = cgClass.name,
                simpleName = cgClass.simpleName,
                packageName = cgClass.packageName ?: ""
            ),
            source = source,
            visibility = getVisibility(cgClass),
            isInterface = cgClass.isInterface,
            isAbstract = cgClass.isAbstract,
            isFinal = cgClass.isFinal,
            isEnum = cgClass.isEnum,
            isAnnotation = cgClass.isAnnotation,
            isSynthetic = cgClass.isSynthetic,
            superclass = cgClass.superclass?.name,
            interfaces = cgClass.interfaces.map { it.name },
            annotations = cgClass.annotationInfo.map { convertAnnotation(it) },
            methods = cgClass.declaredMethodInfo.map { convertMethod(it) },
            fields = cgClass.declaredFieldInfo.map { convertField(it) }
        )
    }

    /**
     * Classifies the source of a class.
     */
    private fun classifySource(cgClass: CGClassInfo, projectOutputPaths: Set<String>): ClassSource {
        val classLocation = cgClass.classpathElementFile?.absolutePath

        return when {
            classLocation == null -> ClassSource.LIBRARY
            projectOutputPaths.any { classLocation.startsWith(it) } -> ClassSource.PROJECT
            isJdkClass(cgClass.name, classLocation) -> ClassSource.JDK
            else -> ClassSource.LIBRARY
        }
    }

    /**
     * Checks if a class is from the JDK.
     */
    private fun isJdkClass(className: String, location: String): Boolean {
        val jdkPackages = listOf(
            "java.", "javax.", "sun.", "com.sun.", "jdk.",
            "org.w3c.", "org.xml.", "org.ietf."
        )
        if (jdkPackages.any { className.startsWith(it) }) {
            return true
        }
        // Also check if it's from the JDK installation
        val javaHome = System.getProperty("java.home") ?: return false
        return location.startsWith(javaHome)
    }

    /**
     * Gets the visibility modifier for a class.
     */
    private fun getVisibility(cgClass: CGClassInfo): Visibility {
        return when {
            cgClass.isPublic -> Visibility.PUBLIC
            cgClass.isProtected -> Visibility.PROTECTED
            cgClass.isPrivate -> Visibility.PRIVATE
            else -> Visibility.PACKAGE_PRIVATE
        }
    }

    /**
     * Converts an annotation.
     */
    private fun convertAnnotation(ann: CGAnnotationInfo): AnnotationInfo {
        val params = mutableMapOf<String, String>()
        ann.parameterValues.forEach { param ->
            params[param.name] = param.value?.toString() ?: "null"
        }
        return AnnotationInfo(
            type = ann.name,
            parameters = params
        )
    }

    /**
     * Converts a method.
     */
    private fun convertMethod(method: CGMethodInfo): MethodInfo {
        return MethodInfo(
            name = method.name,
            visibility = getMethodVisibility(method),
            returnType = method.typeDescriptor?.resultType?.toString() ?: "void",
            parameters = method.parameterInfo.mapIndexed { index, param ->
                ParameterInfo(
                    name = param.name ?: "arg$index",
                    type = param.typeDescriptor?.toString() ?: "java.lang.Object",
                    annotations = param.annotationInfo.map { convertAnnotation(it) }
                )
            },
            annotations = method.annotationInfo.map { convertAnnotation(it) },
            isStatic = method.isStatic,
            isAbstract = method.isAbstract,
            isFinal = method.isFinal,
            isSynthetic = method.isSynthetic
        )
    }

    /**
     * Gets the visibility modifier for a method.
     */
    private fun getMethodVisibility(method: CGMethodInfo): Visibility {
        return when {
            method.isPublic -> Visibility.PUBLIC
            method.isProtected -> Visibility.PROTECTED
            method.isPrivate -> Visibility.PRIVATE
            else -> Visibility.PACKAGE_PRIVATE
        }
    }

    /**
     * Converts a field.
     */
    private fun convertField(field: CGFieldInfo): FieldInfo {
        return FieldInfo(
            name = field.name,
            visibility = getFieldVisibility(field),
            type = field.typeDescriptor?.toString() ?: "java.lang.Object",
            annotations = field.annotationInfo.map { convertAnnotation(it) },
            isStatic = field.isStatic,
            isFinal = field.isFinal
        )
    }

    /**
     * Gets the visibility modifier for a field.
     */
    private fun getFieldVisibility(field: CGFieldInfo): Visibility {
        return when {
            field.isPublic -> Visibility.PUBLIC
            field.isProtected -> Visibility.PROTECTED
            field.isPrivate -> Visibility.PRIVATE
            else -> Visibility.PACKAGE_PRIVATE
        }
    }

    /**
     * Checks if a class matches the given filter.
     */
    private fun matchesFilter(
        classInfo: ClassInfo,
        filter: ClassFilter,
        effectiveSource: ClassSource?
    ): Boolean {
        // Source filter
        if (effectiveSource != null && classInfo.source != effectiveSource) {
            return false
        }

        // Interface/class filter
        if (filter.onlyInterfaces && !classInfo.isInterface) return false
        if (filter.onlyClasses && classInfo.isInterface) return false

        // Package pattern
        filter.packagePattern?.let { pattern ->
            if (!matchesPattern(classInfo.name.packageName, pattern)) {
                return false
            }
        }

        // Name pattern
        filter.namePattern?.let { pattern ->
            if (!matchesPattern(classInfo.name.simpleName, pattern) &&
                !matchesPattern(classInfo.name.fqn, pattern)) {
                return false
            }
        }

        // Annotation filter
        filter.hasAnnotation?.let { annotationFqn ->
            if (!classInfo.annotations.any { it.type == annotationFqn }) {
                return false
            }
        }

        // Extends filter
        filter.extendsClass?.let { superclassFqn ->
            if (classInfo.superclass != superclassFqn) {
                return false
            }
        }

        // Implements filter
        filter.implementsInterface?.let { interfaceFqn ->
            if (!classInfo.interfaces.contains(interfaceFqn)) {
                return false
            }
        }

        return true
    }

    /**
     * Matches a string against a pattern with * wildcards.
     */
    private fun matchesPattern(value: String, pattern: String): Boolean {
        if (!pattern.contains("*")) {
            return value == pattern
        }

        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
        return value.matches(Regex(regex))
    }

    /**
     * Creates empty statistics for when no classes were found.
     */
    private fun createEmptyStatistics(startTime: Long, resolvedBy: String): ScanStatistics {
        return ScanStatistics(
            projectClassCount = 0,
            libraryClassCount = 0,
            jdkClassCount = 0,
            projectInterfaceCount = 0,
            projectAbstractClassCount = 0,
            projectEnumCount = 0,
            projectAnnotationCount = 0,
            projectMethodCount = 0,
            projectFieldCount = 0,
            classpathResolvedBy = resolvedBy,
            classpathEntryCount = 0,
            scanDurationMs = System.currentTimeMillis() - startTime,
            scannedAt = Instant.now().toString()
        )
    }

    /**
     * Builds statistics from the scanned classes.
     */
    private fun buildStatistics(startTime: Long, classpathEntryCount: Int, resolvedBy: String): ScanStatistics {
        var projectClassCount = 0
        var libraryClassCount = 0
        var jdkClassCount = 0
        var projectInterfaceCount = 0
        var projectAbstractClassCount = 0
        var projectEnumCount = 0
        var projectAnnotationCount = 0
        var projectMethodCount = 0
        var projectFieldCount = 0

        for (classInfo in classes.values) {
            when (classInfo.source) {
                ClassSource.PROJECT -> {
                    projectClassCount++
                    if (classInfo.isInterface) projectInterfaceCount++
                    if (classInfo.isAbstract && !classInfo.isInterface) projectAbstractClassCount++
                    if (classInfo.isEnum) projectEnumCount++
                    if (classInfo.isAnnotation) projectAnnotationCount++
                    projectMethodCount += classInfo.methods.size
                    projectFieldCount += classInfo.fields.size
                }
                ClassSource.LIBRARY -> libraryClassCount++
                ClassSource.JDK -> jdkClassCount++
            }
        }

        return ScanStatistics(
            projectClassCount = projectClassCount,
            libraryClassCount = libraryClassCount,
            jdkClassCount = jdkClassCount,
            projectInterfaceCount = projectInterfaceCount,
            projectAbstractClassCount = projectAbstractClassCount,
            projectEnumCount = projectEnumCount,
            projectAnnotationCount = projectAnnotationCount,
            projectMethodCount = projectMethodCount,
            projectFieldCount = projectFieldCount,
            classpathResolvedBy = resolvedBy,
            classpathEntryCount = classpathEntryCount,
            scanDurationMs = System.currentTimeMillis() - startTime,
            scannedAt = Instant.now().toString()
        )
    }
}

/**
 * Extension function to convert ClassInfo to ClassSummary.
 */
private fun ClassInfo.toSummary(): ClassSummary {
    return ClassSummary(
        fqn = this.name.fqn,
        simpleName = this.name.simpleName,
        packageName = this.name.packageName,
        source = this.source,
        isInterface = this.isInterface,
        isAbstract = this.isAbstract,
        isEnum = this.isEnum,
        isAnnotation = this.isAnnotation,
        methodCount = this.methods.size,
        fieldCount = this.fields.size
    )
}

package codelens.classgraph

import codelens.core.model.*
import io.github.classgraph.ClassGraph
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile
import io.github.classgraph.AnnotationInfo as CGAnnotationInfo
import io.github.classgraph.ClassInfo as CGClassInfo
import io.github.classgraph.FieldInfo as CGFieldInfo
import io.github.classgraph.MethodInfo as CGMethodInfo
import io.github.classgraph.MethodParameterInfo as CGMethodParameterInfo
import io.github.classgraph.ScanResult as CGScanResult

/**
 * Implementation of ClassGraphProvider using the ClassGraph library.
 */
class ClassGraphProviderImpl : ClassGraphProvider {
    private val logger = LoggerFactory.getLogger(ClassGraphProviderImpl::class.java)

    private val classes = ConcurrentHashMap<String, ClassInfo>()
    private var statistics: ScanStatistics? = null
    private var projectOutputDirs: Set<File> = emptySet()

    override fun scan(
        classpathEntries: List<File>,
        projectOutputDirs: Set<File>,
        resolvedBy: String,
    ): ScanResult {
        logger.info("Starting ClassGraph scan with ${classpathEntries.size} classpath entries (resolved by: $resolvedBy)")
        val startTime = System.currentTimeMillis()

        this.projectOutputDirs = projectOutputDirs
        this.classes.clear()

        // Build the classpath string
        val classpathStr =
            classpathEntries
                .filter { it.exists() }
                .joinToString(File.pathSeparator) { it.absolutePath }

        if (classpathStr.isEmpty()) {
            logger.warn("No valid classpath entries found")
            val stats = createEmptyStatistics(startTime, resolvedBy)
            this.statistics = stats
            return ScanResult(emptyMap(), stats, projectOutputDirs)
        }

        val projectOutputPaths = projectOutputDirs.map { it.absolutePath }.toSet()

        var cgScanResult: CGScanResult? = null
        try {
            cgScanResult =
                ClassGraph()
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

            // Build statistics - use the resolver name, not "ClassGraph"
            val stats = buildStatistics(startTime, classpathEntries.size, resolvedBy)
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

    override fun getClass(fqn: String): ClassInfo? = classes[fqn]

    override fun getStatistics(): ScanStatistics? = statistics

    override fun isScanned(): Boolean = statistics != null

    override fun getImplementations(
        fqn: String,
        includeLibraries: Boolean,
    ): Pair<List<ClassSummary>, List<ClassSummary>> {
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
        val directImpls =
            directImplFqns.mapNotNull { implFqn ->
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
            indirectImpls.sortedBy { it.fqn },
        )
    }

    override fun getHierarchy(fqn: String): HierarchyNode? {
        val targetClass = classes[fqn] ?: return null

        // Build parent chain
        val parentChain = buildParentChain(targetClass)

        // Build children tree
        val childrenTree = buildChildrenTree(fqn)

        // Build interface implementations
        val interfaceNodes =
            targetClass.interfaces.mapNotNull { ifaceFqn ->
                classes[ifaceFqn]?.let { ifaceInfo ->
                    HierarchyNode(
                        classFqn = ifaceFqn,
                        simpleName = ifaceInfo.name.simpleName,
                        source = ifaceInfo.source,
                        isInterface = true,
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
            children = childrenTree,
        )
    }

    private fun buildParentChain(classInfo: ClassInfo): HierarchyNode? {
        // If superclass is null but this is not an interface/annotation, default to java.lang.Object
        // This handles the case where ClassGraph doesn't return Object because it wasn't scanned
        val superclassFqn =
            classInfo.superclass
                ?: if (!classInfo.isInterface && !classInfo.isAnnotation) {
                    "java.lang.Object" // Default for regular classes
                } else {
                    return null // Interfaces and annotations have no superclass
                }

        // Handle java.lang.Object explicitly - it's the root of the hierarchy
        if (superclassFqn == "java.lang.Object") {
            return HierarchyNode(
                classFqn = "java.lang.Object",
                simpleName = "Object",
                source = ClassSource.JDK,
                isInterface = false,
                parent = null, // Object is the root
            )
        }

        val superclassInfo =
            classes[superclassFqn] ?: return HierarchyNode(
                classFqn = superclassFqn,
                simpleName = superclassFqn.substringAfterLast('.'),
                source = ClassSource.LIBRARY,
                isInterface = false,
                parent = null, // Can't determine further ancestry for unscanned classes
            )

        return HierarchyNode(
            classFqn = superclassFqn,
            simpleName = superclassInfo.name.simpleName,
            source = superclassInfo.source,
            isInterface = superclassInfo.isInterface,
            parent = buildParentChain(superclassInfo),
        )
    }

    private fun buildChildrenTree(fqn: String): List<HierarchyNode> {
        val children = mutableListOf<HierarchyNode>()

        for (classInfo in classes.values) {
            val isDirectChild =
                if (classes[fqn]?.isInterface == true) {
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
                        children = buildChildrenTree(classInfo.name.fqn),
                    ),
                )
            }
        }

        return children.sortedBy { it.classFqn }
    }

    override fun getDependencies(
        fqn: String,
        includeLibraries: Boolean,
    ): Pair<List<DependencyInfo>, List<DependencyInfo>> = DependencyAnalyzer(classes).analyze(fqn, includeLibraries)

    override fun getAnnotationUsages(
        annotationFqn: String,
        includeLibraries: Boolean,
    ): List<ClassSummary> =
        classes.values
            .asSequence()
            .filter { classInfo ->
                (includeLibraries || classInfo.source == ClassSource.PROJECT) &&
                    classInfo.annotations.any { it.type == annotationFqn }
            }.map { it.toSummary() }
            .sortedBy { it.fqn }
            .toList()

    override fun searchMethods(filter: MethodFilter): List<MethodSearchResult> {
        val results = mutableListOf<MethodSearchResult>()

        for (classInfo in classes.values) {
            // Apply class-level filters
            if (!filter.includeLibraries && classInfo.source != ClassSource.PROJECT) continue

            // Skip this class (not the whole search) when it fails a class-level filter.
            // Capture into locals so matchesPattern's non-null String param can smart-cast
            // (filter.* are cross-module properties and cannot be smart-cast directly).
            val inClass = filter.inClass
            if (inClass != null && classInfo.name.fqn != inClass) continue
            val inPackage = filter.inPackage
            if (inPackage != null && !matchesPattern(classInfo.name.packageName, inPackage)) continue

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

                // Return type filter. Match on the erased base type: widening
                // returnType to its generic form for display must not silently
                // broaden this filter into type arguments (to find a type used as a
                // type argument, use `xref`).
                filter.returnType?.let { returnType ->
                    val methodReturnType = extractTypeFqn(method.returnType)
                    if (methodReturnType != returnType && methodReturnType?.contains(returnType) != true) {
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
                            method = method,
                        ),
                    )
                }
            }
        }

        return results.sortedWith(compareBy({ it.classFqn }, { it.method.name }))
    }

    override fun getAllClasses(): Map<String, ClassInfo> = classes.toMap()

    override fun getCalls(
        fqn: String,
        methodName: String?,
        descriptor: String?,
    ): CallSiteList = CallSiteExtractor(this).extract(fqn, methodName, descriptor)

    override fun getReferencesToType(
        typeFqn: String,
        includeLibraries: Boolean,
        scopeImplementing: String?,
    ): List<XrefReference> = TypeXrefAnalyzer(this).analyze(typeFqn, includeLibraries, scopeImplementing)

    override fun getProjectGraph(): ProjectGraph = DependencyAnalyzer(classes).buildProjectGraph()

    override fun getFoundationClasses(minDependents: Int): List<FoundationClass> =
        DependencyAnalyzer(classes).foundationClasses(minDependents)

    override fun getClassBytes(fqn: String): ByteArray? {
        val classInfo = classes[fqn] ?: return null
        val jarPath = classInfo.jarPath ?: return null
        val classFile = File(jarPath)

        if (!classFile.exists()) {
            logger.warn("Class file location not found: $jarPath")
            return null
        }

        // Convert FQN to class file path (e.g., com.example.Foo -> com/example/Foo.class)
        val classEntryPath = fqn.replace('.', '/') + ".class"

        return try {
            if (classFile.isDirectory) {
                // Read from directory
                val file = File(classFile, classEntryPath)
                if (file.exists()) {
                    file.readBytes()
                } else {
                    logger.warn("Class file not found: ${file.absolutePath}")
                    null
                }
            } else if (classFile.extension == "jar" || classFile.name.endsWith(".jar")) {
                // Read from JAR
                JarFile(classFile).use { jar ->
                    val entry = jar.getJarEntry(classEntryPath)
                    if (entry != null) {
                        jar.getInputStream(entry).use { it.readBytes() }
                    } else {
                        logger.warn("Class entry not found in JAR: $classEntryPath in $jarPath")
                        null
                    }
                }
            } else {
                logger.warn("Unknown classpath element type: $jarPath")
                null
            }
        } catch (e: Exception) {
            logger.warn("Failed to read class bytes for $fqn: ${e.message}")
            null
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

    /**
     * Converts a ClassGraph ClassInfo to our model.
     */
    private fun convertClassInfo(
        cgClass: CGClassInfo,
        projectOutputPaths: Set<String>,
    ): ClassInfo {
        val source = classifySource(cgClass, projectOutputPaths)
        // Generic class signature (when present): lets us capture type arguments of
        // generic supertypes, e.g. `Foo` in `extends Base<Foo>`.
        val classSignature = cgClass.typeSignatureOrTypeDescriptor

        return ClassInfo(
            name =
                ClassName(
                    fqn = cgClass.name,
                    simpleName = cgClass.simpleName,
                    packageName = cgClass.packageName ?: "",
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
            constructors = cgClass.declaredConstructorInfo.map { convertConstructor(it) },
            methods = cgClass.declaredMethodInfo.map { convertMethod(it) },
            fields = cgClass.declaredFieldInfo.map { convertField(it) },
            jarPath = cgClass.classpathElementFile?.absolutePath,
            superclassTypeArgs = typeArgumentFqns(classSignature?.superclassSignature),
            interfaceTypeArgs =
                classSignature
                    ?.superinterfaceSignatures
                    ?.flatMap { typeArgumentFqns(it) }
                    ?: emptyList(),
        )
    }

    /**
     * Classifies the source of a class.
     */
    private fun classifySource(
        cgClass: CGClassInfo,
        projectOutputPaths: Set<String>,
    ): ClassSource {
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
    private fun isJdkClass(
        className: String,
        location: String,
    ): Boolean {
        val jdkPackages =
            listOf(
                "java.",
                "javax.",
                "sun.",
                "com.sun.",
                "jdk.",
                "org.w3c.",
                "org.xml.",
                "org.ietf.",
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
    private fun getVisibility(cgClass: CGClassInfo): Visibility =
        when {
            cgClass.isPublic -> Visibility.PUBLIC
            cgClass.isProtected -> Visibility.PROTECTED
            cgClass.isPrivate -> Visibility.PRIVATE
            else -> Visibility.PACKAGE_PRIVATE
        }

    /**
     * Converts an annotation.
     */
    private fun convertAnnotation(ann: CGAnnotationInfo): AnnotationInfo {
        val params = mutableMapOf<String, String>()
        ann.parameterValues.forEach { param ->
            params[param.name] = formatAnnotationValue(param.value)
        }
        return AnnotationInfo(
            type = ann.name,
            parameters = params,
        )
    }

    /**
     * Formats an annotation parameter value to a human-readable string.
     * Handles arrays properly instead of showing Java object references.
     */
    private fun formatAnnotationValue(value: Any?): String =
        when (value) {
            null -> "null"
            is BooleanArray -> value.contentToString()
            is ByteArray -> value.contentToString()
            is CharArray -> value.contentToString()
            is ShortArray -> value.contentToString()
            is IntArray -> value.contentToString()
            is LongArray -> value.contentToString()
            is FloatArray -> value.contentToString()
            is DoubleArray -> value.contentToString()
            is Array<*> -> value.map { formatAnnotationValue(it) }.toString()
            is Enum<*> -> "${value.javaClass.name}.${value.name}"
            is Class<*> -> value.name
            else -> value.toString()
        }

    /**
     * Converts a constructor.
     */
    private fun convertConstructor(ctor: CGMethodInfo): ConstructorInfo =
        ConstructorInfo(
            visibility = getMethodVisibility(ctor),
            parameters = ctor.parameterInfo.mapIndexed { index, param -> convertParameter(index, param) },
            annotations = ctor.annotationInfo.map { convertAnnotation(it) },
            isSynthetic = ctor.isSynthetic,
        )

    /**
     * Converts a method. The return type is captured in generic form (via the
     * type signature) when available, with [MethodInfo.returnTypeRefs] holding
     * every class FQN it references (container + type arguments).
     */
    private fun convertMethod(method: CGMethodInfo): MethodInfo {
        val resultType = method.typeSignatureOrTypeDescriptor?.resultType
        return MethodInfo(
            name = method.name,
            visibility = getMethodVisibility(method),
            returnType = resultType?.toString() ?: "void",
            parameters = method.parameterInfo.mapIndexed { index, param -> convertParameter(index, param) },
            annotations = method.annotationInfo.map { convertAnnotation(it) },
            isStatic = method.isStatic,
            isAbstract = method.isAbstract,
            isFinal = method.isFinal,
            isSynthetic = method.isSynthetic,
            returnTypeRefs = referencedClassFqns(resultType),
        )
    }

    /**
     * Converts a method/constructor parameter, capturing its type in generic
     * form and the set of class FQNs it references (container + type arguments).
     */
    private fun convertParameter(
        index: Int,
        param: CGMethodParameterInfo,
    ): ParameterInfo {
        val typeSignature = param.typeSignatureOrTypeDescriptor
        return ParameterInfo(
            name = param.name ?: "arg$index",
            type = typeSignature?.toString() ?: "java.lang.Object",
            annotations = param.annotationInfo.map { convertAnnotation(it) },
            typeRefs = referencedClassFqns(typeSignature),
        )
    }

    /**
     * Gets the visibility modifier for a method.
     */
    private fun getMethodVisibility(method: CGMethodInfo): Visibility =
        when {
            method.isPublic -> Visibility.PUBLIC
            method.isProtected -> Visibility.PROTECTED
            method.isPrivate -> Visibility.PRIVATE
            else -> Visibility.PACKAGE_PRIVATE
        }

    /**
     * Converts a field.
     */
    private fun convertField(field: CGFieldInfo): FieldInfo {
        val typeSignature = field.typeSignatureOrTypeDescriptor
        return FieldInfo(
            name = field.name,
            visibility = getFieldVisibility(field),
            type = typeSignature?.toString() ?: "java.lang.Object",
            annotations = field.annotationInfo.map { convertAnnotation(it) },
            isStatic = field.isStatic,
            isFinal = field.isFinal,
            typeRefs = referencedClassFqns(typeSignature),
        )
    }

    /**
     * Gets the visibility modifier for a field.
     */
    private fun getFieldVisibility(field: CGFieldInfo): Visibility =
        when {
            field.isPublic -> Visibility.PUBLIC
            field.isProtected -> Visibility.PROTECTED
            field.isPrivate -> Visibility.PRIVATE
            else -> Visibility.PACKAGE_PRIVATE
        }

    /**
     * Checks if a class matches the given filter.
     */
    private fun matchesFilter(
        classInfo: ClassInfo,
        filter: ClassFilter,
        effectiveSource: ClassSource?,
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
                !matchesPattern(classInfo.name.fqn, pattern)
            ) {
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
    private fun matchesPattern(
        value: String,
        pattern: String,
    ): Boolean {
        if (!pattern.contains("*")) {
            return value == pattern
        }

        val regex =
            pattern
                .replace(".", "\\.")
                .replace("*", ".*")
        return value.matches(Regex(regex))
    }

    /**
     * Creates empty statistics for when no classes were found.
     */
    private fun createEmptyStatistics(
        startTime: Long,
        resolvedBy: String,
    ): ScanStatistics =
        ScanStatistics(
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
            scannedAt = Instant.now().toString(),
        )

    /**
     * Builds statistics from the scanned classes.
     */
    private fun buildStatistics(
        startTime: Long,
        classpathEntryCount: Int,
        resolvedBy: String,
    ): ScanStatistics {
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
            scannedAt = Instant.now().toString(),
        )
    }
}

/**
 * Extension function to convert ClassInfo to ClassSummary.
 */
private fun ClassInfo.toSummary(): ClassSummary =
    ClassSummary(
        fqn = this.name.fqn,
        simpleName = this.name.simpleName,
        packageName = this.name.packageName,
        source = this.source,
        isInterface = this.isInterface,
        isAbstract = this.isAbstract,
        isEnum = this.isEnum,
        isAnnotation = this.isAnnotation,
        methodCount = this.methods.size,
        fieldCount = this.fields.size,
    )

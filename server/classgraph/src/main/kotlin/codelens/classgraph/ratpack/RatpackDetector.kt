package codelens.classgraph.ratpack

import codelens.classgraph.ClassGraphProvider
import codelens.core.model.ClassInfo
import codelens.core.model.ClassSource
import codelens.core.model.MethodInfo
import codelens.core.model.ratpack.*
import org.slf4j.LoggerFactory

/**
 * Detects Ratpack handlers and analyzes their characteristics.
 *
 * Handler detection finds classes that:
 * - Implement ratpack.handling.Handler directly
 * - Implement ratpack.func.Action<Chain>
 * - Extend GroovyHandler or similar base classes
 * - Have methods accepting Context that call next()
 */
class RatpackDetector(
    private val classGraphProvider: ClassGraphProvider
) {
    private val logger = LoggerFactory.getLogger(RatpackDetector::class.java)

    // Cached detector instances to avoid creating new ones per handler
    private val promiseDetector by lazy { PromiseDetector(classGraphProvider) }
    private val complexityCalculator by lazy { ComplexityCalculator(classGraphProvider) }

    /**
     * Find all Ratpack handlers in the scanned codebase.
     *
     * @param includeLibraries Include handlers from library dependencies
     * @return List of handler summaries
     */
    fun findAllHandlers(includeLibraries: Boolean = false): List<HandlerSummary> {
        val handlers = mutableListOf<HandlerSummary>()

        // Get direct Handler implementations
        val (directHandlers, indirectHandlers) = classGraphProvider.getImplementations(
            RatpackTypes.HANDLER,
            includeLibraries
        )

        // Process direct implementations
        for (classSummary in directHandlers) {
            val classInfo = classGraphProvider.getClass(classSummary.fqn) ?: continue
            if (!includeLibraries && classInfo.source != ClassSource.PROJECT) continue

            handlers.add(createHandlerSummary(classInfo, HandlerType.HANDLER))
        }

        // Process indirect implementations (through GroovyHandler, etc.)
        for (classSummary in indirectHandlers) {
            val classInfo = classGraphProvider.getClass(classSummary.fqn) ?: continue
            if (!includeLibraries && classInfo.source != ClassSource.PROJECT) continue

            val handlerType = determineHandlerType(classInfo) ?: continue
            handlers.add(createHandlerSummary(classInfo, handlerType))
        }

        // Find Chain Action implementations
        val chainActionHandlers = findChainActionImplementations(includeLibraries)
        handlers.addAll(chainActionHandlers)

        // Remove duplicates (a class might implement both Handler and Chain Action)
        return handlers.distinctBy { it.fqn }.sortedBy { it.fqn }
    }

    /**
     * Get detailed information about a specific handler.
     *
     * @param fqn Fully qualified class name
     * @return Handler info, or null if not found or not a handler
     */
    fun getHandlerDetail(fqn: String): HandlerInfo? {
        val classInfo = classGraphProvider.getClass(fqn) ?: return null

        // Verify it's a handler
        val handlerType = determineHandlerType(classInfo)
        if (handlerType == null && !isChainActionImpl(classInfo)) {
            logger.debug("Class $fqn is not a recognized handler type")
            return null
        }

        val effectiveHandlerType = handlerType ?: HandlerType.CHAIN_ACTION

        // Find handler methods (methods that accept Context)
        val handlerMethods = findHandlerMethods(classInfo)

        // Analyze Promise usage
        val promiseAnalysis = promiseDetector.analyzeClass(fqn)

        // Calculate complexity
        val complexity = complexityCalculator.calculate(fqn)

        // Find injected dependencies
        val injectedDeps = findInjectedDependencies(classInfo)

        return HandlerInfo(
            fqn = classInfo.name.fqn,
            simpleName = classInfo.name.simpleName,
            packageName = classInfo.name.packageName,
            handlerType = effectiveHandlerType,
            source = classInfo.source,
            superclass = classInfo.superclass,
            interfaces = classInfo.interfaces,
            handlerMethods = handlerMethods,
            allMethods = classInfo.methods,
            promiseAnalysis = promiseAnalysis,
            complexity = complexity,
            injectedDependencies = injectedDeps
        )
    }

    /**
     * Find classes that implement Action<Chain>.
     */
    private fun findChainActionImplementations(includeLibraries: Boolean): List<HandlerSummary> {
        val handlers = mutableListOf<HandlerSummary>()

        // Action<Chain> implementations are detected by:
        // 1. Implementing ratpack.func.Action interface
        // 2. Having an execute(Chain) method
        val (directActions, _) = classGraphProvider.getImplementations(
            RatpackTypes.CHAIN_ACTION,
            includeLibraries
        )

        for (classSummary in directActions) {
            val classInfo = classGraphProvider.getClass(classSummary.fqn) ?: continue
            if (!includeLibraries && classInfo.source != ClassSource.PROJECT) continue

            // Check if this is specifically Action<Chain> by looking for execute(Chain) method
            val hasChainExecute = classInfo.methods.any { method ->
                method.name == "execute" &&
                    method.parameters.size == 1 &&
                    method.parameters[0].type.contains(RatpackTypes.CHAIN)
            }

            if (hasChainExecute) {
                handlers.add(createHandlerSummary(classInfo, HandlerType.CHAIN_ACTION))
            }
        }

        return handlers
    }

    /**
     * Determine the handler type for a class.
     */
    private fun determineHandlerType(classInfo: ClassInfo): HandlerType? {
        // Check for direct Handler implementation
        if (classInfo.interfaces.contains(RatpackTypes.HANDLER)) {
            return HandlerType.HANDLER
        }

        // Check for GroovyHandler
        if (classInfo.superclass == RatpackTypes.GROOVY_HANDLER ||
            extendsGroovyHandler(classInfo)
        ) {
            return HandlerType.GROOVY_HANDLER
        }

        // Check if it extends something that implements Handler
        classInfo.superclass?.let { superclass ->
            val superclassInfo = classGraphProvider.getClass(superclass)
            if (superclassInfo != null && superclassInfo.interfaces.contains(RatpackTypes.HANDLER)) {
                return HandlerType.HANDLER
            }
        }

        // Check for inline handler (method with Context parameter)
        if (hasInlineHandlerMethod(classInfo)) {
            return HandlerType.INLINE_HANDLER
        }

        return null
    }

    /**
     * Check if the class extends GroovyHandler (directly or indirectly).
     */
    private fun extendsGroovyHandler(classInfo: ClassInfo): Boolean {
        var current: ClassInfo? = classInfo
        val visited = mutableSetOf<String>()

        while (current != null) {
            if (current.name.fqn in visited) break
            visited.add(current.name.fqn)

            if (current.superclass == RatpackTypes.GROOVY_HANDLER) {
                return true
            }
            current = current.superclass?.let { classGraphProvider.getClass(it) }
        }
        return false
    }

    /**
     * Check if the class has a method that looks like an inline handler.
     */
    private fun hasInlineHandlerMethod(classInfo: ClassInfo): Boolean {
        return classInfo.methods.any { method ->
            method.parameters.any { param ->
                param.type.contains(RatpackTypes.CONTEXT)
            }
        }
    }

    /**
     * Check if this class implements Action<Chain>.
     */
    private fun isChainActionImpl(classInfo: ClassInfo): Boolean {
        // Check interfaces for Action
        if (classInfo.interfaces.any { it.contains(RatpackTypes.CHAIN_ACTION) }) {
            return classInfo.methods.any { method ->
                method.name == "execute" &&
                    method.parameters.size == 1 &&
                    method.parameters[0].type.contains(RatpackTypes.CHAIN)
            }
        }
        return false
    }

    /**
     * Find methods that are handler entry points (accept Context).
     */
    private fun findHandlerMethods(classInfo: ClassInfo): List<MethodInfo> {
        return classInfo.methods.filter { method ->
            // Handler.handle(Context) method
            (method.name == "handle" && method.parameters.any {
                it.type.contains(RatpackTypes.CONTEXT)
            }) ||
                // Action.execute(Chain) method
                (method.name == "execute" && method.parameters.any {
                    it.type.contains(RatpackTypes.CHAIN)
                })
        }
    }

    /**
     * Find dependencies injected via constructor, @Inject fields, or @Inject methods.
     */
    private fun findInjectedDependencies(classInfo: ClassInfo): List<InjectedDependency> {
        val dependencies = mutableListOf<InjectedDependency>()

        // Check constructor parameters
        // In Java, constructor is <init> method, but ClassGraph doesn't expose it directly
        // Instead, look for @Inject annotated constructors via fields with final modifier
        // Or look for constructor with parameters matching injected field types

        // Check @Inject annotated fields
        for (field in classInfo.fields) {
            val hasInjectAnnotation = field.annotations.any { ann ->
                ann.type in RatpackTypes.INJECT_ANNOTATIONS
            }
            if (hasInjectAnnotation) {
                dependencies.add(
                    InjectedDependency(
                        name = field.name,
                        typeFqn = field.type,
                        injectionType = InjectionType.FIELD
                    )
                )
            }
        }

        // Check for setter injection (@Inject annotated methods)
        for (method in classInfo.methods) {
            val hasInjectAnnotation = method.annotations.any { ann ->
                ann.type in RatpackTypes.INJECT_ANNOTATIONS
            }
            if (hasInjectAnnotation && method.name.startsWith("set") && method.parameters.size == 1) {
                dependencies.add(
                    InjectedDependency(
                        name = method.name.removePrefix("set").replaceFirstChar { it.lowercase() },
                        typeFqn = method.parameters[0].type,
                        injectionType = InjectionType.METHOD
                    )
                )
            }
        }

        // Infer constructor injection from final fields without @Inject
        // (common pattern: final fields initialized in constructor)
        val injectedFieldNames = dependencies.map { it.name }.toSet()
        for (field in classInfo.fields) {
            if (field.isFinal && !field.isStatic && field.name !in injectedFieldNames) {
                // Likely constructor-injected
                dependencies.add(
                    InjectedDependency(
                        name = field.name,
                        typeFqn = field.type,
                        injectionType = InjectionType.CONSTRUCTOR
                    )
                )
            }
        }

        return dependencies
    }

    /**
     * Create a handler summary from class info.
     */
    private fun createHandlerSummary(classInfo: ClassInfo, handlerType: HandlerType): HandlerSummary {
        // Analyze Promise usage for summary stats
        val promiseAnalysis = promiseDetector.analyzeClass(classInfo.name.fqn)

        // Calculate complexity
        val complexity = complexityCalculator.calculate(classInfo.name.fqn)

        return HandlerSummary(
            fqn = classInfo.name.fqn,
            simpleName = classInfo.name.simpleName,
            packageName = classInfo.name.packageName,
            handlerType = handlerType,
            source = classInfo.source,
            complexityScore = complexity.score,
            complexityTier = complexity.tier,
            promiseOperationCount = promiseAnalysis.totalOperationCount,
            usesBlocking = promiseAnalysis.usesBlocking
        )
    }
}

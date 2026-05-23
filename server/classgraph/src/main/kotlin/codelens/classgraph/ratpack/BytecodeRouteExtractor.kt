package codelens.classgraph.ratpack

import codelens.classgraph.ClassGraphProvider
import codelens.core.model.ratpack.HttpMethod
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.slf4j.LoggerFactory

/**
 * Represents a raw route extracted from bytecode.
 * This is an intermediate representation before building the full RouteInfo.
 */
data class ExtractedRoute(
    val path: String,
    val method: HttpMethod,
    val handlerClassName: String?,
    val isNestedChain: Boolean = false,
)

/**
 * Extracts route definitions from bytecode using ASM.
 *
 * This analyzer parses the bytecode instructions within execute(Chain) methods
 * to find calls to Chain routing methods (get, post, prefix, etc.) and extracts
 * the path patterns and handler class references.
 */
class BytecodeRouteExtractor(
    private val classGraphProvider: ClassGraphProvider,
) {
    private val logger = LoggerFactory.getLogger(BytecodeRouteExtractor::class.java)

    companion object {
        // Ratpack Chain interface internal name
        private const val CHAIN_INTERNAL_NAME = "ratpack/handling/Chain"

        // Routing methods on Chain that define routes
        private val HTTP_METHOD_NAMES =
            mapOf(
                "get" to HttpMethod.GET,
                "post" to HttpMethod.POST,
                "put" to HttpMethod.PUT,
                "patch" to HttpMethod.PATCH,
                "delete" to HttpMethod.DELETE,
                "options" to HttpMethod.OPTIONS,
                "head" to HttpMethod.HEAD,
                "all" to HttpMethod.ALL,
            )

        // Methods that define path structure
        private const val PREFIX_METHOD = "prefix"
        private const val PATH_METHOD = "path"
    }

    /**
     * Extract routes from a class that implements Action<Chain>.
     *
     * @param fqn Fully qualified class name
     * @return List of extracted routes, or empty list if extraction fails
     */
    fun extractRoutes(fqn: String): List<ExtractedRoute> {
        val classBytes = classGraphProvider.getClassBytes(fqn)
        if (classBytes == null) {
            logger.debug("Could not get class bytes for $fqn")
            return emptyList()
        }

        return try {
            val routes = mutableListOf<ExtractedRoute>()
            val reader = ClassReader(classBytes)
            val visitor = RouteExtractingClassVisitor(routes)
            reader.accept(visitor, ClassReader.EXPAND_FRAMES)
            routes
        } catch (e: Exception) {
            logger.warn("Failed to extract routes from $fqn: ${e.message}")
            emptyList()
        }
    }

    /**
     * ASM ClassVisitor that looks for execute(Chain) methods.
     */
    private inner class RouteExtractingClassVisitor(
        private val routes: MutableList<ExtractedRoute>,
    ) : ClassVisitor(Opcodes.ASM9) {
        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            // Look for execute(Chain) method
            if (name == "execute" && descriptor?.contains("Lratpack/handling/Chain;") == true) {
                return RouteExtractingMethodVisitor(routes)
            }
            return null
        }
    }

    /**
     * ASM MethodVisitor that extracts route definitions from bytecode instructions.
     *
     * This tracks LDC (load constant) instructions to capture string path patterns
     * and class references, then correlates them with subsequent Chain method calls.
     */
    private inner class RouteExtractingMethodVisitor(
        private val routes: MutableList<ExtractedRoute>,
    ) : MethodVisitor(Opcodes.ASM9) {
        // Stack to track recent LDC constants (strings and class references)
        private val recentConstants = ArrayDeque<Any>()
        private val maxStackSize = 10

        override fun visitLdcInsn(value: Any?) {
            // Track LDC instructions - these load constants onto the stack
            if (value != null) {
                if (recentConstants.size >= maxStackSize) {
                    recentConstants.removeFirst()
                }
                recentConstants.addLast(value)
            }
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String?,
            name: String?,
            descriptor: String?,
            isInterface: Boolean,
        ) {
            // Only care about interface methods on Chain
            if (owner != CHAIN_INTERNAL_NAME || name == null) {
                return
            }

            // Check if this is an HTTP method call (get, post, etc.)
            val httpMethod = HTTP_METHOD_NAMES[name]
            if (httpMethod != null) {
                handleHttpMethodCall(httpMethod, descriptor)
                return
            }

            // Check if this is a prefix or path call
            when (name) {
                PREFIX_METHOD -> handlePrefixCall(descriptor)
                PATH_METHOD -> handlePathCall(descriptor)
            }
        }

        /**
         * Handle HTTP method calls like chain.get(path, handler).
         */
        private fun handleHttpMethodCall(
            method: HttpMethod,
            descriptor: String?,
        ) {
            // Find the most recent string constant (the path)
            val path = findRecentString()

            // Find any class reference (the handler class)
            val handlerClass = findRecentClass()

            // For 'all' handler (middleware), only add if no path or it's a valid path
            // This filters out false positives like cookie names or scope strings
            if (method == HttpMethod.ALL) {
                if (path == null || !looksLikePath(path)) {
                    // Middleware without a specific path - add with root path
                    if (handlerClass != null) {
                        routes.add(
                            ExtractedRoute(
                                path = "",
                                method = method,
                                handlerClassName = handlerClass,
                                isNestedChain = false,
                            ),
                        )
                    }
                    recentConstants.clear()
                    return
                }
            }

            if (path != null && looksLikePath(path)) {
                routes.add(
                    ExtractedRoute(
                        path = path,
                        method = method,
                        handlerClassName = handlerClass,
                        isNestedChain = false,
                    ),
                )
            } else if (handlerClass != null) {
                // Method call with just a handler class (e.g., chain.get(MyHandler.class))
                // Path is inherited from context
                routes.add(
                    ExtractedRoute(
                        path = "",
                        method = method,
                        handlerClassName = handlerClass,
                        isNestedChain = false,
                    ),
                )
            }

            // Clear the constants after processing
            recentConstants.clear()
        }

        /**
         * Check if a string looks like a valid route path.
         * Filters out strings that are clearly not paths (like cookie names, scope names, etc.)
         */
        private fun looksLikePath(str: String): Boolean {
            // Empty or very short strings are likely not paths
            if (str.length < 2) return false

            // Paths typically start with alphanumeric or colon (for path params)
            // and don't contain special characters used in non-path strings
            val validPathPattern = Regex("^[a-zA-Z0-9/:_-]+$")
            if (!validPathPattern.matches(str)) return false

            // Common non-path patterns
            if (str.startsWith("_") && !str.contains("/")) return false // Likely a cookie or internal name
            if (str.length <= 10 && !str.contains("/") && !str.contains(":")) {
                // Short strings without path separators might be config values
                // but could also be short endpoints like "ping" or "config"
                // Be lenient here
            }

            return true
        }

        /**
         * Handle prefix calls like chain.prefix(path, NestedChain.class).
         */
        private fun handlePrefixCall(descriptor: String?) {
            val path = findRecentString()
            val handlerClass = findRecentClass()

            if (path != null) {
                // Check if handler is a Class (Action<Chain> implementation)
                val isNestedChain = handlerClass != null && isChainAction(handlerClass)

                routes.add(
                    ExtractedRoute(
                        path = path,
                        method = HttpMethod.ALL,
                        handlerClassName = handlerClass,
                        isNestedChain = isNestedChain,
                    ),
                )
            }

            recentConstants.clear()
        }

        /**
         * Handle path calls like chain.path(path, handler).
         */
        private fun handlePathCall(descriptor: String?) {
            val path = findRecentString()
            val handlerClass = findRecentClass()

            if (path != null) {
                routes.add(
                    ExtractedRoute(
                        path = path,
                        method = HttpMethod.ALL,
                        handlerClassName = handlerClass,
                        isNestedChain = false,
                    ),
                )
            }

            recentConstants.clear()
        }

        /**
         * Find the most recent string constant from the stack.
         */
        private fun findRecentString(): String? = recentConstants.filterIsInstance<String>().lastOrNull()

        /**
         * Find the most recent class reference from the stack.
         */
        private fun findRecentClass(): String? =
            recentConstants
                .filterIsInstance<Type>()
                .lastOrNull()
                ?.className

        /**
         * Check if a class is an Action<Chain> implementation.
         */
        private fun isChainAction(className: String): Boolean {
            val classInfo = classGraphProvider.getClass(className) ?: return false
            return classInfo.interfaces.any { it.contains("ratpack.func.Action") } ||
                classInfo.methods.any { m ->
                    m.name == "execute" &&
                        m.parameters.size == 1 &&
                        m.parameters[0].type.contains("Chain")
                }
        }
    }

    /**
     * Recursively extract routes, expanding nested Action<Chain> classes.
     *
     * @param fqn Starting class FQN
     * @param pathPrefix Path prefix to prepend to all routes
     * @param visited Set of already visited classes (to prevent cycles)
     * @return List of extracted routes with full paths
     */
    fun extractRoutesRecursively(
        fqn: String,
        pathPrefix: String = "",
        visited: MutableSet<String> = mutableSetOf(),
    ): List<ExtractedRoute> {
        if (fqn in visited) {
            logger.debug("Skipping already visited class: $fqn")
            return emptyList()
        }
        visited.add(fqn)

        val routes = extractRoutes(fqn)
        val expandedRoutes = mutableListOf<ExtractedRoute>()

        for (route in routes) {
            val fullPath = combinePaths(pathPrefix, route.path)

            if (route.isNestedChain && route.handlerClassName != null) {
                // Recursively extract routes from nested chain
                val nestedRoutes =
                    extractRoutesRecursively(
                        route.handlerClassName,
                        fullPath,
                        visited,
                    )
                if (nestedRoutes.isEmpty()) {
                    // If nested chain has no routes, add the prefix route as fallback
                    expandedRoutes.add(route.copy(path = fullPath, isNestedChain = false))
                } else {
                    expandedRoutes.addAll(nestedRoutes)
                }
            } else {
                expandedRoutes.add(route.copy(path = fullPath))
            }
        }

        return expandedRoutes
    }

    /**
     * Combine two path segments.
     */
    private fun combinePaths(
        prefix: String,
        path: String,
    ): String {
        val normalizedPrefix = prefix.trimEnd('/')
        val normalizedPath = path.trimStart('/')

        return when {
            normalizedPrefix.isEmpty() && normalizedPath.isEmpty() -> "/"
            normalizedPrefix.isEmpty() -> "/$normalizedPath"
            normalizedPath.isEmpty() -> normalizedPrefix
            else -> "$normalizedPrefix/$normalizedPath"
        }
    }
}

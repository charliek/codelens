package codelens.classgraph.ratpack

import codelens.classgraph.ClassGraphProvider
import codelens.core.model.ClassFilter
import codelens.core.model.ClassInfo
import codelens.core.model.ratpack.*
import org.slf4j.LoggerFactory

/**
 * Ratpack Chain method names that define routes.
 */
object ChainMethods {
    // HTTP method routes
    const val GET = "get"
    const val POST = "post"
    const val PUT = "put"
    const val PATCH = "patch"
    const val DELETE = "delete"
    const val OPTIONS = "options"
    const val HEAD = "head"
    const val ALL = "all"

    // Structure methods
    const val PREFIX = "prefix"
    const val PATH = "path"
    const val INSERT = "insert"
    const val REGISTER = "register"

    val HTTP_METHODS = setOf(GET, POST, PUT, PATCH, DELETE, OPTIONS, HEAD, ALL)
    val ALL_ROUTE_METHODS = HTTP_METHODS + setOf(PREFIX, PATH)
}

/**
 * Analyzes Ratpack route/chain definitions.
 *
 * Finds Action<Chain> implementations and extracts route patterns.
 */
class RouteAnalyzer(
    private val classGraphProvider: ClassGraphProvider
) {
    private val logger = LoggerFactory.getLogger(RouteAnalyzer::class.java)

    /**
     * Get summary of all routes in the project.
     */
    fun getRoutingSummary(includeLibraries: Boolean = false): RoutingSummary {
        val chainClasses = findChainClasses(includeLibraries)
        val allRoutes = mutableListOf<RouteInfo>()
        val chainInfos = mutableListOf<ChainClassInfo>()

        for (chainClass in chainClasses) {
            val routes = analyzeChainClass(chainClass)
            allRoutes.addAll(routes)

            chainInfos.add(
                ChainClassInfo(
                    fqn = chainClass.name.fqn,
                    simpleName = chainClass.name.simpleName,
                    routeCount = routes.size,
                    pathPrefix = extractPathPrefix(chainClass)
                )
            )
        }

        // Build method breakdown
        val routesByMethod = allRoutes.groupBy { it.method }
            .mapValues { it.value.size }

        return RoutingSummary(
            totalRoutes = allRoutes.size,
            routesByMethod = routesByMethod,
            routes = allRoutes,
            chainClasses = chainInfos,
            uniquePaths = allRoutes.map { it.pathPattern }.distinct().size
        )
    }

    /**
     * Find all Action<Chain> implementations.
     */
    private fun findChainClasses(includeLibraries: Boolean): List<ClassInfo> {
        val filter = ClassFilter(includeLibraries = includeLibraries)
        val classes = classGraphProvider.listClasses(filter)

        return classes.mapNotNull { classSummary ->
            val classInfo = classGraphProvider.getClass(classSummary.fqn) ?: return@mapNotNull null

            // Check if implements Action<Chain>
            val implementsChainAction = classInfo.interfaces.any { iface ->
                iface.contains(RatpackTypes.CHAIN_ACTION)
            }

            // Check if has execute(Chain) method
            val hasExecuteChain = classInfo.methods.any { method ->
                method.name == "execute" &&
                    method.parameters.size == 1 &&
                    method.parameters[0].type.contains(RatpackTypes.CHAIN)
            }

            if (implementsChainAction || hasExecuteChain) classInfo else null
        }
    }

    /**
     * Analyze a chain class to extract routes.
     * Since we can't analyze method bodies, we infer routes from class structure.
     */
    private fun analyzeChainClass(classInfo: ClassInfo): List<RouteInfo> {
        val routes = mutableListOf<RouteInfo>()

        // Find the execute method
        val executeMethod = classInfo.methods.find { method ->
            method.name == "execute" &&
                method.parameters.size == 1 &&
                method.parameters[0].type.contains(RatpackTypes.CHAIN)
        }

        if (executeMethod == null) return routes

        // Extract a base prefix from class name if following conventions
        val classPrefix = extractClassNamePrefix(classInfo.name.simpleName)

        // Look for Handler type fields that might be registered as routes
        val handlerFields = classInfo.fields.filter { field ->
            field.type.contains(RatpackTypes.HANDLER) ||
                field.type.contains("Handler")
        }

        // Look for constructor params that are handlers
        val handlerParams = classInfo.constructors.flatMap { it.parameters }
            .filter { param ->
                param.type.contains(RatpackTypes.HANDLER) ||
                    param.type.contains("Handler")
            }

        // Create route entries based on detected handlers
        val allHandlers = (handlerFields.map { it.type to it.name } +
            handlerParams.map { it.type to it.name }).distinctBy { it.first }

        if (allHandlers.isNotEmpty()) {
            // If we have handlers, create a route for each
            for ((handlerType, handlerName) in allHandlers) {
                val simpleName = handlerType.substringAfterLast(".")
                val inferredMethod = inferHttpMethodFromName(simpleName)

                routes.add(
                    RouteInfo(
                        method = inferredMethod,
                        pathPattern = classPrefix ?: "/*",
                        handlerFqn = handlerType,
                        handlerSimpleName = simpleName,
                        chainFqn = classInfo.name.fqn,
                        pathParameters = extractPathParameters(classPrefix ?: ""),
                        isPrefix = false
                    )
                )
            }
        } else {
            // No explicit handlers found, create a generic route
            // isPrefix should be false - this is a fallback route, not a detected prefix route
            routes.add(
                RouteInfo(
                    method = HttpMethod.ALL,
                    pathPattern = classPrefix ?: "/*",
                    handlerFqn = null,
                    handlerSimpleName = null,
                    chainFqn = classInfo.name.fqn,
                    pathParameters = extractPathParameters(classPrefix ?: ""),
                    isPrefix = false
                )
            )
        }

        return routes
    }

    /**
     * Infer HTTP method from handler class name.
     * Uses a heuristic based on common naming conventions:
     * - startsWith for HTTP verbs (get, post, put, patch, delete)
     * - Word boundary check for action words (fetch, list, create, update, remove)
     */
    private fun inferHttpMethodFromName(name: String): HttpMethod {
        val lowerName = name.lowercase()
        // Regex to check for word boundaries (word at start or preceded by non-letter)
        fun containsWord(word: String): Boolean {
            return lowerName.contains(Regex("(^|[^a-z])$word"))
        }
        return when {
            lowerName.startsWith("get") || containsWord("fetch") || containsWord("list") -> HttpMethod.GET
            lowerName.startsWith("post") || containsWord("create") -> HttpMethod.POST
            lowerName.startsWith("put") || containsWord("update") -> HttpMethod.PUT
            lowerName.startsWith("patch") -> HttpMethod.PATCH
            lowerName.startsWith("delete") || containsWord("remove") -> HttpMethod.DELETE
            else -> HttpMethod.ALL
        }
    }

    /**
     * Extract path prefix from execute method analysis.
     */
    private fun extractPathPrefix(classInfo: ClassInfo): String? {
        // Try to extract from class name convention (e.g., UsersChain -> /users)
        return extractClassNamePrefix(classInfo.name.simpleName)
    }

    /**
     * Extract path prefix from chain class name.
     * E.g., UsersChain -> /users, ApiRoutes -> /api
     */
    private fun extractClassNamePrefix(simpleName: String): String? {
        val baseName = simpleName
            .removeSuffix("Chain")
            .removeSuffix("Routes")
            .removeSuffix("Bindings")
            .removeSuffix("Action")

        if (baseName.isEmpty() || baseName == simpleName) return null

        // Convert PascalCase to path segment
        val path = baseName.fold(StringBuilder()) { acc, c ->
            if (c.isUpperCase() && acc.isNotEmpty()) {
                acc.append("-").append(c.lowercase())
            } else {
                acc.append(c.lowercase())
            }
            acc
        }.toString()

        return "/$path"
    }

    /**
     * Extract path parameters from a path pattern.
     */
    private fun extractPathParameters(pathPattern: String): List<PathParameter> {
        val params = mutableListOf<PathParameter>()
        val segments = pathPattern.split("/").filter { it.isNotBlank() }

        segments.forEachIndexed { index, segment ->
            when {
                // Ratpack style: :param
                segment.startsWith(":") -> {
                    params.add(
                        PathParameter(
                            name = segment.removePrefix(":"),
                            position = index,
                            optional = segment.endsWith("?")
                        )
                    )
                }
                // Alternative style: {param}
                segment.startsWith("{") && segment.endsWith("}") -> {
                    val paramName = segment.removeSurrounding("{", "}")
                    params.add(
                        PathParameter(
                            name = paramName.removeSuffix("?"),
                            position = index,
                            optional = paramName.endsWith("?")
                        )
                    )
                }
            }
        }

        return params
    }

    /**
     * Build a tree structure from routes.
     */
    fun buildRouteTree(routes: List<RouteInfo>): RouteTreeNode {
        val root = RouteTreeNode(
            segment = "",
            fullPath = "/",
            routes = routes.filter { it.pathPattern == "/" },
            children = mutableListOf()
        )

        // Group routes by first segment
        val routesByFirstSegment = routes
            .filter { it.pathPattern != "/" }
            .groupBy { route ->
                val segments = route.pathPattern.removePrefix("/").split("/")
                segments.firstOrNull() ?: ""
            }

        // Build children recursively
        val children = routesByFirstSegment.map { (segment, segmentRoutes) ->
            buildTreeNode(segment, "/$segment", segmentRoutes)
        }.sortedBy { it.segment }

        return root.copy(children = children)
    }

    private fun buildTreeNode(
        segment: String,
        fullPath: String,
        routes: List<RouteInfo>
    ): RouteTreeNode {
        // Routes that end at this path
        val directRoutes = routes.filter { route ->
            route.pathPattern.removePrefix("/").split("/").size == fullPath.removePrefix("/").split("/").size
        }

        // Routes that go deeper
        val deeperRoutes = routes.filter { route ->
            route.pathPattern.removePrefix("/").split("/").size > fullPath.removePrefix("/").split("/").size
        }

        // Group deeper routes by next segment
        val pathDepth = fullPath.removePrefix("/").split("/").size
        val routesByNextSegment = deeperRoutes.groupBy { route ->
            val segments = route.pathPattern.removePrefix("/").split("/")
            segments.getOrNull(pathDepth) ?: ""
        }

        val children = routesByNextSegment.map { (nextSegment, nextRoutes) ->
            buildTreeNode(nextSegment, "$fullPath/$nextSegment", nextRoutes)
        }.sortedBy { it.segment }

        return RouteTreeNode(
            segment = segment,
            fullPath = fullPath,
            routes = directRoutes,
            children = children
        )
    }

    /**
     * Generate Spring @RequestMapping equivalents for routes.
     */
    fun generateSpringMappings(routes: List<RouteInfo>): List<SpringMappingEquivalent> {
        return routes.map { route ->
            val annotation = generateSpringAnnotation(route)
            val methodSig = generateSpringMethodSignature(route)
            val notes = generateMigrationNotes(route)

            SpringMappingEquivalent(
                ratpackRoute = route,
                springAnnotation = annotation,
                methodSignature = methodSig,
                notes = notes
            )
        }
    }

    private fun generateSpringAnnotation(route: RouteInfo): String {
        val annotationName = when (route.method) {
            HttpMethod.GET -> "@GetMapping"
            HttpMethod.POST -> "@PostMapping"
            HttpMethod.PUT -> "@PutMapping"
            HttpMethod.PATCH -> "@PatchMapping"
            HttpMethod.DELETE -> "@DeleteMapping"
            HttpMethod.OPTIONS -> "@RequestMapping(method = RequestMethod.OPTIONS)"
            HttpMethod.HEAD -> "@RequestMapping(method = RequestMethod.HEAD)"
            HttpMethod.ALL -> "@RequestMapping"
        }

        // Convert Ratpack path to Spring path
        val springPath = convertPathToSpring(route.pathPattern)

        return if (springPath == "/") {
            annotationName
        } else {
            "$annotationName(\"$springPath\")"
        }
    }

    private fun convertPathToSpring(ratpackPath: String): String {
        // Convert :param to {param} (supports underscores in param names like :user_id)
        return ratpackPath.replace(Regex(":([a-zA-Z][a-zA-Z0-9_]*)")) { match ->
            "{${match.groupValues[1]}}"
        }
    }

    private fun generateSpringMethodSignature(route: RouteInfo): String {
        val methodName = route.handlerSimpleName?.let { handler ->
            handler.removeSuffix("Handler")
                .replaceFirstChar { it.lowercase() }
        } ?: generateMethodNameFromPath(route.pathPattern, route.method)

        val params = route.pathParameters.joinToString(", ") { param ->
            "@PathVariable ${param.name}: String"
        }

        return "fun $methodName($params): ResponseEntity<*>"
    }

    private fun generateMethodNameFromPath(path: String, method: HttpMethod): String {
        val segments = path.split("/")
            .filter { it.isNotBlank() && !it.startsWith(":") && !it.startsWith("{") }

        val baseName = segments.lastOrNull()?.replaceFirstChar { it.uppercase() } ?: "Root"

        val prefix = when (method) {
            HttpMethod.GET -> "get"
            HttpMethod.POST -> "create"
            HttpMethod.PUT -> "update"
            HttpMethod.PATCH -> "patch"
            HttpMethod.DELETE -> "delete"
            else -> "handle"
        }

        return "$prefix$baseName"
    }

    private fun generateMigrationNotes(route: RouteInfo): List<String> {
        val notes = mutableListOf<String>()

        if (route.pathParameters.isNotEmpty()) {
            notes.add("Contains ${route.pathParameters.size} path parameter(s)")
        }

        if (route.method == HttpMethod.ALL) {
            notes.add("Handles all HTTP methods - consider splitting into specific mappings")
        }

        if (route.isPrefix) {
            notes.add("This is a prefix route - nested routes should be moved to the same controller")
        }

        return notes
    }
}

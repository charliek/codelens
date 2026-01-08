package codelens.core.model.ratpack

import kotlinx.serialization.Serializable

// ============================================================================
// Route/Chain Analysis Models
// ============================================================================

/**
 * HTTP methods used in Ratpack routes.
 */
@Serializable
enum class HttpMethod {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE,
    OPTIONS,
    HEAD,
    ALL  // Represents any HTTP method
}

/**
 * A path parameter extracted from a route pattern.
 */
@Serializable
data class PathParameter(
    /** Parameter name (e.g., "id" from ":id" or "{id}") */
    val name: String,
    /** Position in the path segments (0-indexed) */
    val position: Int,
    /** Whether this is optional */
    val optional: Boolean = false
)

/**
 * A single route defined in the application.
 */
@Serializable
data class RouteInfo(
    /** HTTP method (GET, POST, etc.) */
    val method: HttpMethod,
    /** Full path pattern (e.g., "/api/users/:id") */
    val pathPattern: String,
    /** Handler class FQN that handles this route */
    val handlerFqn: String?,
    /** Handler simple name for display */
    val handlerSimpleName: String?,
    /** Chain class where this route is defined */
    val chainFqn: String,
    /** Path parameters in this route */
    val pathParameters: List<PathParameter>,
    /** Whether this is a prefix route */
    val isPrefix: Boolean = false,
    /** Nested routes under this prefix */
    val nestedRoutes: List<RouteInfo> = emptyList()
)

/**
 * A node in the route tree structure.
 */
@Serializable
data class RouteTreeNode(
    /** Path segment (e.g., "users", ":id") */
    val segment: String,
    /** Full path up to this node */
    val fullPath: String,
    /** Routes at this exact path */
    val routes: List<RouteInfo>,
    /** Child nodes */
    val children: List<RouteTreeNode>
)

/**
 * Summary of all routes in the application.
 */
@Serializable
data class RoutingSummary(
    /** Total number of routes */
    val totalRoutes: Int,
    /** Routes by HTTP method */
    val routesByMethod: Map<HttpMethod, Int>,
    /** All routes in flat list */
    val routes: List<RouteInfo>,
    /** All chain classes that define routes */
    val chainClasses: List<ChainClassInfo>,
    /** Number of unique paths */
    val uniquePaths: Int
)

/**
 * Information about a Chain class.
 */
@Serializable
data class ChainClassInfo(
    /** Fully qualified class name */
    val fqn: String,
    /** Simple class name */
    val simpleName: String,
    /** Number of routes defined in this chain */
    val routeCount: Int,
    /** Path prefix if any */
    val pathPrefix: String?
)

/**
 * Spring @RequestMapping equivalent for a route.
 */
@Serializable
data class SpringMappingEquivalent(
    /** Original Ratpack route */
    val ratpackRoute: RouteInfo,
    /** Equivalent Spring annotation */
    val springAnnotation: String,
    /** Controller method signature suggestion */
    val methodSignature: String,
    /** Notes about the mapping */
    val notes: List<String> = emptyList()
)

/**
 * Response containing Spring mapping equivalents.
 */
@Serializable
data class SpringMappingsResponse(
    /** All route mappings */
    val mappings: List<SpringMappingEquivalent>,
    /** Total count */
    val totalCount: Int
)

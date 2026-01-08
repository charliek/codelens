package codelens.server.routes

import codelens.core.model.ErrorResponse
import codelens.core.model.ratpack.*
import codelens.server.services.RatpackAnalysisService
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

// ============================================================================
// Response Models
// ============================================================================

@Serializable
data class HandlerListResponse(
    val handlers: List<HandlerSummary>,
    val totalCount: Int,
    val appliedFilters: HandlerFilterSummary
)

@Serializable
data class HandlerFilterSummary(
    val handlerType: String?,
    val tier: String?
)

@Serializable
data class HandlerDetailResponse(
    val handler: HandlerInfo
)

@Serializable
data class PromiseSummaryResponse(
    val summary: PromiseSummary
)

@Serializable
data class PromiseUsageResponse(
    val usage: PromiseUsageInfo
)

@Serializable
data class PromiseSearchResponse(
    val results: List<PromiseUsageInfo>,
    val totalCount: Int
)

@Serializable
data class ComplexitySummaryResponse(
    val summary: ComplexitySummary
)

@Serializable
data class ComplexityDetailResponse(
    val complexity: ComplexityResult
)

@Serializable
data class MigrationOrderResponse(
    val order: List<MigrationOrderItem>,
    val totalCount: Int,
    val totalEstimatedHours: Double
)

@Serializable
data class ModuleListResponse(
    val modules: List<GuiceModuleSummary>,
    val totalCount: Int
)

@Serializable
data class ModuleDetailResponse(
    val module: GuiceModuleInfo
)

@Serializable
data class BindingSearchResponse(
    val typeFqn: String,
    val bindings: List<TypeBindingResult>,
    val totalCount: Int
)

@Serializable
data class TypeBindingResult(
    val moduleFqn: String,
    val binding: GuiceBinding
)

@Serializable
data class IntegrationsResponse(
    val summary: ProjectIntegrationSummary,
    val filter: IntegrationFilterApplied? = null
)

@Serializable
data class ClassIntegrationsDetailResponse(
    val classIntegrations: ClassIntegrations
)

@Serializable
data class IntegrationsByTypeResponse(
    val type: IntegrationType,
    val subType: IntegrationSubType? = null,
    val classes: List<ClassIntegrations>,
    val totalCount: Int
)

// ============================================================================
// Routes
// ============================================================================

/**
 * Routes for Ratpack-specific analysis endpoints.
 */
fun Route.ratpackRoutes(ratpackService: RatpackAnalysisService) {
    route("/api/v1/ratpack") {
        // =====================================================================
        // Handler Endpoints
        // =====================================================================

        /**
         * GET /api/v1/ratpack/handlers
         * List all Ratpack handlers.
         *
         * Query parameters:
         * - type: Filter by handler type (HANDLER, CHAIN_ACTION, INLINE_HANDLER, GROOVY_HANDLER)
         * - tier: Filter by complexity tier (LOW, MEDIUM, HIGH, CRITICAL)
         * - includeLibraries: Include library handlers (default: false)
         */
        get("/handlers") {
            val typeParam = call.request.queryParameters["type"]
            val tierParam = call.request.queryParameters["tier"]
            val includeLibraries = call.request.queryParameters["includeLibraries"]?.toBoolean() ?: false

            // Validate type parameter
            val handlerType = if (typeParam != null) {
                try {
                    HandlerType.valueOf(typeParam.uppercase())
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            code = 400,
                            type = "BadRequest",
                            message = "Invalid handler type: $typeParam. Valid values: ${HandlerType.entries.joinToString()}"
                        )
                    )
                    return@get
                }
            } else null

            // Validate tier parameter
            val tier = if (tierParam != null) {
                try {
                    ComplexityTier.valueOf(tierParam.uppercase())
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            code = 400,
                            type = "BadRequest",
                            message = "Invalid complexity tier: $tierParam. Valid values: ${ComplexityTier.entries.joinToString()}"
                        )
                    )
                    return@get
                }
            } else null

            val handlers = ratpackService.listHandlers(
                handlerType = handlerType,
                tier = tier,
                includeLibraries = includeLibraries
            )

            call.respond(
                HandlerListResponse(
                    handlers = handlers,
                    totalCount = handlers.size,
                    appliedFilters = HandlerFilterSummary(
                        handlerType = typeParam,
                        tier = tierParam
                    )
                )
            )
        }

        /**
         * GET /api/v1/ratpack/handlers/{fqn}
         * Get detailed information about a handler.
         */
        get("/handlers/{fqn...}") {
            val fqn = call.parameters.getAll("fqn")?.joinToString(".")
            if (fqn.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(code = 400, type = "BadRequest", message = "Handler FQN is required")
                )
                return@get
            }

            val handler = ratpackService.getHandlerDetail(fqn)
            if (handler != null) {
                call.respond(HandlerDetailResponse(handler = handler))
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse(code = 404, type = "NotFound", message = "Handler not found: $fqn")
                )
            }
        }

        // =====================================================================
        // Promise Endpoints
        // =====================================================================

        /**
         * GET /api/v1/ratpack/promises
         * Get project-wide Promise usage summary.
         *
         * Query parameters:
         * - includeLibraries: Include library classes (default: false)
         */
        get("/promises") {
            val includeLibraries = call.request.queryParameters["includeLibraries"]?.toBoolean() ?: false

            val summary = ratpackService.getPromiseSummary(includeLibraries)
            call.respond(PromiseSummaryResponse(summary = summary))
        }

        /**
         * GET /api/v1/ratpack/promises/search
         * Search for classes with specific Promise usage patterns.
         *
         * Query parameters:
         * - usesBlocking: Filter for classes using Blocking (true/false)
         * - usesAsync: Filter for classes using async (true/false)
         * - usesFork: Filter for classes using fork (true/false)
         * - minOperations: Minimum operation count (default: 0)
         */
        get("/promises/search") {
            val usesBlocking = call.request.queryParameters["usesBlocking"]?.toBoolean()
            val usesAsync = call.request.queryParameters["usesAsync"]?.toBoolean()
            val usesFork = call.request.queryParameters["usesFork"]?.toBoolean()
            val minOperations = call.request.queryParameters["minOperations"]?.toIntOrNull() ?: 0

            val results = ratpackService.searchPromiseUsage(
                usesBlocking = usesBlocking,
                usesAsync = usesAsync,
                usesFork = usesFork,
                minOperations = minOperations
            )

            call.respond(
                PromiseSearchResponse(
                    results = results,
                    totalCount = results.size
                )
            )
        }

        /**
         * GET /api/v1/ratpack/promises/{fqn}
         * Get Promise usage for a specific class.
         */
        get("/promises/{fqn...}") {
            val fqn = call.parameters.getAll("fqn")?.joinToString(".")
            if (fqn.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(code = 400, type = "BadRequest", message = "Class FQN is required")
                )
                return@get
            }

            val usage = ratpackService.getPromiseUsage(fqn)
            call.respond(PromiseUsageResponse(usage = usage))
        }

        // =====================================================================
        // Complexity Endpoints
        // =====================================================================

        /**
         * GET /api/v1/ratpack/complexity
         * Get project-wide complexity summary.
         */
        get("/complexity") {
            val summary = ratpackService.getComplexitySummary()
            call.respond(ComplexitySummaryResponse(summary = summary))
        }

        /**
         * GET /api/v1/ratpack/complexity/{fqn}
         * Get complexity score for a specific class.
         */
        get("/complexity/{fqn...}") {
            val fqn = call.parameters.getAll("fqn")?.joinToString(".")
            if (fqn.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(code = 400, type = "BadRequest", message = "Class FQN is required")
                )
                return@get
            }

            val complexity = ratpackService.getComplexity(fqn)
            call.respond(ComplexityDetailResponse(complexity = complexity))
        }

        /**
         * GET /api/v1/ratpack/migration-order
         * Get suggested migration order.
         */
        get("/migration-order") {
            val summary = ratpackService.getComplexitySummary()
            call.respond(
                MigrationOrderResponse(
                    order = summary.migrationOrder,
                    totalCount = summary.migrationOrder.size,
                    totalEstimatedHours = summary.totalEstimatedHours
                )
            )
        }

        // =====================================================================
        // Guice Module Endpoints
        // =====================================================================

        /**
         * GET /api/v1/ratpack/modules
         * List all Guice modules.
         *
         * Query parameters:
         * - includeLibraries: Include library modules (default: false)
         */
        get("/modules") {
            val includeLibraries = call.request.queryParameters["includeLibraries"]?.toBoolean() ?: false

            val modules = ratpackService.listModules(includeLibraries)
            call.respond(
                ModuleListResponse(
                    modules = modules,
                    totalCount = modules.size
                )
            )
        }

        /**
         * GET /api/v1/ratpack/modules/{fqn}
         * Get detailed information about a Guice module.
         */
        get("/modules/{fqn...}") {
            val fqn = call.parameters.getAll("fqn")?.joinToString(".")
            if (fqn.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(code = 400, type = "BadRequest", message = "Module FQN is required")
                )
                return@get
            }

            val module = ratpackService.getModuleDetail(fqn)
            if (module != null) {
                call.respond(ModuleDetailResponse(module = module))
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse(code = 404, type = "NotFound", message = "Module not found: $fqn")
                )
            }
        }

        /**
         * GET /api/v1/ratpack/bindings/{fqn}
         * Find all bindings for a specific type.
         */
        get("/bindings/{fqn...}") {
            val fqn = call.parameters.getAll("fqn")?.joinToString(".")
            if (fqn.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(code = 400, type = "BadRequest", message = "Type FQN is required")
                )
                return@get
            }

            val bindings = ratpackService.findBindingsForType(fqn)
            call.respond(
                BindingSearchResponse(
                    typeFqn = fqn,
                    bindings = bindings.map { (moduleFqn, binding) ->
                        TypeBindingResult(moduleFqn = moduleFqn, binding = binding)
                    },
                    totalCount = bindings.size
                )
            )
        }

        // =====================================================================
        // Integration Endpoints
        // =====================================================================

        /**
         * GET /api/v1/ratpack/integrations
         * Get project-wide integration summary.
         *
         * Query parameters:
         * - type: Filter by integration type (HTTP_CLIENT, DATABASE, etc.)
         * - subType: Filter by sub-type (RATPACK_HTTP_CLIENT, DYNAMODB, etc.)
         * - includeLibraries: Include library classes (default: false)
         */
        get("/integrations") {
            val typeParam = call.request.queryParameters["type"]
            val subTypeParam = call.request.queryParameters["subType"]
            val includeLibraries = call.request.queryParameters["includeLibraries"]?.toBoolean() ?: false

            // If filtering by type, use findByType
            if (typeParam != null) {
                val type = try {
                    IntegrationType.valueOf(typeParam.uppercase())
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            code = 400,
                            type = "BadRequest",
                            message = "Invalid integration type: $typeParam. Valid values: ${IntegrationType.entries.joinToString()}"
                        )
                    )
                    return@get
                }

                val subType = if (subTypeParam != null) {
                    try {
                        IntegrationSubType.valueOf(subTypeParam.uppercase())
                    } catch (e: IllegalArgumentException) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(
                                code = 400,
                                type = "BadRequest",
                                message = "Invalid integration sub-type: $subTypeParam"
                            )
                        )
                        return@get
                    }
                } else null

                val classes = ratpackService.findIntegrationsByType(type, subType, includeLibraries)
                call.respond(
                    IntegrationsByTypeResponse(
                        type = type,
                        subType = subType,
                        classes = classes,
                        totalCount = classes.size
                    )
                )
            } else {
                // Return full summary
                val summary = ratpackService.getIntegrationsSummary(includeLibraries)
                call.respond(IntegrationsResponse(summary = summary))
            }
        }

        /**
         * GET /api/v1/ratpack/integrations/{fqn}
         * Get integrations for a specific class.
         */
        get("/integrations/{fqn...}") {
            val fqn = call.parameters.getAll("fqn")?.joinToString(".")
            if (fqn.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(code = 400, type = "BadRequest", message = "Class FQN is required")
                )
                return@get
            }

            val classIntegrations = ratpackService.getClassIntegrations(fqn)
            if (classIntegrations != null) {
                call.respond(ClassIntegrationsDetailResponse(classIntegrations = classIntegrations))
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse(code = 404, type = "NotFound", message = "Class not found: $fqn")
                )
            }
        }

        /**
         * GET /api/v1/ratpack/integrations/by-type/{type}
         * Find classes by integration type.
         *
         * Query parameters:
         * - subType: Filter by sub-type
         * - includeLibraries: Include library classes (default: false)
         */
        get("/integrations/by-type/{type}") {
            val typeParam = call.parameters["type"]
            if (typeParam.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(code = 400, type = "BadRequest", message = "Integration type is required")
                )
                return@get
            }

            val type = try {
                IntegrationType.valueOf(typeParam.uppercase())
            } catch (e: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        code = 400,
                        type = "BadRequest",
                        message = "Invalid integration type: $typeParam. Valid values: ${IntegrationType.entries.joinToString()}"
                    )
                )
                return@get
            }

            val subTypeParam = call.request.queryParameters["subType"]
            val subType = if (subTypeParam != null) {
                try {
                    IntegrationSubType.valueOf(subTypeParam.uppercase())
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            code = 400,
                            type = "BadRequest",
                            message = "Invalid integration sub-type: $subTypeParam"
                        )
                    )
                    return@get
                }
            } else null

            val includeLibraries = call.request.queryParameters["includeLibraries"]?.toBoolean() ?: false
            val classes = ratpackService.findIntegrationsByType(type, subType, includeLibraries)

            call.respond(
                IntegrationsByTypeResponse(
                    type = type,
                    subType = subType,
                    classes = classes,
                    totalCount = classes.size
                )
            )
        }
    }
}

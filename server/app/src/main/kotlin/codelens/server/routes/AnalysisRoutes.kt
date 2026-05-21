package codelens.server.routes

import codelens.core.model.*
import codelens.server.services.AnalysisService
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Helper to extract FQN from path parameters and respond with error if missing.
 * Returns null if FQN is missing (and responds with 400), otherwise returns the FQN.
 */
private suspend fun RoutingContext.getFqnOrRespond(
    paramName: String = "fqn",
    errorMessage: String = "Class FQN is required",
): String? {
    val fqn = call.parameters.getAll(paramName)?.joinToString(".")
    if (fqn.isNullOrBlank()) {
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(
                code = 400,
                type = "BadRequest",
                message = errorMessage,
            ),
        )
        return null
    }
    return fqn
}

/**
 * Routes for bytecode analysis endpoints.
 */
fun Route.analysisRoutes(analysisService: AnalysisService) {
    route("/api/v1") {
        /**
         * GET /api/v1/stats
         * Get scan statistics.
         */
        get("/stats") {
            val stats = analysisService.getStatistics()
            if (stats != null) {
                call.respond(stats)
            } else {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse(
                        code = 503,
                        type = "ScanNotReady",
                        message = "Scan has not completed yet",
                    ),
                )
            }
        }

        /**
         * GET /api/v1/classes
         * List classes with optional filtering.
         *
         * Query parameters:
         * - package: Filter by package pattern (supports * wildcard)
         * - name: Filter by class name pattern (supports * wildcard)
         * - annotation: Filter to classes with this annotation
         * - extends: Filter to classes extending this class
         * - implements: Filter to classes implementing this interface
         * - interfaces: Only show interfaces (true/false)
         * - includeLibraries: Include library classes (default: false)
         * - page: Page number (0-based, default: 0)
         * - size: Page size (default: 50)
         */
        get("/classes") {
            val packagePattern = call.request.queryParameters["package"]
            val namePattern = call.request.queryParameters["name"]
            val annotation = call.request.queryParameters["annotation"]
            val extendsClass = call.request.queryParameters["extends"]
            val implementsInterface = call.request.queryParameters["implements"]
            val onlyInterfaces = call.request.queryParameters["interfaces"]?.toBoolean() ?: false
            val includeLibraries = call.request.queryParameters["includeLibraries"]?.toBoolean() ?: false
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
            val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 50

            val filter =
                ClassFilter(
                    packagePattern = packagePattern,
                    namePattern = namePattern,
                    hasAnnotation = annotation,
                    extendsClass = extendsClass,
                    implementsInterface = implementsInterface,
                    onlyInterfaces = onlyInterfaces,
                    includeLibraries = includeLibraries,
                )

            val allClasses = analysisService.listClasses(filter)
            val totalCount = allClasses.size
            val totalPages = if (totalCount == 0) 1 else (totalCount + size - 1) / size

            // Apply pagination
            val startIndex = page * size
            val endIndex = minOf(startIndex + size, totalCount)
            val pagedClasses =
                if (startIndex < totalCount) {
                    allClasses.subList(startIndex, endIndex)
                } else {
                    emptyList()
                }

            val response =
                ClassListResponse(
                    classes = pagedClasses,
                    totalCount = totalCount,
                    page = page,
                    pageSize = size,
                    totalPages = totalPages,
                    appliedFilter =
                        ClassFilterSummary(
                            packagePattern = packagePattern,
                            namePattern = namePattern,
                            source = if (includeLibraries) null else "PROJECT",
                            hasAnnotation = annotation,
                            extendsClass = extendsClass,
                            implementsInterface = implementsInterface,
                        ),
                )

            call.respond(response)
        }

        /**
         * GET /api/v1/classes/{fqn}
         * Get full details for a specific class.
         */
        get("/classes/{fqn...}") {
            val fqn = getFqnOrRespond() ?: return@get

            val classInfo = analysisService.getClass(fqn)
            if (classInfo != null) {
                call.respond(ClassDetailResponse(classInfo = classInfo))
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse(
                        code = 404,
                        type = "NotFound",
                        message = "Class not found: $fqn",
                    ),
                )
            }
        }

        /**
         * GET /api/v1/implementations/{fqn}
         * Find all implementations of an interface or subclasses of a class.
         *
         * Query parameters:
         * - includeLibraries: Include library classes (default: false)
         */
        get("/implementations/{fqn...}") {
            val fqn = getFqnOrRespond() ?: return@get

            val includeLibraries = call.request.queryParameters["includeLibraries"]?.toBoolean() ?: false

            val (direct, indirect) = analysisService.getImplementations(fqn, includeLibraries)

            call.respond(
                ImplementationsResponse(
                    targetClass = fqn,
                    directImplementations = direct,
                    indirectImplementations = indirect,
                    totalCount = direct.size + indirect.size,
                ),
            )
        }

        /**
         * GET /api/v1/hierarchy/{fqn}
         * Get the class hierarchy for a given class.
         */
        get("/hierarchy/{fqn...}") {
            val fqn = getFqnOrRespond() ?: return@get

            val hierarchy = analysisService.getHierarchy(fqn)
            if (hierarchy != null) {
                call.respond(HierarchyResponse(targetClass = fqn, hierarchy = hierarchy))
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse(
                        code = 404,
                        type = "NotFound",
                        message = "Class not found: $fqn",
                    ),
                )
            }
        }

        /**
         * GET /api/v1/dependencies/{fqn}
         * Get dependencies for a class.
         *
         * Query parameters:
         * - includeLibraries: Include library classes (default: false)
         */
        get("/dependencies/{fqn...}") {
            val fqn = getFqnOrRespond() ?: return@get

            val includeLibraries = call.request.queryParameters["includeLibraries"]?.toBoolean() ?: false

            val (outgoing, incoming) = analysisService.getDependencies(fqn, includeLibraries)

            call.respond(
                DependenciesResponse(
                    targetClass = fqn,
                    outgoing = outgoing,
                    incoming = incoming,
                ),
            )
        }

        /**
         * GET /api/v1/annotations/usages/{fqn}
         * Find all classes using a specific annotation.
         *
         * Query parameters:
         * - includeLibraries: Include library classes (default: false)
         */
        get("/annotations/usages/{fqn...}") {
            val fqn = getFqnOrRespond(errorMessage = "Annotation FQN is required") ?: return@get

            val includeLibraries = call.request.queryParameters["includeLibraries"]?.toBoolean() ?: false

            val usages = analysisService.getAnnotationUsages(fqn, includeLibraries)

            call.respond(
                AnnotationUsagesResponse(
                    annotationFqn = fqn,
                    usages = usages,
                    totalCount = usages.size,
                ),
            )
        }

        /**
         * GET /api/v1/methods
         * Search methods across all classes.
         *
         * Query parameters:
         * - name: Filter by method name pattern (supports * wildcard)
         * - returnType: Filter by return type FQN
         * - annotation: Filter to methods with this annotation
         * - inClass: Filter by containing class FQN
         * - inPackage: Filter by containing package pattern
         * - includeLibraries: Include library classes (default: false)
         * - page: Page number (0-based, default: 0)
         * - size: Page size (default: 50)
         */
        get("/methods") {
            val namePattern = call.request.queryParameters["name"]
            val returnType = call.request.queryParameters["returnType"]
            val annotation = call.request.queryParameters["annotation"]
            val inClass = call.request.queryParameters["inClass"]
            val inPackage = call.request.queryParameters["inPackage"]
            val includeLibraries = call.request.queryParameters["includeLibraries"]?.toBoolean() ?: false
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
            val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 50

            val filter =
                MethodFilter(
                    namePattern = namePattern,
                    returnType = returnType,
                    hasAnnotation = annotation,
                    inClass = inClass,
                    inPackage = inPackage,
                    includeLibraries = includeLibraries,
                )

            val allMethods = analysisService.searchMethods(filter)
            val totalCount = allMethods.size
            val totalPages = if (totalCount == 0) 1 else (totalCount + size - 1) / size

            // Apply pagination
            val startIndex = page * size
            val endIndex = minOf(startIndex + size, totalCount)
            val pagedMethods =
                if (startIndex < totalCount) {
                    allMethods.subList(startIndex, endIndex)
                } else {
                    emptyList()
                }

            call.respond(
                MethodSearchResponse(
                    methods = pagedMethods,
                    totalCount = totalCount,
                    page = page,
                    pageSize = size,
                    totalPages = totalPages,
                ),
            )
        }
    }
}

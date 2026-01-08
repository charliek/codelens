package codelens.server.routes

import codelens.core.model.ErrorResponse
import codelens.core.model.source.*
import codelens.server.services.AnalysisService
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Response wrapper for source code.
 */
@kotlinx.serialization.Serializable
data class SourceResponse(
    val source: SourceInfo
)

/**
 * Response wrapper for method source code.
 */
@kotlinx.serialization.Serializable
data class MethodSourceResponse(
    val methodSource: MethodSourceInfo
)

/**
 * Routes for source code retrieval endpoints.
 */
fun Route.sourceRoutes(analysisService: AnalysisService) {
    route("/api/v1/source") {
        /**
         * GET /api/v1/source/{fqn}/method/{methodName}
         * Get source code for a specific method.
         *
         * Path parameters:
         * - fqn: URL-encoded fully qualified class name
         * - methodName: Method name
         *
         * Query parameters:
         * - paramTypes: Comma-separated parameter types for disambiguation (optional)
         * - context: Number of context lines before/after method (default: 0)
         */
        get("/{fqn}/method/{methodName}") {
            val fqn = call.parameters["fqn"]
            val methodName = call.parameters["methodName"]

            if (fqn.isNullOrBlank() || methodName.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        code = 400,
                        type = "BadRequest",
                        message = "Class FQN and method name are required"
                    )
                )
                return@get
            }

            val paramTypesStr = call.request.queryParameters["paramTypes"]
            val paramTypes = paramTypesStr?.split(",")?.map { it.trim() }
            val contextLines = call.request.queryParameters["context"]?.toIntOrNull() ?: 0

            val result = analysisService.getMethodSource(fqn, methodName, paramTypes, contextLines)

            result.fold(
                onSuccess = { methodSourceInfo ->
                    call.respond(MethodSourceResponse(methodSource = methodSourceInfo))
                },
                onFailure = { exception ->
                    when (exception) {
                        is SourceResolutionException -> {
                            val statusCode = when (exception.reason) {
                                SourceResolutionErrorReason.CLASS_NOT_FOUND,
                                SourceResolutionErrorReason.METHOD_NOT_FOUND,
                                SourceResolutionErrorReason.FILE_NOT_FOUND -> HttpStatusCode.NotFound
                                SourceResolutionErrorReason.LIBRARY_CLASS,
                                SourceResolutionErrorReason.JDK_CLASS -> HttpStatusCode.UnprocessableEntity
                            }
                            call.respond(
                                statusCode,
                                SourceResolutionError(
                                    fqn = exception.fqn,
                                    reason = exception.reason,
                                    message = exception.message ?: "Source resolution failed"
                                )
                            )
                        }
                        else -> {
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ErrorResponse(
                                    code = 500,
                                    type = "InternalError",
                                    message = exception.message ?: "Failed to resolve method source"
                                )
                            )
                        }
                    }
                }
            )
        }

        /**
         * GET /api/v1/source/{fqn}
         * Get source code for a class.
         *
         * Path parameter:
         * - fqn: URL-encoded fully qualified class name (e.g., com.example.MyClass)
         *
         * Returns 404 if class not found or source not available.
         */
        get("/{fqn}") {
            val fqn = call.parameters["fqn"]
            if (fqn.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        code = 400,
                        type = "BadRequest",
                        message = "Class FQN is required"
                    )
                )
                return@get
            }

            val result = analysisService.getSource(fqn)

            result.fold(
                onSuccess = { sourceInfo ->
                    call.respond(SourceResponse(source = sourceInfo))
                },
                onFailure = { exception ->
                    when (exception) {
                        is SourceResolutionException -> {
                            val statusCode = when (exception.reason) {
                                SourceResolutionErrorReason.CLASS_NOT_FOUND -> HttpStatusCode.NotFound
                                SourceResolutionErrorReason.LIBRARY_CLASS,
                                SourceResolutionErrorReason.JDK_CLASS -> HttpStatusCode.UnprocessableEntity
                                SourceResolutionErrorReason.FILE_NOT_FOUND -> HttpStatusCode.NotFound
                                SourceResolutionErrorReason.METHOD_NOT_FOUND -> HttpStatusCode.NotFound
                            }
                            call.respond(
                                statusCode,
                                SourceResolutionError(
                                    fqn = exception.fqn,
                                    reason = exception.reason,
                                    message = exception.message ?: "Source resolution failed"
                                )
                            )
                        }
                        else -> {
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ErrorResponse(
                                    code = 500,
                                    type = "InternalError",
                                    message = exception.message ?: "Failed to resolve source"
                                )
                            )
                        }
                    }
                }
            )
        }
    }
}

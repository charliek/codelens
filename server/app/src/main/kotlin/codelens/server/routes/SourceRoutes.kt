package codelens.server.routes

import codelens.core.model.ErrorResponse
import codelens.core.model.source.*
import codelens.server.services.AnalysisService
import codelens.source.model.StubLanguage
import codelens.source.model.VisibilityFilter
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Response wrapper for source code.
 */
@kotlinx.serialization.Serializable
data class SourceResponse(
    val source: SourceInfo,
)

/**
 * Response wrapper for method source code.
 */
@kotlinx.serialization.Serializable
data class MethodSourceResponse(
    val methodSource: MethodSourceInfo,
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
                        message = "Class FQN and method name are required",
                    ),
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
                            val statusCode =
                                when (exception.reason) {
                                    SourceResolutionErrorReason.CLASS_NOT_FOUND,
                                    SourceResolutionErrorReason.METHOD_NOT_FOUND,
                                    SourceResolutionErrorReason.FILE_NOT_FOUND,
                                    -> HttpStatusCode.NotFound
                                    SourceResolutionErrorReason.LIBRARY_CLASS,
                                    SourceResolutionErrorReason.JDK_CLASS,
                                    -> HttpStatusCode.UnprocessableEntity
                                }
                            call.respond(
                                statusCode,
                                SourceResolutionError(
                                    fqn = exception.fqn,
                                    reason = exception.reason,
                                    message = exception.message ?: "Source resolution failed",
                                ),
                            )
                        }
                        else -> {
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ErrorResponse(
                                    code = 500,
                                    type = "InternalError",
                                    message = exception.message ?: "Failed to resolve method source",
                                ),
                            )
                        }
                    }
                },
            )
        }

        /**
         * GET /api/v1/source/{fqn}
         * Get source code for a class.
         *
         * Supports project classes, library classes (from source JARs or decompilation),
         * and JDK classes (from src.zip).
         *
         * Path parameter:
         * - fqn: URL-encoded fully qualified class name (e.g., com.example.MyClass)
         *
         * Query parameters:
         * - allowDecompilation: Allow decompilation fallback when source unavailable (default: true)
         * - forceRefresh: Force re-download of source JARs (default: false)
         * - format: Output format - full, stub, signatures, javadoc (default: full) [not yet implemented]
         * - visibility: Filter by visibility - all, public, protected (default: all) [not yet implemented]
         * - lang: Stub language - java, kotlin (only applies to stub format) [not yet implemented]
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
                        message = "Class FQN is required",
                    ),
                )
                return@get
            }

            // Parse query parameters
            val allowDecompilation = call.request.queryParameters["allowDecompilation"]?.toBoolean() ?: true
            val forceRefresh = call.request.queryParameters["forceRefresh"]?.toBoolean() ?: false

            // Format parameters for stub generation
            val formatStr = call.request.queryParameters["format"]?.lowercase() ?: "full"
            val visibilityStr = call.request.queryParameters["visibility"]?.lowercase() ?: "all"
            val langStr = call.request.queryParameters["lang"]?.lowercase() ?: "java"

            val format =
                when (formatStr) {
                    "stub" -> SourceFormat.STUB
                    "signatures" -> SourceFormat.SIGNATURES
                    "javadoc" -> SourceFormat.JAVADOC
                    else -> SourceFormat.FULL
                }

            val visibility =
                when (visibilityStr) {
                    "public" -> VisibilityFilter.PUBLIC
                    "protected" -> VisibilityFilter.PUBLIC_PROTECTED
                    else -> VisibilityFilter.ALL
                }

            val language =
                when (langStr) {
                    "kotlin", "kt" -> StubLanguage.KOTLIN
                    else -> StubLanguage.JAVA
                }

            // Use appropriate method based on format
            val result =
                when (format) {
                    SourceFormat.STUB, SourceFormat.SIGNATURES -> {
                        // Use stub generator (works from bytecode, no source needed)
                        analysisService.getStub(
                            fqn = fqn,
                            language = language,
                            visibility = visibility,
                            format = format,
                        )
                    }
                    SourceFormat.JAVADOC -> {
                        // Use javadoc extractor (requires actual source)
                        analysisService.getSourceWithJavadoc(
                            fqn = fqn,
                            visibility = visibility,
                            allowDecompilation = allowDecompilation,
                            forceRefresh = forceRefresh,
                        )
                    }
                    else -> {
                        // Full source
                        analysisService.getSource(
                            fqn = fqn,
                            allowDecompilation = allowDecompilation,
                            forceRefresh = forceRefresh,
                        )
                    }
                }

            result.fold(
                onSuccess = { sourceInfo ->
                    call.respond(SourceResponse(source = sourceInfo))
                },
                onFailure = { exception ->
                    when (exception) {
                        is SourceResolutionException -> {
                            val statusCode =
                                when (exception.reason) {
                                    SourceResolutionErrorReason.CLASS_NOT_FOUND -> HttpStatusCode.NotFound
                                    SourceResolutionErrorReason.FILE_NOT_FOUND -> HttpStatusCode.NotFound
                                    SourceResolutionErrorReason.METHOD_NOT_FOUND -> HttpStatusCode.NotFound
                                    // Library/JDK classes now return 404 only if source truly unavailable
                                    SourceResolutionErrorReason.LIBRARY_CLASS,
                                    SourceResolutionErrorReason.JDK_CLASS,
                                    -> HttpStatusCode.NotFound
                                }
                            call.respond(
                                statusCode,
                                SourceResolutionError(
                                    fqn = exception.fqn,
                                    reason = exception.reason,
                                    message = exception.message ?: "Source resolution failed",
                                ),
                            )
                        }
                        else -> {
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ErrorResponse(
                                    code = 500,
                                    type = "InternalError",
                                    message = exception.message ?: "Failed to resolve source",
                                ),
                            )
                        }
                    }
                },
            )
        }
    }
}

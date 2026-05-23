package codelens.server.routes

import codelens.core.model.*
import codelens.server.services.KtlintService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Routes for ktlint operations.
 */
fun Route.ktlintRoutes(ktlintService: KtlintService) {
    route("/api/v1/ktlint") {
        // POST /api/v1/ktlint/lint/file
        // Lint a single file.
        // Request body: { "filePath": "/path/to/file.kt" }
        post("/lint/file") {
            val request = call.receive<LintFileRequest>()
            try {
                val result = ktlintService.lintFile(request.filePath)
                call.respond(result)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        code = 400,
                        type = "LintError",
                        message = e.message ?: "Failed to lint file",
                    ),
                )
            }
        }

        // POST /api/v1/ktlint/lint/project
        // Lint all Kotlin files in the project.
        // Request body: { "pattern": "...", "includeTests": true }
        post("/lint/project") {
            val request = call.receiveNullable<LintProjectRequest>() ?: LintProjectRequest()
            try {
                val result = ktlintService.lintProject(request.pattern, request.includeTests)
                call.respond(result)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(
                        code = 500,
                        type = "LintError",
                        message = e.message ?: "Failed to lint project",
                    ),
                )
            }
        }

        // POST /api/v1/ktlint/format/file
        // Format a single file.
        // Request body: { "filePath": "/path/to/file.kt", "writeToFile": false }
        post("/format/file") {
            val request = call.receive<FormatFileRequest>()
            try {
                val result = ktlintService.formatFile(request.filePath, request.writeToFile)
                call.respond(result)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        code = 400,
                        type = "FormatError",
                        message = e.message ?: "Failed to format file",
                    ),
                )
            }
        }

        // POST /api/v1/ktlint/format/project
        // Format all Kotlin files in the project.
        // Request body: { "pattern": "...", "includeTests": true, "dryRun": false }
        post("/format/project") {
            val request = call.receiveNullable<FormatProjectRequest>() ?: FormatProjectRequest()
            try {
                val result =
                    ktlintService.formatProject(
                        request.pattern,
                        request.includeTests,
                        request.dryRun,
                    )
                call.respond(result)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(
                        code = 500,
                        type = "FormatError",
                        message = e.message ?: "Failed to format project",
                    ),
                )
            }
        }
    }
}

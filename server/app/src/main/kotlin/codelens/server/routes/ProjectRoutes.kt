package codelens.server.routes

import codelens.server.services.AnalysisService
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.projectRoutes(analysisService: AnalysisService) {
    route("/api/v1") {
        get("/project") {
            call.respond(analysisService.getProjectInfo())
        }

        post("/project/refresh") {
            analysisService.refresh()
            call.respond(analysisService.getProjectInfo())
        }
    }
}

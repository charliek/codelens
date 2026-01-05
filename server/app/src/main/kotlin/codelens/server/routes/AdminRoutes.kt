package codelens.server.routes

import codelens.core.model.*
import codelens.server.ActivityTracker
import codelens.server.ServerConfig
import codelens.server.services.AnalysisService
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant

fun Route.adminRoutes(
    analysisService: AnalysisService,
    activityTracker: ActivityTracker,
    config: ServerConfig
) {
    route("/admin") {
        get("/health") {
            call.respond(HealthResponse(
                status = "UP",
                timestamp = Instant.now().toString()
            ))
        }

        get("/ready") {
            val projectInfo = analysisService.getProjectInfo()
            val isReady = projectInfo.status == ProjectStatus.READY

            if (isReady) {
                call.respond(ReadyResponse(
                    ready = true,
                    status = "READY",
                    project = projectInfo.name
                ))
            } else {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ReadyResponse(
                        ready = false,
                        status = projectInfo.status.name,
                        project = projectInfo.name
                    )
                )
            }
        }

        get("/info") {
            val projectInfo = analysisService.getProjectInfo()
            val uptime = activityTracker.getUptime()
            val idle = activityTracker.getIdleDuration()

            call.respond(ServerInfo(
                version = "0.1.0",
                apiVersion = "v1",
                projectPath = config.projectPath,
                projectName = projectInfo.name,
                port = call.request.local.serverPort,
                host = config.host,
                status = projectInfo.status.name,
                startedAt = activityTracker.getStartedAt().toString(),
                uptime = formatDuration(uptime),
                lastActivityAt = activityTracker.getLastActivity().toString(),
                idleDuration = formatDuration(idle),
                idleTimeout = "${config.idleTimeoutMinutes}m"
            ))
        }

        post("/activity") {
            activityTracker.touch()
            call.respond(mapOf("lastActivityAt" to Instant.now().toString()))
        }

        post("/shutdown") {
            // Verify request is from localhost
            val remoteHost = call.request.local.remoteHost
            if (remoteHost != "127.0.0.1" && remoteHost != "localhost" && remoteHost != "0:0:0:0:0:0:0:1") {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse(
                    code = 403,
                    type = "Forbidden",
                    message = "Shutdown only allowed from localhost"
                ))
                return@post
            }

            call.respond(mapOf("status" to "shutting_down"))

            // Shutdown in background thread
            Thread {
                Thread.sleep(100) // Let response complete
                System.exit(0)
            }.start()
        }
    }
}

private fun formatDuration(duration: java.time.Duration): String {
    val hours = duration.toHours()
    val minutes = duration.toMinutesPart()
    val seconds = duration.toSecondsPart()

    return when {
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

package codelens.server

import codelens.server.config.ServerConfig
import codelens.server.config.findAvailablePort
import codelens.server.config.parseArgs
import codelens.server.monitoring.ActivityTracker
import codelens.server.monitoring.startIdleMonitor
import codelens.server.routes.adminRoutes
import codelens.server.routes.analysisRoutes
import codelens.server.routes.ktlintRoutes
import codelens.server.routes.projectRoutes
import codelens.server.routes.ratpackRoutes
import codelens.server.routes.sourceRoutes
import codelens.server.services.AnalysisService
import codelens.server.services.KtlintService
import codelens.server.services.RatpackAnalysisService
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("codelens.server.Application")

fun main(args: Array<String>) {
    val config = parseArgs(args)

    // Validate project path
    val projectDir = File(config.projectPath)
    if (!projectDir.exists() || !projectDir.isDirectory) {
        logger.error("Project path does not exist: ${config.projectPath}")
        exitProcess(1)
    }

    val hasBuildFile =
        projectDir.resolve("build.gradle").exists() ||
            projectDir.resolve("build.gradle.kts").exists()
    if (!hasBuildFile) {
        logger.error("No build.gradle or build.gradle.kts found in ${config.projectPath}")
        exitProcess(1)
    }

    val analysisService = AnalysisService(projectDir, config.classpathFile, config.projectJavaHome)
    val ratpackAnalysisService = RatpackAnalysisService(analysisService.getClassGraphProvider())
    val ktlintService = KtlintService(projectDir)
    val activityTracker = ActivityTracker()

    // Find available port
    val port = config.port ?: findAvailablePort(config.portRangeStart, config.portRangeEnd)

    val server =
        embeddedServer(Netty, port = port, host = config.host) {
            configureServer(analysisService, ratpackAnalysisService, ktlintService, activityTracker, config)
        }

    // Start idle shutdown monitor
    if (config.idleTimeoutMinutes > 0) {
        startIdleMonitor(activityTracker, Duration.ofMinutes(config.idleTimeoutMinutes.toLong())) {
            logger.info("Idle timeout reached, shutting down...")
            server.stop(1000, 5000)
            exitProcess(0)
        }
    }

    // Add shutdown hook
    Runtime.getRuntime().addShutdownHook(
        Thread {
            logger.info("Shutting down...")
            server.stop(1000, 5000)
        },
    )

    // Start server
    server.start(wait = false)

    // Print ready signal (CLI watches for this on stdout)
    println("CODELENS_READY port=$port host=${config.host} version=0.1.0")
    System.out.flush()

    // Block main thread
    Thread.currentThread().join()
}

/**
 * Configures the Ktor application with plugins and routes.
 */
fun Application.configureServer(
    analysisService: AnalysisService,
    ratpackAnalysisService: RatpackAnalysisService,
    ktlintService: KtlintService,
    activityTracker: ActivityTracker,
    config: ServerConfig,
) {
    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = true
                encodeDefaults = true
            },
        )
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                codelens.core.model.ErrorResponse(
                    code = 500,
                    type = cause::class.simpleName ?: "Unknown",
                    message = cause.message ?: "Internal server error",
                ),
            )
        }
    }

    // Track activity on every request
    intercept(ApplicationCallPipeline.Monitoring) {
        activityTracker.touch()
    }

    routing {
        adminRoutes(analysisService, activityTracker, config)
        projectRoutes(analysisService)
        analysisRoutes(analysisService)
        sourceRoutes(analysisService)
        ratpackRoutes(ratpackAnalysisService)
        ktlintRoutes(ktlintService)
    }
}

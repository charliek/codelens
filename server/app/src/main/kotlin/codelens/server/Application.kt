package codelens.server

import codelens.server.routes.adminRoutes
import codelens.server.routes.projectRoutes
import codelens.server.services.AnalysisService
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import kotlinx.cli.*
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val config = parseArgs(args)

    // Validate project path
    val projectDir = File(config.projectPath)
    if (!projectDir.exists() || !projectDir.isDirectory) {
        System.err.println("Error: Project path does not exist: ${config.projectPath}")
        exitProcess(1)
    }

    val hasBuildFile = projectDir.resolve("build.gradle").exists() ||
                       projectDir.resolve("build.gradle.kts").exists()
    if (!hasBuildFile) {
        System.err.println("Error: No build.gradle or build.gradle.kts found in ${config.projectPath}")
        exitProcess(1)
    }

    val analysisService = AnalysisService(projectDir)
    val activityTracker = ActivityTracker()

    // Find available port
    val port = config.port ?: findAvailablePort(config.portRangeStart, config.portRangeEnd)

    val server = embeddedServer(Netty, port = port, host = config.host) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                encodeDefaults = true
            })
        }

        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respond(
                    HttpStatusCode.InternalServerError,
                    codelens.core.model.ErrorResponse(
                        code = 500,
                        type = cause::class.simpleName ?: "Unknown",
                        message = cause.message ?: "Internal server error"
                    )
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
        }
    }

    // Start idle shutdown monitor
    if (config.idleTimeoutMinutes > 0) {
        startIdleMonitor(activityTracker, Duration.ofMinutes(config.idleTimeoutMinutes.toLong())) {
            System.err.println("Idle timeout reached, shutting down...")
            server.stop(1000, 5000)
            exitProcess(0)
        }
    }

    // Add shutdown hook
    Runtime.getRuntime().addShutdownHook(Thread {
        System.err.println("Shutting down...")
        server.stop(1000, 5000)
    })

    // Start server
    server.start(wait = false)

    // Print ready signal (CLI watches for this on stdout)
    println("CODELENS_READY port=$port host=${config.host} version=0.1.0")
    System.out.flush()

    // Block main thread
    Thread.currentThread().join()
}

data class ServerConfig(
    val projectPath: String,
    val port: Int?,
    val host: String,
    val portRangeStart: Int,
    val portRangeEnd: Int,
    val idleTimeoutMinutes: Int
)

fun parseArgs(args: Array<String>): ServerConfig {
    val parser = ArgParser("codelens-server")

    val projectPath by parser.option(
        ArgType.String,
        shortName = "p",
        fullName = "project",
        description = "Path to target project directory"
    ).required()

    val port by parser.option(
        ArgType.Int,
        fullName = "port",
        description = "Port to listen on (auto-assigns if not specified)"
    )

    val host by parser.option(
        ArgType.String,
        fullName = "host",
        description = "Host to bind to"
    ).default("127.0.0.1")

    val idleTimeout by parser.option(
        ArgType.String,
        fullName = "idle-timeout",
        description = "Idle timeout (e.g., 30m, 1h, 0 to disable)"
    ).default("30m")

    parser.parse(args)

    return ServerConfig(
        projectPath = projectPath,
        port = port,
        host = host,
        portRangeStart = 8080,
        portRangeEnd = 8180,
        idleTimeoutMinutes = parseTimeoutMinutes(idleTimeout)
    )
}

fun parseTimeoutMinutes(timeout: String): Int {
    if (timeout == "0") return 0
    val value = timeout.dropLast(1).toIntOrNull() ?: return 30
    return when (timeout.last()) {
        'm' -> value
        'h' -> value * 60
        else -> 30
    }
}

fun findAvailablePort(start: Int, end: Int): Int {
    for (port in start..end) {
        try {
            java.net.ServerSocket(port).use { return port }
        } catch (e: Exception) {
            continue
        }
    }
    throw IllegalStateException("No available ports in range $start-$end")
}

class ActivityTracker {
    private val lastActivity = AtomicReference(Instant.now())
    private val startedAt = Instant.now()

    fun touch() {
        lastActivity.set(Instant.now())
    }

    fun getLastActivity(): Instant = lastActivity.get()
    fun getStartedAt(): Instant = startedAt
    fun getIdleDuration(): Duration = Duration.between(lastActivity.get(), Instant.now())
    fun getUptime(): Duration = Duration.between(startedAt, Instant.now())
}

fun startIdleMonitor(tracker: ActivityTracker, timeout: Duration, onIdle: () -> Unit) {
    thread(name = "idle-monitor", isDaemon = true) {
        while (true) {
            Thread.sleep(60_000) // Check every minute
            if (tracker.getIdleDuration() > timeout) {
                onIdle()
                break
            }
        }
    }
}

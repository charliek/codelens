package codelens.server

import codelens.core.BuildConfig
import codelens.core.model.ProjectStatus
import codelens.gradle.ClasspathResolutionException
import codelens.server.config.ServerConfig
import codelens.server.config.findAvailablePort
import codelens.server.config.parseArgs
import codelens.server.monitoring.ActivityTracker
import codelens.server.monitoring.startIdleMonitor
import codelens.server.routes.adminRoutes
import codelens.server.routes.analysisRoutes
import codelens.server.routes.ktlintRoutes
import codelens.server.routes.projectRoutes
import codelens.server.routes.sourceRoutes
import codelens.server.services.AnalysisService
import codelens.server.services.KtlintService
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
import java.io.PrintStream
import java.time.Duration
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("codelens.server.Application")

/**
 * Handle returned from [runServer] so callers (production `main` and tests)
 * can wait for shutdown or stop the server explicitly.
 */
internal class ServerHandle(
    private val server: EmbeddedServer<*, *>,
    private val analysisService: AnalysisService,
) {
    /** Block the current thread until the JVM is interrupted (production path). */
    fun awaitShutdown() {
        Thread.currentThread().join()
    }

    /** Stop the server and release scan executor resources (test path). */
    fun stop() {
        server.stop(1000, 5000)
        analysisService.shutdown()
    }
}

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
    runServer(config, projectDir, analysisService).awaitShutdown()
}

/**
 * Startup orchestration extracted from [main] so the readiness contract is
 * testable end-to-end.
 *
 * The readiness contract emitted to [readinessOut] is the public surface that
 * the CLI (and any future non-Python client) reads on stdout:
 *
 *   - `CODELENS_STARTING port=<p> host=<h>` - HTTP listener is bound;
 *     the initial scan is still running. Informational only; the CLI does
 *     not treat this as readiness.
 *   - `CODELENS_WARNING message="<msg>"` - an advisory emitted immediately
 *     before `CODELENS_READY` (currently only when the scan found zero project
 *     classes, the typical symptom of an uncompiled project). Additive and
 *     non-fatal; older CLIs that don't recognize it simply ignore the line.
 *   - `CODELENS_READY port=<p> host=<h> version=<v>` - the initial scan
 *     completed successfully and every analysis endpoint is ready to serve
 *     real data. The CLI matches on this line.
 *   - `CODELENS_ERROR reason=<reason> message="<msg>"` - the initial scan
 *     failed. The server is stopped and the process exits non-zero.
 *
 * @param exit Injection seam for [exitProcess]. Tests pass a lambda that
 *   throws so they can assert the exit code without terminating the JVM.
 */
internal fun runServer(
    config: ServerConfig,
    projectDir: File,
    analysisService: AnalysisService,
    ktlintService: KtlintService = KtlintService(projectDir),
    readinessOut: PrintStream = System.out,
    exit: (Int) -> Nothing = { exitProcess(it) },
): ServerHandle {
    val activityTracker = ActivityTracker()

    // Find available port
    val port = config.port ?: findAvailablePort(config.portRangeStart, config.portRangeEnd)

    val server =
        embeddedServer(Netty, port = port, host = config.host) {
            configureServer(analysisService, ktlintService, activityTracker, config)
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

    // Bind the HTTP listener so /admin/health and friends are reachable
    // even while the initial scan is still running.
    server.start(wait = false)

    // Informational early signal: HTTP listener is up, scan is not yet done.
    // The CLI ignores this line; it exists so users staring at a slow startup
    // can tell "JVM came up" from "JVM failed to start".
    readinessOut.println("CODELENS_STARTING port=$port host=${config.host}")
    readinessOut.flush()

    // Block here until the initial scan finishes. Netty is on its own worker
    // pool so it keeps serving health checks during this wait.
    val finalStatus = analysisService.awaitInitialScan()

    return when (finalStatus) {
        ProjectStatus.READY -> {
            // Advisory: a successful scan that found no project classes almost
            // always means the target wasn't compiled. Emit before READY so the
            // CLI (which returns the instant it sees READY) still consumes it.
            if ((analysisService.getProjectInfo().classCount ?: 0) == 0) {
                val message = AnalysisService.NO_PROJECT_CLASSES_WARNING.sanitizeForReadinessLine()
                readinessOut.println("""CODELENS_WARNING message="$message"""")
                readinessOut.flush()
            }
            readinessOut.println(
                "CODELENS_READY port=$port host=${config.host} version=${BuildConfig.VERSION}",
            )
            readinessOut.flush()
            ServerHandle(server, analysisService)
        }

        else -> {
            val error = analysisService.getInitialScanError()
            val reason =
                when (error) {
                    is ClasspathResolutionException -> "CLASSPATH_RESOLUTION"
                    null -> "UNKNOWN"
                    else -> "SCAN"
                }
            val message = (error?.message ?: "initial scan failed").sanitizeForReadinessLine()
            readinessOut.println("""CODELENS_ERROR reason=$reason message="$message"""")
            readinessOut.flush()
            server.stop(1000, 5000)
            analysisService.shutdown()
            exit(1)
        }
    }
}

/**
 * Strips characters that would break the single-line `CODELENS_ERROR` format
 * the CLI parses. Quotes and newlines get replaced; everything else passes
 * through.
 */
private fun String.sanitizeForReadinessLine(): String = replace('\n', ' ').replace('\r', ' ').replace("\"", "'").trim()

/**
 * Configures the Ktor application with plugins and routes.
 */
fun Application.configureServer(
    analysisService: AnalysisService,
    ktlintService: KtlintService,
    activityTracker: ActivityTracker,
    config: ServerConfig,
) {
    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = true
                encodeDefaults = true
                // Forward-compatible deserialization: ignore fields the server
                // doesn't know about yet, so a newer client can talk to an
                // older server (and during rolling upgrades, vice versa).
                ignoreUnknownKeys = true
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
        ktlintRoutes(ktlintService)
    }
}

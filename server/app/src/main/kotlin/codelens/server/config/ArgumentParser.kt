package codelens.server.config

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.required

/**
 * Parses command-line arguments into ServerConfig.
 */
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

    val classpathFile by parser.option(
        ArgType.String,
        fullName = "classpath-file",
        description = "Path to a pre-generated classpath file (fallback mode). If not specified, uses Gradle Tooling API."
    )

    parser.parse(args)

    return ServerConfig(
        projectPath = projectPath,
        port = port,
        host = host,
        portRangeStart = 8080,
        portRangeEnd = 8180,
        idleTimeoutMinutes = parseTimeoutMinutes(idleTimeout),
        classpathFile = classpathFile
    )
}

/**
 * Parses a timeout string like "30m" or "1h" into minutes.
 *
 * @param timeout Timeout string (e.g., "30m", "1h", "0")
 * @return Timeout in minutes, defaults to 30 if parsing fails
 */
fun parseTimeoutMinutes(timeout: String): Int {
    if (timeout == "0") return 0
    val value = timeout.dropLast(1).toIntOrNull() ?: return 30
    return when (timeout.last()) {
        'm' -> value
        'h' -> value * 60
        else -> 30
    }
}

/**
 * Finds an available port in the given range.
 *
 * @param start Start of port range (inclusive)
 * @param end End of port range (inclusive)
 * @return An available port
 * @throws IllegalStateException if no port is available
 */
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

package codelens.server.config

/**
 * Configuration for the CodeLens server.
 *
 * @property projectPath Path to the target project directory to analyze
 * @property port Specific port to listen on, or null for auto-assignment
 * @property host Host address to bind to
 * @property portRangeStart Start of port range for auto-assignment
 * @property portRangeEnd End of port range for auto-assignment
 * @property idleTimeoutMinutes Minutes of inactivity before auto-shutdown (0 to disable)
 */
data class ServerConfig(
    val projectPath: String,
    val port: Int?,
    val host: String,
    val portRangeStart: Int,
    val portRangeEnd: Int,
    val idleTimeoutMinutes: Int
)

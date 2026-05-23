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
 * @property classpathFile Path to a pre-generated classpath file (fallback mode), or null to use Gradle Tooling API
 * @property projectJavaHome Path to Java home for target project's Gradle (for older Gradle versions)
 */
data class ServerConfig(
    val projectPath: String,
    val port: Int?,
    val host: String,
    val portRangeStart: Int,
    val portRangeEnd: Int,
    val idleTimeoutMinutes: Int,
    val classpathFile: String? = null,
    val projectJavaHome: String? = null,
)

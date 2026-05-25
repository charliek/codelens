package codelens.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ProjectInfo(
    val name: String,
    val path: String,
    val status: ProjectStatus,
    val classCount: Int? = null,
    val scannedAt: String? = null,
)

@Serializable
enum class ProjectStatus {
    LOADING,
    READY,
    ERROR,
}

@Serializable
data class ServerInfo(
    val version: String,
    val apiVersion: String,
    val projectPath: String,
    val projectName: String,
    val port: Int,
    val host: String,
    val status: String,
    val startedAt: String,
    val uptime: String,
    val lastActivityAt: String,
    val idleDuration: String,
    val idleTimeout: String,
)

@Serializable
data class HealthResponse(
    val status: String,
    val timestamp: String,
)

@Serializable
data class ReadyResponse(
    val ready: Boolean,
    val status: String,
    val project: String,
)

@Serializable
data class ErrorResponse(
    val error: Boolean = true,
    val code: Int,
    val type: String,
    val message: String,
)

@Serializable
data class ActivityResponse(
    val lastActivityAt: String,
)

@Serializable
data class ShutdownResponse(
    val status: String,
)

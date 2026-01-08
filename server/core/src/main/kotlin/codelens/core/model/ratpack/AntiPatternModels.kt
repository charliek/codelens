package codelens.core.model.ratpack

import kotlinx.serialization.Serializable

// ============================================================================
// Anti-Pattern Detection Models
// ============================================================================

/**
 * Types of anti-patterns detected in Ratpack applications.
 */
@Serializable
enum class AntiPatternType {
    /** JDBC calls without Blocking.get() wrapper */
    BLOCKING_JDBC,
    /** Thread.sleep() calls in handlers */
    THREAD_SLEEP,
    /** Synchronous file I/O in handlers */
    SYNCHRONOUS_FILE_IO,
    /** Blocking HTTP client calls */
    BLOCKING_HTTP_CLIENT,
    /** Direct System.out/err usage instead of logging */
    CONSOLE_LOGGING,
    /** Catching and swallowing exceptions */
    SWALLOWED_EXCEPTION
}

/**
 * Severity level for detected anti-patterns.
 */
@Serializable
enum class AntiPatternSeverity {
    /** Informational - might be intentional */
    INFO,
    /** Warning - should review */
    WARNING,
    /** Error - likely a bug */
    ERROR,
    /** Critical - will cause problems in production */
    CRITICAL
}

/**
 * A detected anti-pattern instance in the code.
 */
@Serializable
data class AntiPatternInstance(
    /** Anti-pattern type */
    val type: AntiPatternType,
    /** Severity level */
    val severity: AntiPatternSeverity,
    /** Class where detected */
    val classFqn: String,
    /** Method where detected (if applicable) */
    val methodName: String?,
    /** Detection confidence (0.0-1.0) */
    val confidence: Double,
    /** Why this was flagged */
    val reason: String,
    /** How to fix it */
    val recommendation: String,
    /** Example fix code (if available) */
    val fixExample: String?
)

/**
 * Count of anti-patterns in a specific class.
 */
@Serializable
data class ClassAntiPatternCount(
    /** Class FQN */
    val classFqn: String,
    /** Total anti-pattern count */
    val count: Int,
    /** Count of CRITICAL severity */
    val criticalCount: Int,
    /** Count of ERROR severity */
    val errorCount: Int
)

/**
 * Summary of all anti-patterns found in the project.
 */
@Serializable
data class AntiPatternSummary(
    /** All detected instances */
    val instances: List<AntiPatternInstance>,
    /** Count by type */
    val countByType: Map<AntiPatternType, Int>,
    /** Count by severity */
    val countBySeverity: Map<AntiPatternSeverity, Int>,
    /** Classes with most issues (top 10) */
    val worstOffenders: List<ClassAntiPatternCount>,
    /** Total count */
    val totalCount: Int
)

package codelens.core.model

import kotlinx.serialization.Serializable

/**
 * A single lint error found in a file.
 */
@Serializable
data class LintError(
    /** Line number (1-based) */
    val line: Int,
    /** Column number (1-based) */
    val col: Int,
    /** Rule ID that was violated (e.g., "standard:no-wildcard-imports") */
    val ruleId: String,
    /** Human-readable description of the error */
    val detail: String,
    /** Whether this error can be auto-fixed */
    val canBeAutoCorrected: Boolean,
)

/**
 * Lint results for a single file.
 */
@Serializable
data class FileLintResult(
    /** Absolute path to the file */
    val filePath: String,
    /** List of lint errors found */
    val errors: List<LintError>,
    /** Number of errors */
    val errorCount: Int,
)

/**
 * Response for linting a single file.
 */
@Serializable
data class LintFileResponse(
    /** The file that was linted */
    val filePath: String,
    /** List of lint errors */
    val errors: List<LintError>,
    /** Total error count */
    val errorCount: Int,
    /** Lint duration in milliseconds */
    val durationMs: Long,
)

/**
 * Response for linting a project.
 */
@Serializable
data class LintProjectResponse(
    /** Project directory */
    val projectPath: String,
    /** Results per file (only files with errors included) */
    val fileResults: List<FileLintResult>,
    /** Total files scanned */
    val filesScanned: Int,
    /** Files with errors */
    val filesWithErrors: Int,
    /** Total error count */
    val totalErrorCount: Int,
    /** Lint duration in milliseconds */
    val durationMs: Long,
)

/**
 * Response for formatting a single file.
 */
@Serializable
data class FormatFileResponse(
    /** The file that was formatted */
    val filePath: String,
    /** Formatted content (if requested) */
    val formattedContent: String? = null,
    /** Whether changes were made */
    val hasChanges: Boolean,
    /** Errors that could not be auto-corrected */
    val remainingErrors: List<LintError>,
    /** Format duration in milliseconds */
    val durationMs: Long,
)

/**
 * Response for formatting a project.
 */
@Serializable
data class FormatProjectResponse(
    /** Project directory */
    val projectPath: String,
    /** Files that were formatted */
    val filesFormatted: List<String>,
    /** Total files scanned */
    val filesScanned: Int,
    /** Files that had changes */
    val filesWithChanges: Int,
    /** Format duration in milliseconds */
    val durationMs: Long,
)

/**
 * Request to lint a single file.
 */
@Serializable
data class LintFileRequest(
    /** Absolute path to the file to lint */
    val filePath: String,
)

/**
 * Request to lint a project.
 */
@Serializable
data class LintProjectRequest(
    /** Optional glob pattern to filter files (default: all .kt and .kts files) */
    val pattern: String? = null,
    /** Include test files */
    val includeTests: Boolean = true,
)

/**
 * Request to format a single file.
 */
@Serializable
data class FormatFileRequest(
    /** Absolute path to the file to format */
    val filePath: String,
    /** If true, write changes back to file; if false, return formatted content */
    val writeToFile: Boolean = false,
)

/**
 * Request to format a project.
 */
@Serializable
data class FormatProjectRequest(
    /** Optional glob pattern to filter files */
    val pattern: String? = null,
    /** Include test files */
    val includeTests: Boolean = true,
    /** Perform a dry run (report changes but don't write) */
    val dryRun: Boolean = false,
)

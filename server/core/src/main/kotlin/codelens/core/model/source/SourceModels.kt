package codelens.core.model.source

import kotlinx.serialization.Serializable
import java.io.File

/**
 * Internal representation of a source root directory with metadata.
 * Used during analysis (not for API serialization - use SourceRoot for that).
 */
data class SourceRootInfo(
    /** Absolute path to the source root directory */
    val path: File,
    /** Source language: "java" or "kotlin" */
    val language: String,
    /** Source set name: "main", "test", etc. */
    val sourceSet: String,
    /** Module path in multi-module project (e.g., ":server:app") */
    val module: String
)

/**
 * Represents a source root directory in the project (API serializable).
 */
@Serializable
data class SourceRoot(
    /** Absolute path to the source root directory */
    val path: String,
    /** Source language: "java" or "kotlin" */
    val language: String,
    /** Source set name: "main", "test", etc. */
    val sourceSet: String,
    /** Module path in multi-module project (e.g., ":server:app") */
    val module: String
)

/**
 * Language type for source files.
 */
@Serializable
enum class SourceLanguage {
    JAVA,
    KOTLIN,
    UNKNOWN
}

/**
 * Complete source code information for a class.
 */
@Serializable
data class SourceInfo(
    /** Fully qualified class name */
    val fqn: String,
    /** Absolute path to the source file */
    val filePath: String,
    /** Source language */
    val language: SourceLanguage,
    /** Full source code content */
    val content: String,
    /** Total number of lines */
    val lineCount: Int,
    /** Module this source belongs to (for multi-module projects) */
    val module: String? = null
)

/**
 * Source code for a specific method.
 */
@Serializable
data class MethodSourceInfo(
    /** Fully qualified class name */
    val classFqn: String,
    /** Method name */
    val methodName: String,
    /** Method signature for disambiguation */
    val signature: String,
    /** Source code of the method */
    val content: String,
    /** Starting line number in the file (1-based) */
    val startLine: Int,
    /** Ending line number in the file (1-based) */
    val endLine: Int,
    /** Context lines before the method (if requested) */
    val contextBefore: String? = null,
    /** Context lines after the method (if requested) */
    val contextAfter: String? = null
)

/**
 * Error when source cannot be resolved.
 */
@Serializable
data class SourceResolutionError(
    /** The FQN that could not be resolved */
    val fqn: String,
    /** Reason for failure */
    val reason: SourceResolutionErrorReason,
    /** Human-readable message */
    val message: String
)

@Serializable
enum class SourceResolutionErrorReason {
    /** Class is from a library, source not available */
    LIBRARY_CLASS,
    /** Class is from JDK, source not available */
    JDK_CLASS,
    /** Source file not found in any source root */
    FILE_NOT_FOUND,
    /** Class not found in scan results */
    CLASS_NOT_FOUND,
    /** Method not found in class */
    METHOD_NOT_FOUND
}

/**
 * Exception thrown when source resolution fails.
 */
class SourceResolutionException(
    val fqn: String,
    val reason: SourceResolutionErrorReason,
    message: String
) : Exception(message)

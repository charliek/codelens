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
 * Origin of resolved source code.
 */
@Serializable
enum class SourceOrigin {
    /** Source from project source roots */
    PROJECT_SOURCE,
    /** Source from library -sources.jar */
    SOURCE_JAR,
    /** Source from bytecode decompilation */
    DECOMPILED,
    /** Source from JDK src.zip */
    JDK_SOURCE
}

/**
 * Format of source output for LLM-friendly responses.
 */
@Serializable
enum class SourceFormat {
    /** Complete source code */
    FULL,
    /** Stub with placeholder method bodies */
    STUB,
    /** Just method/field signatures */
    SIGNATURES,
    /** Signatures with doc comments only */
    JAVADOC
}

/**
 * Complete source code information for a class.
 */
@Serializable
data class SourceInfo(
    /** Fully qualified class name */
    val fqn: String,
    /** Absolute path to the source file (null for library/JDK sources) */
    val filePath: String? = null,
    /** Source language */
    val language: SourceLanguage,
    /** Full source code content */
    val content: String,
    /** Total number of lines */
    val lineCount: Int,
    /** Module this source belongs to (for multi-module projects) */
    val module: String? = null,
    /** Origin of the source code */
    val sourceOrigin: SourceOrigin = SourceOrigin.PROJECT_SOURCE,
    /** Maven coordinates for library sources (e.g., "com.google.guava:guava:32.1.3-jre") */
    val mavenCoordinates: String? = null,
    /** Whether the source was decompiled from bytecode */
    val isDecompiled: Boolean = false,
    /** Format of the returned content */
    val format: SourceFormat = SourceFormat.FULL
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

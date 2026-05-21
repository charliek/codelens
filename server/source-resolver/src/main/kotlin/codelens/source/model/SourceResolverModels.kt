package codelens.source.model

import codelens.core.model.MavenCoordinates
import codelens.core.model.source.SourceFormat
import codelens.core.model.source.SourceOrigin

/**
 * Represents visibility filter for source output.
 */
enum class VisibilityFilter {
    /** Include all members */
    ALL,

    /** Include only public members */
    PUBLIC,

    /** Include public and protected members */
    PUBLIC_PROTECTED,
}

/**
 * Represents the target language for stub generation.
 */
enum class StubLanguage {
    JAVA,
    KOTLIN,
}

/**
 * Extended source information with library resolution metadata.
 */
data class LibrarySourceInfo(
    val fqn: String,
    val source: String,
    val sourceOrigin: SourceOrigin,
    val mavenCoordinates: MavenCoordinates? = null,
    val isDecompiled: Boolean = false,
    val format: SourceFormat = SourceFormat.FULL,
    val language: String = "JAVA",
)

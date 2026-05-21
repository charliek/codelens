package codelens.ktlint

import codelens.core.model.FormatFileResponse
import codelens.core.model.FormatProjectResponse
import codelens.core.model.LintFileResponse
import codelens.core.model.LintProjectResponse
import java.io.File
import java.nio.file.Path

/**
 * Interface for ktlint-based linting and formatting.
 */
interface KtlintProvider {
    /**
     * Initialize the provider for a specific project.
     * Loads .editorconfig if present.
     *
     * @param projectDir The project root directory
     */
    fun initialize(projectDir: File)

    /**
     * Lint a single file.
     *
     * @param filePath Path to the Kotlin file
     * @return Lint results
     */
    fun lintFile(filePath: Path): LintFileResponse

    /**
     * Lint all Kotlin files in the project.
     *
     * @param pattern Optional glob pattern to filter files
     * @param includeTests Whether to include test files
     * @return Lint results for all files
     */
    fun lintProject(
        pattern: String? = null,
        includeTests: Boolean = true,
    ): LintProjectResponse

    /**
     * Format a single file.
     *
     * @param filePath Path to the Kotlin file
     * @param writeToFile If true, writes changes back to file
     * @return Format results with formatted content
     */
    fun formatFile(
        filePath: Path,
        writeToFile: Boolean = false,
    ): FormatFileResponse

    /**
     * Format all Kotlin files in the project.
     *
     * @param pattern Optional glob pattern to filter files
     * @param includeTests Whether to include test files
     * @param dryRun If true, don't write changes
     * @return Format results
     */
    fun formatProject(
        pattern: String? = null,
        includeTests: Boolean = true,
        dryRun: Boolean = false,
    ): FormatProjectResponse

    /**
     * Check if the provider has been initialized.
     */
    fun isInitialized(): Boolean
}

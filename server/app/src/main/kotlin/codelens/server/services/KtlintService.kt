package codelens.server.services

import codelens.core.model.*
import codelens.ktlint.KtlintProvider
import codelens.ktlint.KtlintProviderImpl
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Service for ktlint operations.
 */
class KtlintService(
    private val projectDir: File,
) {
    private val logger = LoggerFactory.getLogger(KtlintService::class.java)
    private val ktlintProvider: KtlintProvider = KtlintProviderImpl()

    init {
        // Initialize ktlint with the project directory
        ktlintProvider.initialize(projectDir)
    }

    /**
     * Lint a single file.
     */
    fun lintFile(filePath: String): LintFileResponse {
        val path = resolvePath(filePath)
        return ktlintProvider.lintFile(path)
    }

    /**
     * Lint the entire project.
     */
    fun lintProject(
        pattern: String?,
        includeTests: Boolean,
    ): LintProjectResponse = ktlintProvider.lintProject(pattern, includeTests)

    /**
     * Format a single file.
     */
    fun formatFile(
        filePath: String,
        writeToFile: Boolean,
    ): FormatFileResponse {
        val path = resolvePath(filePath)
        return ktlintProvider.formatFile(path, writeToFile)
    }

    /**
     * Format the entire project.
     */
    fun formatProject(
        pattern: String?,
        includeTests: Boolean,
        dryRun: Boolean,
    ): FormatProjectResponse = ktlintProvider.formatProject(pattern, includeTests, dryRun)

    /**
     * Resolve a file path relative to the project directory if needed.
     */
    private fun resolvePath(filePath: String): Path {
        val path = Paths.get(filePath)
        return if (path.isAbsolute) {
            path
        } else {
            projectDir.toPath().resolve(path)
        }
    }
}

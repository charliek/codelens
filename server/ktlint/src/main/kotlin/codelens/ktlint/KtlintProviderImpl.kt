package codelens.ktlint

import codelens.core.model.*
import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.EditorConfigDefaults
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.api.LintError as KtLintError
import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.propertyTypes
import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString
import kotlin.streams.toList

/**
 * Implementation of KtlintProvider using ktlint rule engine.
 */
class KtlintProviderImpl : KtlintProvider {
    private val logger = LoggerFactory.getLogger(KtlintProviderImpl::class.java)

    private var projectDir: File? = null
    private var ruleEngine: KtLintRuleEngine? = null

    override fun initialize(projectDir: File) {
        logger.info("Initializing ktlint for project: ${projectDir.absolutePath}")
        this.projectDir = projectDir

        // Get standard rule providers
        val ruleProviders = StandardRuleSetProvider().getRuleProviders()

        // Load .editorconfig if present
        val editorConfigPath = projectDir.resolve(".editorconfig").toPath()
        val editorConfigDefaults = if (Files.exists(editorConfigPath)) {
            logger.info("Loading .editorconfig from: $editorConfigPath")
            EditorConfigDefaults.load(
                path = editorConfigPath,
                propertyTypes = ruleProviders.propertyTypes()
            )
        } else {
            EditorConfigDefaults.EMPTY_EDITOR_CONFIG_DEFAULTS
        }

        // Create rule engine with standard ruleset (reused for all operations)
        ruleEngine = KtLintRuleEngine(
            ruleProviders = ruleProviders,
            editorConfigDefaults = editorConfigDefaults
        )

        logger.info("ktlint initialized successfully")
    }

    override fun isInitialized(): Boolean = ruleEngine != null

    override fun lintFile(filePath: Path): LintFileResponse {
        requireInitialized()
        val startTime = System.currentTimeMillis()

        val errors = mutableListOf<LintError>()
        val code = Code.fromFile(filePath.toFile())

        ruleEngine!!.lint(code) { error ->
            errors.add(error.toModel())
        }

        return LintFileResponse(
            filePath = filePath.pathString,
            errors = errors,
            errorCount = errors.size,
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    override fun lintProject(pattern: String?, includeTests: Boolean): LintProjectResponse {
        requireInitialized()
        val startTime = System.currentTimeMillis()

        val files = findKotlinFiles(pattern, includeTests)
        val fileResults = mutableListOf<FileLintResult>()
        var totalErrors = 0

        for (file in files) {
            val result = lintFile(file)
            if (result.errorCount > 0) {
                fileResults.add(
                    FileLintResult(
                        filePath = result.filePath,
                        errors = result.errors,
                        errorCount = result.errorCount
                    )
                )
                totalErrors += result.errorCount
            }
        }

        return LintProjectResponse(
            projectPath = projectDir!!.absolutePath,
            fileResults = fileResults,
            filesScanned = files.size,
            filesWithErrors = fileResults.size,
            totalErrorCount = totalErrors,
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    override fun formatFile(filePath: Path, writeToFile: Boolean): FormatFileResponse {
        requireInitialized()
        val startTime = System.currentTimeMillis()

        val originalContent = Files.readString(filePath)
        val remainingErrors = mutableListOf<LintError>()

        val code = Code.fromFile(filePath.toFile())
        val formattedContent = ruleEngine!!.format(code) { error ->
            if (error.canBeAutoCorrected) {
                AutocorrectDecision.ALLOW_AUTOCORRECT
            } else {
                remainingErrors.add(error.toModel())
                AutocorrectDecision.NO_AUTOCORRECT
            }
        }

        val hasChanges = formattedContent != originalContent

        if (writeToFile && hasChanges) {
            Files.writeString(filePath, formattedContent)
            logger.info("Formatted file: $filePath")
        }

        return FormatFileResponse(
            filePath = filePath.pathString,
            formattedContent = if (!writeToFile) formattedContent else null,
            hasChanges = hasChanges,
            remainingErrors = remainingErrors,
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    override fun formatProject(
        pattern: String?,
        includeTests: Boolean,
        dryRun: Boolean
    ): FormatProjectResponse {
        requireInitialized()
        val startTime = System.currentTimeMillis()

        val files = findKotlinFiles(pattern, includeTests)
        val filesFormatted = mutableListOf<String>()

        for (file in files) {
            val result = formatFile(file, writeToFile = !dryRun)
            if (result.hasChanges) {
                filesFormatted.add(file.pathString)
            }
        }

        return FormatProjectResponse(
            projectPath = projectDir!!.absolutePath,
            filesFormatted = filesFormatted,
            filesScanned = files.size,
            filesWithChanges = filesFormatted.size,
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    private fun findKotlinFiles(pattern: String?, includeTests: Boolean): List<Path> {
        val dir = projectDir!!.toPath()

        // Use use {} to ensure the stream is properly closed
        return Files.walk(dir).use { stream ->
            stream
                .filter { it.isRegularFile() }
                .filter { it.extension == "kt" || it.extension == "kts" }
                .filter { path ->
                    // Exclude build directories
                    !path.pathString.contains("/build/") &&
                        !path.pathString.contains("\\build\\")
                }
                .filter { path ->
                    if (!includeTests) {
                        !path.pathString.contains("/test/") &&
                            !path.pathString.contains("\\test\\") &&
                            !path.pathString.contains("/testFixtures/") &&
                            !path.pathString.contains("\\testFixtures\\")
                    } else {
                        true
                    }
                }
                .filter { path ->
                    if (pattern != null) {
                        matchesGlob(path, pattern)
                    } else {
                        true
                    }
                }
                .toList()
        }
    }

    private fun matchesGlob(path: Path, pattern: String): Boolean {
        val matcher = path.fileSystem.getPathMatcher("glob:$pattern")
        return matcher.matches(path) || matcher.matches(path.fileName)
    }

    private fun requireInitialized() {
        check(ruleEngine != null) { "KtlintProvider not initialized. Call initialize() first." }
    }

    private fun KtLintError.toModel(): LintError {
        return LintError(
            line = this.line,
            col = this.col,
            ruleId = this.ruleId.value,
            detail = this.detail,
            canBeAutoCorrected = this.canBeAutoCorrected
        )
    }
}

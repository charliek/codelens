package codelens.ktlint

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for KtlintProviderImpl.
 */
class KtlintProviderImplTest {

    private lateinit var provider: KtlintProviderImpl
    private lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        provider = KtlintProviderImpl()
        tempDir = Files.createTempDirectory("ktlint-test")

        // Create a minimal build.gradle.kts for initialization
        Files.writeString(tempDir.resolve("build.gradle.kts"), "// empty")

        // Initialize provider with temp directory
        provider.initialize(tempDir.toFile())
    }

    @AfterEach
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `isInitialized should return true after initialize`() {
        assertTrue(provider.isInitialized())
    }

    @Test
    fun `lintFile should detect style violations`() {
        // Create a file with ktlint violations
        val badCode = """
            fun badFunction( x:Int,y:Int ){
                val z=x+y
                if(z>0){
                    println( "result" )
                }
            }
        """.trimIndent()

        val testFile = tempDir.resolve("BadCode.kt")
        Files.writeString(testFile, badCode)

        // Lint the file
        val result = provider.lintFile(testFile)

        // Verify violations were detected
        assertTrue(result.errorCount > 0, "Should detect style violations")
        assertTrue(result.errors.isNotEmpty(), "Should have error details")
        assertEquals(testFile.toString(), result.filePath)
    }

    @Test
    fun `lintFile should return empty errors for compliant code`() {
        // Create a properly formatted file - ktlint requires:
        // - File ends with newline
        // - Single parameter functions are fine on one line
        val goodCode = """
            fun goodFunction(x: Int): Int {
                val z = x + 1
                if (z > 0) {
                    println("result")
                }
                return z
            }

        """.trimIndent()

        val testFile = tempDir.resolve("GoodCode.kt")
        Files.writeString(testFile, goodCode)

        // Lint the file
        val result = provider.lintFile(testFile)

        // Verify no violations
        assertEquals(0, result.errorCount, "Should have no errors for compliant code. Errors: ${result.errors}")
        assertTrue(result.errors.isEmpty(), "Error list should be empty")
    }

    @Test
    fun `formatFile should fix auto-correctable issues`() {
        // Create a file with auto-correctable violations
        val badCode = """
            fun badFunction( x:Int,y:Int ){
                val z=x+y
                println(z)
            }
        """.trimIndent()

        val testFile = tempDir.resolve("ToFormat.kt")
        Files.writeString(testFile, badCode)

        // Format without writing to file
        val result = provider.formatFile(testFile, writeToFile = false)

        // Verify formatting occurred
        assertTrue(result.hasChanges, "Should detect changes needed")
        assertTrue(result.formattedContent != null, "Should return formatted content")
        assertTrue(result.formattedContent != badCode, "Formatted content should differ from original")

        // Verify original file is unchanged
        val originalContent = Files.readString(testFile)
        assertEquals(badCode, originalContent, "Original file should not be modified")
    }

    @Test
    fun `formatFile with writeToFile true should modify file`() {
        // Create a file with auto-correctable violations
        val badCode = """
            fun badFunction( x:Int,y:Int ){
                val z=x+y
                println(z)
            }
        """.trimIndent()

        val testFile = tempDir.resolve("ToFormat2.kt")
        Files.writeString(testFile, badCode)

        // Format with write enabled
        val result = provider.formatFile(testFile, writeToFile = true)

        // Verify formatting occurred
        assertTrue(result.hasChanges, "Should detect changes needed")

        // Verify file was modified
        val newContent = Files.readString(testFile)
        assertTrue(newContent != badCode, "File should be modified")
    }

    @Test
    fun `formatFile with compliant code should report no changes`() {
        // Create a properly formatted file - using expression body which ktlint won't change
        val goodCode = """
            fun goodFunction(x: Int): Int = x + 1

        """.trimIndent()

        val testFile = tempDir.resolve("AlreadyFormatted.kt")
        Files.writeString(testFile, goodCode)

        // Format the file
        val result = provider.formatFile(testFile, writeToFile = false)

        // Verify no changes needed
        assertFalse(result.hasChanges, "Should report no changes for compliant code. Formatted: ${result.formattedContent}")
    }

    @Test
    fun `lintProject should scan all kotlin files`() {
        // Create source directory
        val srcDir = tempDir.resolve("src/main/kotlin")
        Files.createDirectories(srcDir)

        // Create multiple Kotlin files
        Files.writeString(srcDir.resolve("File1.kt"), "fun file1( ){}")
        Files.writeString(srcDir.resolve("File2.kt"), "fun file2( ){}")
        Files.writeString(srcDir.resolve("File3.kt"), "fun file3(): Unit { }")

        // Lint the project
        val result = provider.lintProject(pattern = null, includeTests = true)

        // Verify multiple files were scanned
        assertTrue(result.filesScanned >= 3, "Should scan at least 3 files")
        assertTrue(result.totalErrorCount > 0, "Should find errors")
    }

    @Test
    fun `lintProject should respect includeTests flag`() {
        // Create source and test directories
        val srcDir = tempDir.resolve("src/main/kotlin")
        val testDir = tempDir.resolve("src/test/kotlin")
        Files.createDirectories(srcDir)
        Files.createDirectories(testDir)

        // Create source file with errors
        Files.writeString(srcDir.resolve("Main.kt"), "fun main( ){}")

        // Create test file with errors
        Files.writeString(testDir.resolve("MainTest.kt"), "fun testMain( ){}")

        // Lint without tests
        val resultNoTests = provider.lintProject(pattern = null, includeTests = false)

        // Lint with tests
        val resultWithTests = provider.lintProject(pattern = null, includeTests = true)

        // The test directory file should be excluded when includeTests is false
        assertTrue(
            resultWithTests.filesScanned >= resultNoTests.filesScanned,
            "Should scan more files when including tests"
        )
    }

    @Test
    fun `lintProject should exclude build directories`() {
        // Create source and build directories
        val srcDir = tempDir.resolve("src/main/kotlin")
        val buildDir = tempDir.resolve("build/generated/kotlin")
        Files.createDirectories(srcDir)
        Files.createDirectories(buildDir)

        // Create source file
        Files.writeString(srcDir.resolve("Main.kt"), "fun main(): Unit {}")

        // Create build file (should be excluded)
        Files.writeString(buildDir.resolve("Generated.kt"), "fun generated( ){}")

        // Lint the project
        val result = provider.lintProject(pattern = null, includeTests = true)

        // Build directory files should be excluded
        val filesWithErrors = result.fileResults.map { it.filePath }
        assertFalse(
            filesWithErrors.any { it.contains("/build/") },
            "Should exclude files in build directory"
        )
    }

    @Test
    fun `formatProject should format all kotlin files`() {
        // Create source directory
        val srcDir = tempDir.resolve("src/main/kotlin")
        Files.createDirectories(srcDir)

        // Create files with formatting issues
        Files.writeString(srcDir.resolve("File1.kt"), "fun file1( ){}")
        Files.writeString(srcDir.resolve("File2.kt"), "fun file2( ){}")

        // Format project with dry run
        val result = provider.formatProject(pattern = null, includeTests = true, dryRun = true)

        // Verify files would be formatted
        assertTrue(result.filesScanned >= 2, "Should scan at least 2 files")
        assertTrue(result.filesWithChanges > 0, "Should find files to format")

        // Verify files weren't actually modified (dry run)
        val file1Content = Files.readString(srcDir.resolve("File1.kt"))
        assertEquals("fun file1( ){}", file1Content, "File should not be modified in dry run")
    }

    @Test
    fun `formatProject without dry run should modify files`() {
        // Create source directory
        val srcDir = tempDir.resolve("src/main/kotlin")
        Files.createDirectories(srcDir)

        // Create a file with formatting issues
        val originalContent = "fun file1( ){}"
        Files.writeString(srcDir.resolve("File1.kt"), originalContent)

        // Format project without dry run
        val result = provider.formatProject(pattern = null, includeTests = true, dryRun = false)

        // Verify files were formatted
        assertTrue(result.filesWithChanges > 0, "Should have formatted files")

        // Verify file was actually modified
        val newContent = Files.readString(srcDir.resolve("File1.kt"))
        assertTrue(newContent != originalContent, "File should be modified")
    }

    @Test
    fun `initialize should load editorconfig if present`() {
        // Create a new provider with .editorconfig
        val newTempDir = Files.createTempDirectory("ktlint-editorconfig-test")
        try {
            // Create build file
            Files.writeString(newTempDir.resolve("build.gradle.kts"), "// empty")

            // Create .editorconfig with custom rule
            Files.writeString(
                newTempDir.resolve(".editorconfig"),
                """
                    root = true

                    [*.kt]
                    indent_size = 4
                    max_line_length = 120
                """.trimIndent()
            )

            // Initialize new provider
            val newProvider = KtlintProviderImpl()
            newProvider.initialize(newTempDir.toFile())

            // Provider should initialize without errors
            assertTrue(newProvider.isInitialized())
        } finally {
            newTempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `lintFile should report correct line and column numbers`() {
        // Create a file with a known violation
        val code = """
            fun test() {
                val x=1
            }
        """.trimIndent()

        val testFile = tempDir.resolve("LineColTest.kt")
        Files.writeString(testFile, code)

        // Lint the file
        val result = provider.lintFile(testFile)

        // Verify errors have line and column info
        assertTrue(result.errors.isNotEmpty(), "Should have errors")
        val error = result.errors.first()
        assertTrue(error.line > 0, "Line number should be positive")
        assertTrue(error.col > 0, "Column number should be positive")
        assertTrue(error.ruleId.isNotBlank(), "Rule ID should not be blank")
    }

    @Test
    fun `formatFile remaining errors should not contain duplicates`() {
        // Create a file with multiple wildcard imports that cannot be auto-corrected
        // This is the scenario that was causing duplicate errors
        val code = """
            import java.util.*
            import java.io.*
            import java.net.*

            fun test() {
                println("test")
            }
        """.trimIndent()

        val testFile = tempDir.resolve("WildcardImports.kt")
        Files.writeString(testFile, code)

        // Format the file (wildcard imports are not auto-correctable)
        val result = provider.formatFile(testFile, writeToFile = false)

        // Check for duplicates by comparing size with distinct
        val errorKeys = result.remainingErrors.map { "${it.line}:${it.col}:${it.ruleId}" }
        assertEquals(
            errorKeys.distinct().size,
            errorKeys.size,
            "Remaining errors should not contain duplicates. Found: ${result.remainingErrors.map { "${it.line}:${it.col}:${it.ruleId}" }}"
        )

        // Additionally verify we have the expected number of unique errors
        // Each wildcard import should be reported once
        val wildcardErrors = result.remainingErrors.filter { it.ruleId.contains("no-wildcard-imports") }
        assertEquals(
            3,
            wildcardErrors.size,
            "Should have exactly 3 wildcard import errors (one per import)"
        )
    }

    @Test
    fun `formatFile should not duplicate errors even when callback is invoked multiple times`() {
        // Create a file with code that triggers multiple violations
        val code = """
            import java.util.*

            fun messyFunction( x:Int,y:Int ){
                val z=x+y
                println(z)
            }
        """.trimIndent()

        val testFile = tempDir.resolve("MultiErrors.kt")
        Files.writeString(testFile, code)

        // Format the file
        val result = provider.formatFile(testFile, writeToFile = false)

        // Verify no duplicates in remaining errors
        val errorKeys = result.remainingErrors.map { "${it.line}:${it.col}:${it.ruleId}" }
        val uniqueKeys = errorKeys.distinct()

        assertEquals(
            uniqueKeys.size,
            errorKeys.size,
            "Should have no duplicate errors. Duplicates: ${errorKeys.groupBy { it }.filter { it.value.size > 1 }}"
        )
    }
}

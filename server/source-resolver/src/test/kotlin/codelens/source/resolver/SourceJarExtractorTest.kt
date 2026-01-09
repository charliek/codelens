package codelens.source.resolver

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceJarExtractorTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var extractor: SourceJarExtractor

    @BeforeEach
    fun setUp() {
        extractor = SourceJarExtractor()
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ========== Helper Methods ==========

    private fun createTestJar(vararg entries: Pair<String, String>): File {
        val jarFile = File(tempDir, "test-sources.jar")
        ZipOutputStream(jarFile.outputStream()).use { zos ->
            for ((path, content) in entries) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return jarFile
    }

    // ========== extractSource Tests ==========

    @Test
    fun `extractSource finds Java source file`() {
        val sourceContent = "package com.example;\n\npublic class MyClass { }"
        val jar = createTestJar(
            "com/example/MyClass.java" to sourceContent
        )

        val result = extractor.extractSource(jar, "com.example.MyClass")

        assertNotNull(result)
        assertEquals(sourceContent, result)
    }

    @Test
    fun `extractSource finds Kotlin source file`() {
        val sourceContent = "package com.example\n\nclass MyClass"
        val jar = createTestJar(
            "com/example/MyClass.kt" to sourceContent
        )

        val result = extractor.extractSource(jar, "com.example.MyClass")

        assertNotNull(result)
        assertEquals(sourceContent, result)
    }

    @Test
    fun `extractSource prefers Java over Kotlin when both exist`() {
        val javaContent = "// Java version"
        val kotlinContent = "// Kotlin version"
        val jar = createTestJar(
            "com/example/MyClass.java" to javaContent,
            "com/example/MyClass.kt" to kotlinContent
        )

        val result = extractor.extractSource(jar, "com.example.MyClass")

        assertNotNull(result)
        assertEquals(javaContent, result)
    }

    @Test
    fun `extractSource returns null for missing class`() {
        val jar = createTestJar(
            "com/example/OtherClass.java" to "public class OtherClass { }"
        )

        val result = extractor.extractSource(jar, "com.example.Missing")

        assertNull(result)
    }

    @Test
    fun `extractSource returns null for non-existent JAR`() {
        val nonExistent = File(tempDir, "nonexistent.jar")

        val result = extractor.extractSource(nonExistent, "com.example.MyClass")

        assertNull(result)
    }

    @Test
    fun `extractSource handles inner class by finding outer class file`() {
        val sourceContent = "public class Outer { class Inner { } }"
        val jar = createTestJar(
            "com/example/Outer.java" to sourceContent
        )

        val result = extractor.extractSource(jar, "com.example.Outer\$Inner")

        assertNotNull(result)
        assertEquals(sourceContent, result)
    }

    @Test
    fun `extractSource handles nested inner classes`() {
        val sourceContent = "public class Outer { class Middle { class Inner { } } }"
        val jar = createTestJar(
            "com/example/Outer.java" to sourceContent
        )

        val result = extractor.extractSource(jar, "com.example.Outer\$Middle\$Inner")

        assertNotNull(result)
        assertEquals(sourceContent, result)
    }

    @Test
    fun `extractSource handles Kotlin inner class`() {
        val sourceContent = "class Outer { inner class Inner }"
        val jar = createTestJar(
            "com/example/Outer.kt" to sourceContent
        )

        val result = extractor.extractSource(jar, "com.example.Outer\$Inner")

        assertNotNull(result)
        assertEquals(sourceContent, result)
    }

    // ========== listSourceFiles Tests ==========

    @Test
    fun `listSourceFiles returns all Java and Kotlin files`() {
        val jar = createTestJar(
            "com/example/ClassA.java" to "class A",
            "com/example/ClassB.kt" to "class B",
            "com/example/ClassC.java" to "class C",
            "META-INF/MANIFEST.MF" to "Manifest"
        )

        val files = extractor.listSourceFiles(jar)

        assertEquals(3, files.size)
        assertTrue(files.contains("com/example/ClassA.java"))
        assertTrue(files.contains("com/example/ClassB.kt"))
        assertTrue(files.contains("com/example/ClassC.java"))
        assertFalse(files.contains("META-INF/MANIFEST.MF"))
    }

    @Test
    fun `listSourceFiles returns empty list for non-existent JAR`() {
        val nonExistent = File(tempDir, "nonexistent.jar")

        val files = extractor.listSourceFiles(nonExistent)

        assertTrue(files.isEmpty())
    }

    @Test
    fun `listSourceFiles excludes directories`() {
        val jar = createTestJar(
            "com/example/MyClass.java" to "class MyClass"
        )

        val files = extractor.listSourceFiles(jar)

        assertEquals(1, files.size)
        assertEquals("com/example/MyClass.java", files[0])
    }

    // ========== extractAll Tests ==========

    @Test
    fun `extractAll extracts all source files`() {
        val jar = createTestJar(
            "com/example/ClassA.java" to "class A",
            "com/example/sub/ClassB.kt" to "class B"
        )
        val targetDir = File(tempDir, "extracted")

        val count = extractor.extractAll(jar, targetDir)

        assertEquals(2, count)
        assertTrue(File(targetDir, "com/example/ClassA.java").exists())
        assertTrue(File(targetDir, "com/example/sub/ClassB.kt").exists())
    }

    @Test
    fun `extractAll returns 0 for non-existent JAR`() {
        val nonExistent = File(tempDir, "nonexistent.jar")
        val targetDir = File(tempDir, "extracted")

        val count = extractor.extractAll(nonExistent, targetDir)

        assertEquals(0, count)
    }

    @Test
    fun `extractAll skips non-source files`() {
        val jar = createTestJar(
            "com/example/MyClass.java" to "class MyClass",
            "META-INF/MANIFEST.MF" to "Manifest",
            "com/example/data.txt" to "data"
        )
        val targetDir = File(tempDir, "extracted")

        val count = extractor.extractAll(jar, targetDir)

        assertEquals(1, count)
        assertTrue(File(targetDir, "com/example/MyClass.java").exists())
        assertFalse(File(targetDir, "META-INF/MANIFEST.MF").exists())
    }

    // ========== ZIP Slip Prevention Tests ==========

    @Test
    fun `extractAll rejects entries with path traversal`() {
        // Create a JAR with a malicious entry that tries to escape
        val jarFile = File(tempDir, "malicious.jar")
        ZipOutputStream(jarFile.outputStream()).use { zos ->
            // Normal entry
            zos.putNextEntry(ZipEntry("com/example/Normal.java"))
            zos.write("class Normal".toByteArray())
            zos.closeEntry()

            // Malicious entry attempting path traversal
            zos.putNextEntry(ZipEntry("../../../etc/passwd.java"))
            zos.write("malicious content".toByteArray())
            zos.closeEntry()
        }

        val targetDir = File(tempDir, "extracted")

        val count = extractor.extractAll(jarFile, targetDir)

        // Only the normal entry should be extracted
        assertEquals(1, count)
        assertTrue(File(targetDir, "com/example/Normal.java").exists())
        // Malicious file should not exist anywhere
        assertFalse(File(tempDir, "etc/passwd.java").exists())
        assertFalse(File(targetDir.parentFile, "etc/passwd.java").exists())
    }

    @Test
    fun `extractAll handles entries with leading slash safely`() {
        val jarFile = File(tempDir, "leading-slash.jar")
        ZipOutputStream(jarFile.outputStream()).use { zos ->
            // Entry with leading slash (unusual but possible)
            zos.putNextEntry(ZipEntry("/com/example/MyClass.java"))
            zos.write("class MyClass".toByteArray())
            zos.closeEntry()
        }

        val targetDir = File(tempDir, "extracted")

        // Should handle gracefully (either extract safely or skip)
        val count = extractor.extractAll(jarFile, targetDir)

        // The file should either be extracted safely within targetDir or skipped
        // It should NOT be written to the root filesystem
        assertFalse(File("/com/example/MyClass.java").exists())
    }

    // ========== Edge Cases ==========

    @Test
    fun `extractSource handles empty JAR`() {
        val emptyJar = createTestJar()

        val result = extractor.extractSource(emptyJar, "com.example.MyClass")

        assertNull(result)
    }

    @Test
    fun `extractSource handles class in default package`() {
        val sourceContent = "public class MyClass { }"
        val jar = createTestJar(
            "MyClass.java" to sourceContent
        )

        val result = extractor.extractSource(jar, "MyClass")

        assertNotNull(result)
        assertEquals(sourceContent, result)
    }

    @Test
    fun `extractSource handles deeply nested package`() {
        val sourceContent = "package a.b.c.d.e;\n\npublic class Deep { }"
        val jar = createTestJar(
            "a/b/c/d/e/Deep.java" to sourceContent
        )

        val result = extractor.extractSource(jar, "a.b.c.d.e.Deep")

        assertNotNull(result)
        assertEquals(sourceContent, result)
    }
}

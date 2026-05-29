package codelens.gradle

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Integration test for issue #33: the Gradle init script CodeLens injects must be
 * compatible with Gradle's configuration cache.
 *
 * It builds a tiny multi-module Gradle project in a temp dir with the configuration
 * cache enabled AND `configuration-cache.problems=fail`, then runs the real
 * [GradleProjectResolver.resolve]. If the generated init script touched
 * `Task.project` (or any other forbidden state) at execution time, Gradle would
 * report a configuration-cache problem and — because problems are set to `fail` —
 * abort the build, surfacing as a [ClasspathResolutionException]. So a successful
 * resolve is itself the assertion that the script is config-cache compatible.
 *
 * The temp project pins Gradle 8.14 (the repo's own wrapper version, so the
 * distribution is already cached and config cache is supported). It declares a
 * file dependency to exercise the non-module artifact path (classpath entry, no
 * coordinate mapping).
 *
 * Note: this launches a nested Gradle build via the Tooling API and is therefore
 * heavier than a unit test.
 */
class GradleProjectResolverConfigCacheTest {
    @Test
    fun `resolve succeeds under configuration cache and collects multi-module classpath`(
        @TempDir tempDir: File,
    ) {
        writeMultiModuleProject(tempDir)

        val result = GradleProjectResolver().resolve(tempDir, javaHome = null)

        // Source roots: the init script ran for BOTH the root project (module ":")
        // and the subproject (module ":lib"). The :lib source root can only come
        // from the init script (the Kotlin-side fallback only inspects the root
        // project's own src/), so asserting it proves the script executed.
        val rootJavaMain =
            result.sourceRoots.any {
                it.path.absolutePath.endsWith(join("src", "main", "java")) &&
                    it.language == "java" &&
                    it.sourceSet == "main"
            }
        val libJavaMain =
            result.sourceRoots.any {
                it.path.absolutePath.endsWith(join("lib", "src", "main", "java")) &&
                    it.language == "java" &&
                    it.module == ":lib"
            }
        assertTrue(rootJavaMain, "expected a java/main source root for the root project; got ${result.sourceRoots}")
        assertTrue(libJavaMain, "expected a java/main source root for :lib (module \":lib\"); got ${result.sourceRoots}")

        // The file dependency must appear as a classpath entry but NOT as an
        // artifact mapping (it is not a module component, so it has no coordinates).
        val dummyJarOnClasspath = result.entries.any { it.absolutePath.endsWith(join("libs", "dummy.jar")) }
        val dummyJarMapped = result.artifactMappings.any { it.jarPath.endsWith(join("libs", "dummy.jar")) }
        assertTrue(dummyJarOnClasspath, "expected the file dependency on the classpath; got ${result.entries}")
        assertTrue(!dummyJarMapped, "file dependency must not have an artifact coordinate mapping; got ${result.artifactMappings}")
    }

    @Test
    fun `repeated resolve under configuration cache still succeeds`(
        @TempDir tempDir: File,
    ) {
        writeMultiModuleProject(tempDir)

        val resolver = GradleProjectResolver()
        // Each call uses a fresh temp init script + output property, so the
        // configuration cache misses and re-stores every time — the realistic
        // CodeLens scenario. Both runs must complete without config-cache problems.
        val first = resolver.resolve(tempDir, javaHome = null)
        val second = resolver.resolve(tempDir, javaHome = null)

        assertTrue(first.sourceRoots.isNotEmpty(), "first resolve found no source roots")
        assertTrue(second.sourceRoots.isNotEmpty(), "second resolve found no source roots")
    }

    private fun join(vararg parts: String): String = parts.joinToString(File.separator)

    /**
     * Writes a minimal multi-module Gradle project (root + :lib) with the
     * configuration cache enabled and problems set to fail. Both modules apply the
     * `java` plugin; :lib has a local file dependency. No external repositories or
     * module dependencies are declared, so resolution is fully offline.
     */
    private fun writeMultiModuleProject(dir: File) {
        // Pin Gradle 8.14 (repo wrapper version → distribution already cached, and
        // configuration cache is supported). useBuildDistribution() only needs the
        // properties file, not the gradlew script or wrapper jar.
        val wrapperDir = File(dir, "gradle/wrapper").apply { mkdirs() }
        File(wrapperDir, "gradle-wrapper.properties").writeText(
            """
            distributionBase=GRADLE_USER_HOME
            distributionPath=wrapper/dists
            distributionUrl=https://services.gradle.org/distributions/gradle-8.14-bin.zip
            zipStoreBase=GRADLE_USER_HOME
            zipStorePath=wrapper/dists
            """.trimIndent(),
        )

        File(dir, "settings.gradle").writeText(
            """
            rootProject.name = 'cctest'
            include 'lib'
            """.trimIndent(),
        )

        File(dir, "gradle.properties").writeText(
            """
            org.gradle.configuration-cache=true
            org.gradle.configuration-cache.problems=fail
            """.trimIndent(),
        )

        File(dir, "build.gradle").writeText(
            """
            plugins { id 'java' }
            """.trimIndent(),
        )
        File(dir, "src/main/java").mkdirs()
        File(dir, "src/main/java/Foo.java").writeText("public class Foo {}\n")

        val lib = File(dir, "lib").apply { mkdirs() }
        File(lib, "libs").mkdirs()
        // A file dependency need not be a valid archive; it only needs to exist.
        File(lib, "libs/dummy.jar").writeText("")
        File(lib, "build.gradle").writeText(
            """
            plugins { id 'java' }
            dependencies { implementation files('libs/dummy.jar') }
            """.trimIndent(),
        )
        File(lib, "src/main/java").mkdirs()
        File(lib, "src/main/java/Bar.java").writeText("public class Bar {}\n")
    }
}

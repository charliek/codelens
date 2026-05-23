package codelens.source.model

import codelens.core.model.MavenCoordinates
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MavenCoordinatesTest {
    @Test
    fun `parse valid notation`() {
        val coords = MavenCoordinates.parse("com.google.guava:guava:32.1.3-jre")

        assertNotNull(coords)
        assertEquals("com.google.guava", coords.groupId)
        assertEquals("guava", coords.artifactId)
        assertEquals("32.1.3-jre", coords.version)
    }

    @Test
    fun `parse invalid notation returns null`() {
        assertNull(MavenCoordinates.parse("invalid"))
        assertNull(MavenCoordinates.parse("only:two"))
        assertNull(MavenCoordinates.parse(""))
    }

    @Test
    fun `toGradleNotation returns correct format`() {
        val coords = MavenCoordinates("com.example", "lib", "1.0.0")
        assertEquals("com.example:lib:1.0.0", coords.toGradleNotation())
    }

    @Test
    fun `toRepositoryPath returns correct format`() {
        val coords = MavenCoordinates("com.google.guava", "guava", "32.1.3-jre")
        assertEquals("com/google/guava/guava/32.1.3-jre", coords.toRepositoryPath())
    }

    @Test
    fun `sourceJarName returns correct format`() {
        val coords = MavenCoordinates("com.google.guava", "guava", "32.1.3-jre")
        assertEquals("guava-32.1.3-jre-sources.jar", coords.sourceJarName())
    }

    @Test
    fun `sourceJarUrl returns correct Maven Central URL`() {
        val coords = MavenCoordinates("com.google.guava", "guava", "32.1.3-jre")
        assertEquals(
            "https://repo1.maven.org/maven2/com/google/guava/guava/32.1.3-jre/guava-32.1.3-jre-sources.jar",
            coords.sourceJarUrl(),
        )
    }

    @Test
    fun `parseFromMapping parses correctly`() {
        val result = MavenCoordinates.parseFromMapping("com.example:lib:1.0.0|/path/to/lib.jar")

        assertNotNull(result)
        val (coords, jarPath) = result
        assertEquals("com.example", coords.groupId)
        assertEquals("lib", coords.artifactId)
        assertEquals("1.0.0", coords.version)
        assertEquals("/path/to/lib.jar", jarPath)
    }

    @Test
    fun `parseFromMapping with invalid format returns null`() {
        assertNull(MavenCoordinates.parseFromMapping("no-pipe"))
        assertNull(MavenCoordinates.parseFromMapping("invalid:coords|/path"))
    }
}

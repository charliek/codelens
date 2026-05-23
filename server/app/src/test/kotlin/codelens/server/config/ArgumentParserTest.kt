package codelens.server.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArgumentParserTest {
    // ----- parseTimeoutMinutes -----

    @Test
    fun `parseTimeoutMinutes accepts plain minutes suffix`() {
        assertEquals(30, parseTimeoutMinutes("30m"))
        assertEquals(1, parseTimeoutMinutes("1m"))
    }

    @Test
    fun `parseTimeoutMinutes converts hours suffix to minutes`() {
        assertEquals(60, parseTimeoutMinutes("1h"))
        assertEquals(120, parseTimeoutMinutes("2h"))
    }

    @Test
    fun `parseTimeoutMinutes treats literal 0 as disabled`() {
        assertEquals(0, parseTimeoutMinutes("0"))
    }

    @Test
    fun `parseTimeoutMinutes falls back to 30 on garbage input`() {
        assertEquals(30, parseTimeoutMinutes("not a duration"))
        assertEquals(30, parseTimeoutMinutes(""))
        assertEquals(30, parseTimeoutMinutes("30x"))
        assertEquals(30, parseTimeoutMinutes("h"))
    }

    // ----- findAvailablePort -----

    @Test
    fun `findAvailablePort returns a port in the requested range`() {
        // Use a deliberately uncontested high range.
        val port = findAvailablePort(start = 19000, end = 19010)
        assert(port in 19000..19010) { "Expected port in 19000..19010, got $port" }
    }

    @Test
    fun `findAvailablePort skips ports that are already in use`() {
        // Occupy the first port in a small range, then ask for a port; we
        // should get the next port up, not the occupied one.
        val occupied = ServerSocket(0).use { it.localPort }
        ServerSocket(occupied).use {
            val picked = findAvailablePort(start = occupied, end = occupied + 5)
            assert(picked != occupied) { "findAvailablePort returned the occupied port $occupied" }
            assert(picked in (occupied + 1)..(occupied + 5)) { "Expected next available port near $occupied, got $picked" }
        }
    }

    @Test
    fun `findAvailablePort throws when range is fully exhausted`() {
        // Pick a port we know is free, occupy it, then ask findAvailablePort
        // for a one-port range that consists only of that occupied port.
        val occupied = ServerSocket(0).use { it.localPort }
        ServerSocket(occupied).use {
            assertThrows<IllegalStateException> {
                findAvailablePort(start = occupied, end = occupied)
            }
        }
    }

    // ----- parseArgs -----
    //
    // Note: missing-required-arg behavior is intentionally NOT tested -- kotlinx-cli
    // calls `System.exit` on a missing required option, which would terminate the
    // test JVM. We accept that as a kotlinx-cli implementation detail and only test
    // happy paths from here.

    @Test
    fun `parseArgs returns defaults when only project path is supplied`() {
        val config = parseArgs(arrayOf("--project", "/tmp/sample"))
        assertEquals("/tmp/sample", config.projectPath)
        assertEquals("127.0.0.1", config.host)
        assertEquals(30, config.idleTimeoutMinutes)
        assertEquals(8080, config.portRangeStart)
        assertEquals(8180, config.portRangeEnd)
        assertNull(config.port)
        assertNull(config.classpathFile)
        assertNull(config.projectJavaHome)
    }

    @Test
    fun `parseArgs honors all flags`() {
        val config =
            parseArgs(
                arrayOf(
                    "--project",
                    "/tmp/sample",
                    "--port",
                    "9999",
                    "--host",
                    "0.0.0.0",
                    "--idle-timeout",
                    "2h",
                    "--classpath-file",
                    "/tmp/cp.txt",
                    "--project-java-home",
                    "/opt/java/11",
                ),
            )
        assertEquals("/tmp/sample", config.projectPath)
        assertEquals(9999, config.port)
        assertEquals("0.0.0.0", config.host)
        assertEquals(120, config.idleTimeoutMinutes)
        assertEquals("/tmp/cp.txt", config.classpathFile)
        assertEquals("/opt/java/11", config.projectJavaHome)
    }

    @Test
    fun `parseArgs accepts -p short flag for project`() {
        val config = parseArgs(arrayOf("-p", "/tmp/short"))
        assertEquals("/tmp/short", config.projectPath)
    }
}

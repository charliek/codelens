package codelens.server

import codelens.core.BuildConfig
import codelens.server.config.ServerConfig
import codelens.server.services.AnalysisService
import codelens.server.services.DelayingResolver
import codelens.server.services.EmptyClassGraphProvider
import codelens.server.services.ThrowingClassGraphProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end test for the server's startup readiness contract.
 *
 * Closes adversarial-review finding: previously `CODELENS_READY` was emitted
 * immediately after Netty bound, while the initial scan was still running on
 * a background executor. The CLI would then write `status=READY` to its state
 * file and fire analysis calls against an unready server. This test pins the
 * fix: `CODELENS_READY` must only appear after the scan has actually finished,
 * and a failed scan must emit `CODELENS_ERROR` and exit non-zero rather than
 * leaving a zombie process behind.
 */
class StartupReadinessTest {
    @Test
    fun `CODELENS_READY is emitted only after the initial scan completes`(
        @TempDir tempDir: Path,
    ) {
        val projectDir = tempDir.toFile()
        val scanDelayMs = 200L

        val analysisService =
            AnalysisService(
                projectDir = projectDir,
                classpathResolverOverride = DelayingResolver(scanDelayMs),
                classGraphProviderOverride = EmptyClassGraphProvider(),
            )

        val capturedOut = ByteArrayOutputStream()
        val readinessStream = PrintStream(capturedOut, true, Charsets.UTF_8)
        val config = newTestConfig(projectDir.absolutePath)

        val handle =
            runServer(
                config = config,
                projectDir = projectDir,
                analysisService = analysisService,
                readinessOut = readinessStream,
                exit = { code -> throw ExitInvoked(code) },
            )

        try {
            val output = capturedOut.toString(Charsets.UTF_8)
            val startingIdx = output.indexOf("CODELENS_STARTING")
            val readyIdx = output.indexOf("CODELENS_READY")

            assertTrue(startingIdx >= 0, "Expected CODELENS_STARTING line in output, got:\n$output")
            assertTrue(readyIdx >= 0, "Expected CODELENS_READY line in output, got:\n$output")
            assertTrue(
                startingIdx < readyIdx,
                "CODELENS_STARTING must precede CODELENS_READY in output:\n$output",
            )

            assertTrue(
                analysisService.isReady(),
                "AnalysisService must report ready by the time CODELENS_READY is printed",
            )

            // Sanity: the ready line includes the port (the CLI parses this).
            assertTrue(
                output.contains("CODELENS_READY port=${config.port} host=${config.host} version=${BuildConfig.VERSION}"),
                "CODELENS_READY line missing expected fields:\n$output",
            )
            assertFalse(
                output.contains("CODELENS_ERROR"),
                "Successful startup must not emit CODELENS_ERROR:\n$output",
            )
        } finally {
            handle.stop()
        }
    }

    @Test
    fun `scan failure emits CODELENS_ERROR and exits non-zero`(
        @TempDir tempDir: Path,
    ) {
        val projectDir = tempDir.toFile()
        val failure = RuntimeException("classgraph blew up")

        val analysisService =
            AnalysisService(
                projectDir = projectDir,
                classpathResolverOverride =
                    codelens.server.services.StaticResolver(),
                classGraphProviderOverride = ThrowingClassGraphProvider(failure),
            )

        val capturedOut = ByteArrayOutputStream()
        val readinessStream = PrintStream(capturedOut, true, Charsets.UTF_8)
        val config = newTestConfig(projectDir.absolutePath)

        val thrown =
            assertThrows<ExitInvoked> {
                runServer(
                    config = config,
                    projectDir = projectDir,
                    analysisService = analysisService,
                    readinessOut = readinessStream,
                    exit = { code -> throw ExitInvoked(code) },
                )
            }

        assertEquals(1, thrown.code, "Scan failure must exit with code 1")

        val output = capturedOut.toString(Charsets.UTF_8)
        assertTrue(
            output.contains("CODELENS_ERROR reason=SCAN"),
            "Expected CODELENS_ERROR with reason=SCAN in output:\n$output",
        )
        assertTrue(
            output.contains("classgraph blew up"),
            "CODELENS_ERROR must include the underlying message:\n$output",
        )
        assertFalse(
            output.contains("CODELENS_READY"),
            "Failed startup must not emit CODELENS_READY:\n$output",
        )
    }

    // ----- helpers -----

    /**
     * Builds a ServerConfig pinned to a random free port and with the idle
     * monitor disabled. Tests own the port allocation explicitly so each test
     * gets a fresh port (the production code path uses findAvailablePort,
     * which would also work but is less explicit).
     */
    private fun newTestConfig(projectPath: String): ServerConfig {
        val freePort = ServerSocket(0).use { it.localPort }
        return ServerConfig(
            projectPath = projectPath,
            port = freePort,
            host = "127.0.0.1",
            portRangeStart = 0,
            portRangeEnd = 0,
            idleTimeoutMinutes = 0,
        )
    }
}

/** Thrown by the test's exit lambda so we can assert the exit code without killing the JVM. */
private class ExitInvoked(
    val code: Int,
) : RuntimeException("exit($code)")

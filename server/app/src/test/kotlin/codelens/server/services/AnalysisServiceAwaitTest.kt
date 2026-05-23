package codelens.server.services

import codelens.core.model.ProjectStatus
import codelens.gradle.ClasspathResolutionException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies that the initial-scan readiness contract behaves correctly:
 * `awaitInitialScan()` actually waits for the background scan, and surfaces
 * the right terminal status + underlying error.
 *
 * This is the unit-level counterpart to [codelens.server.StartupReadinessTest],
 * which proves the end-to-end stdout ordering.
 */
class AnalysisServiceAwaitTest {
    @Test
    fun `awaitInitialScan blocks until the background scan completes`(
        @TempDir tempDir: Path,
    ) {
        val projectDir = tempDir.toFile().also { it.mkdirs() }
        val scanDelayMs = 200L
        val slowResolver = DelayingResolver(scanDelayMs)
        val service =
            AnalysisService(
                projectDir = projectDir,
                classpathResolverOverride = slowResolver,
                classGraphProviderOverride = EmptyClassGraphProvider(),
            )

        try {
            val startNanos = System.nanoTime()
            val status = service.awaitInitialScan()
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

            assertEquals(ProjectStatus.READY, status, "Successful scan should resolve to READY")
            assertTrue(
                elapsedMs >= scanDelayMs - 20, // 20ms tolerance for scheduler jitter
                "awaitInitialScan should not return before the scan finishes; elapsed=${elapsedMs}ms",
            )
            assertNull(
                service.getInitialScanError(),
                "Successful scan should not record an error",
            )
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `awaitInitialScan returns ERROR and captures ClasspathResolutionException`(
        @TempDir tempDir: Path,
    ) {
        val projectDir = tempDir.toFile().also { it.mkdirs() }
        val failure = ClasspathResolutionException("simulated gradle failure")
        val service =
            AnalysisService(
                projectDir = projectDir,
                classpathResolverOverride = ThrowingResolver(failure),
                classGraphProviderOverride = EmptyClassGraphProvider(),
            )

        try {
            val status = service.awaitInitialScan()
            assertEquals(ProjectStatus.ERROR, status)
            val captured = service.getInitialScanError()
            assertNotNull(captured, "Initial scan error should be captured")
            assertEquals(failure, captured)
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `awaitInitialScan returns ERROR for generic scan exceptions`(
        @TempDir tempDir: Path,
    ) {
        val projectDir = tempDir.toFile().also { it.mkdirs() }
        val failure = RuntimeException("classgraph blew up")
        val service =
            AnalysisService(
                projectDir = projectDir,
                classpathResolverOverride = StaticResolver(),
                classGraphProviderOverride = ThrowingClassGraphProvider(failure),
            )

        try {
            val status = service.awaitInitialScan()
            assertEquals(ProjectStatus.ERROR, status)
            val captured = service.getInitialScanError()
            assertNotNull(captured)
            assertEquals(failure, captured)
        } finally {
            service.shutdown()
        }
    }
}

package codelens.server.services

import codelens.core.model.ProjectInfo
import codelens.core.model.ProjectStatus
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class AnalysisService(private val projectDir: File) {
    private val logger = LoggerFactory.getLogger(AnalysisService::class.java)

    private val projectInfo: AtomicReference<ProjectInfo>
    private val scannedAt: AtomicReference<Instant?> = AtomicReference(null)

    init {
        projectInfo = AtomicReference(ProjectInfo(
            name = projectDir.name,
            path = projectDir.absolutePath,
            status = ProjectStatus.LOADING
        ))

        // Simulate initial scan (stub - will be ClassGraph later)
        Thread {
            try {
                Thread.sleep(500) // Simulate brief loading
                val now = Instant.now()
                scannedAt.set(now)
                projectInfo.updateAndGet { current ->
                    current.copy(
                        status = ProjectStatus.READY,
                        classCount = 42,  // Stub values
                        handlerCount = 3,
                        scannedAt = now.toString()
                    )
                }
                logger.info("Initial project scan completed for ${projectDir.name}")
            } catch (e: Exception) {
                logger.error("Failed to scan project ${projectDir.name}", e)
                projectInfo.updateAndGet { current ->
                    current.copy(status = ProjectStatus.ERROR)
                }
            }
        }.start()
    }

    fun getProjectInfo(): ProjectInfo = projectInfo.get()

    fun refresh() {
        projectInfo.updateAndGet { it.copy(status = ProjectStatus.LOADING) }
        try {
            Thread.sleep(200) // Simulate rescan
            val now = Instant.now()
            scannedAt.set(now)
            projectInfo.updateAndGet { current ->
                current.copy(
                    status = ProjectStatus.READY,
                    scannedAt = now.toString()
                )
            }
            logger.info("Project refresh completed for ${projectDir.name}")
        } catch (e: Exception) {
            logger.error("Failed to refresh project ${projectDir.name}", e)
            projectInfo.updateAndGet { it.copy(status = ProjectStatus.ERROR) }
        }
    }
}

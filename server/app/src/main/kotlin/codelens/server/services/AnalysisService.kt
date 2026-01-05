package codelens.server.services

import codelens.core.model.ProjectInfo
import codelens.core.model.ProjectStatus
import java.io.File
import java.time.Instant

class AnalysisService(private val projectDir: File) {
    private var projectInfo: ProjectInfo
    private var scannedAt: Instant? = null

    init {
        projectInfo = ProjectInfo(
            name = projectDir.name,
            path = projectDir.absolutePath,
            status = ProjectStatus.LOADING
        )

        // Simulate initial scan (stub - will be ClassGraph later)
        Thread {
            Thread.sleep(500) // Simulate brief loading
            scannedAt = Instant.now()
            projectInfo = projectInfo.copy(
                status = ProjectStatus.READY,
                classCount = 42,  // Stub values
                handlerCount = 3,
                scannedAt = scannedAt.toString()
            )
        }.start()
    }

    fun getProjectInfo(): ProjectInfo = projectInfo

    fun refresh() {
        projectInfo = projectInfo.copy(status = ProjectStatus.LOADING)
        Thread.sleep(200) // Simulate rescan
        scannedAt = Instant.now()
        projectInfo = projectInfo.copy(
            status = ProjectStatus.READY,
            scannedAt = scannedAt.toString()
        )
    }
}

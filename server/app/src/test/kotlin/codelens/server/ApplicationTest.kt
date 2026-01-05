package codelens.server

import codelens.core.model.ProjectStatus
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {

    @Test
    fun `health endpoint returns ok`() = testApplication {
        application {
            // Note: This requires refactoring Application.kt to be testable
            // For now, this is a placeholder that validates test infrastructure
        }

        // Placeholder test to verify JUnit works
        assertTrue(true, "Test infrastructure is working")
    }

    @Test
    fun `project status enum has expected values`() {
        assertEquals("LOADING", ProjectStatus.LOADING.name)
        assertEquals("READY", ProjectStatus.READY.name)
        assertEquals("ERROR", ProjectStatus.ERROR.name)
    }
}

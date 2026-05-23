package codelens.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip serialization tests for the wire types in `codelens.core.model`.
 *
 * These tests guard the JSON contract that both the Python CLI and any
 * future port (Go, Kotlin Multiplatform, etc.) depend on. If a field is
 * renamed or removed here, all clients must be updated.
 */
class SerializationRoundtripTest {
    private val json =
        Json {
            prettyPrint = false
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    @Test
    fun `ProjectInfo round-trips and uses expected field names`() {
        val original =
            ProjectInfo(
                name = "user-service",
                path = "/work/user-service",
                status = ProjectStatus.READY,
                classCount = 42,
                handlerCount = 3,
                scannedAt = "2026-05-21T00:00:00Z",
            )

        val encoded = json.encodeToString(original)
        assertContainsAll(
            encoded,
            "\"name\"",
            "\"path\"",
            "\"status\"",
            "\"classCount\"",
            "\"handlerCount\"",
            "\"scannedAt\"",
        )

        val decoded = json.decodeFromString<ProjectInfo>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `ProjectStatus enum names are stable`() {
        // The Python CLI parses these as exact string values; renaming any
        // of them would break wire compatibility.
        assertEquals("LOADING", ProjectStatus.LOADING.name)
        assertEquals("READY", ProjectStatus.READY.name)
        assertEquals("ERROR", ProjectStatus.ERROR.name)
        // STARTING is intentionally a CLI-only state (see cli/src/codelens_cli/models.py);
        // ensure we have not accidentally added it on the server side.
        assertEquals(3, ProjectStatus.entries.size)
    }

    @Test
    fun `ServerInfo round-trips with all required fields`() {
        val original =
            ServerInfo(
                version = "0.1.0",
                apiVersion = "v1",
                projectPath = "/work/user-service",
                projectName = "user-service",
                port = 8080,
                host = "127.0.0.1",
                status = "READY",
                startedAt = "2026-05-21T00:00:00Z",
                uptime = "5s",
                lastActivityAt = "2026-05-21T00:00:01Z",
                idleDuration = "0s",
                idleTimeout = "30m",
            )

        val encoded = json.encodeToString(original)
        assertContainsAll(
            encoded,
            "\"version\"",
            "\"apiVersion\"",
            "\"projectPath\"",
            "\"projectName\"",
            "\"idleDuration\"",
            "\"idleTimeout\"",
        )

        val decoded = json.decodeFromString<ServerInfo>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `HealthResponse and ReadyResponse round-trip`() {
        val health = HealthResponse(status = "UP", timestamp = "2026-05-21T00:00:00Z")
        assertEquals(health, json.decodeFromString(json.encodeToString(health)))

        val ready = ReadyResponse(ready = true, status = "READY", project = "user-service")
        assertEquals(ready, json.decodeFromString(json.encodeToString(ready)))
    }

    @Test
    fun `ErrorResponse round-trips and always includes error=true`() {
        val original =
            ErrorResponse(
                code = 500,
                type = "InternalServerError",
                message = "boom",
            )

        val encoded = json.encodeToString(original)
        assertTrue(encoded.contains("\"error\":true"), "ErrorResponse should always wire error=true, got: $encoded")

        val decoded = json.decodeFromString<ErrorResponse>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `ActivityResponse and ShutdownResponse round-trip`() {
        val activity = ActivityResponse(lastActivityAt = "2026-05-21T00:00:00Z")
        assertEquals(activity, json.decodeFromString(json.encodeToString(activity)))

        val shutdown = ShutdownResponse(status = "shutting_down")
        assertEquals(shutdown, json.decodeFromString(json.encodeToString(shutdown)))
    }

    @Test
    fun `ClassInfo round-trips with nested types`() {
        val original =
            ClassInfo(
                name = ClassName(fqn = "com.example.UserHandler", simpleName = "UserHandler", packageName = "com.example"),
                source = ClassSource.PROJECT,
                visibility = Visibility.PUBLIC,
                isInterface = false,
                superclass = "java.lang.Object",
                interfaces = listOf("ratpack.handling.Handler"),
                annotations = listOf(AnnotationInfo(type = "javax.inject.Singleton")),
                methods =
                    listOf(
                        MethodInfo(
                            name = "handle",
                            visibility = Visibility.PUBLIC,
                            returnType = "void",
                            parameters =
                                listOf(
                                    ParameterInfo(
                                        name = "ctx",
                                        type = "ratpack.handling.Context",
                                    ),
                                ),
                        ),
                    ),
                jarPath = null,
            )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ClassInfo>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `Visibility enum names are stable`() {
        assertEquals("PUBLIC", Visibility.PUBLIC.name)
        assertEquals("PROTECTED", Visibility.PROTECTED.name)
        assertEquals("PACKAGE_PRIVATE", Visibility.PACKAGE_PRIVATE.name)
        assertEquals("PRIVATE", Visibility.PRIVATE.name)
    }

    @Test
    fun `ClassSource enum names are stable`() {
        assertEquals("PROJECT", ClassSource.PROJECT.name)
        assertEquals("LIBRARY", ClassSource.LIBRARY.name)
        assertEquals("JDK", ClassSource.JDK.name)
    }

    @Test
    fun `ignoreUnknownKeys allows future fields without breaking decode`() {
        // Simulates a future server sending extra fields. Clients on this
        // version must still decode the known fields cleanly.
        val payloadWithExtra =
            """
            {
                "name": "user-service",
                "path": "/work/user-service",
                "status": "READY",
                "futureField": "ignore me",
                "anotherFutureField": 42
            }
            """.trimIndent()

        val decoded = json.decodeFromString<ProjectInfo>(payloadWithExtra)
        assertEquals("user-service", decoded.name)
        assertEquals(ProjectStatus.READY, decoded.status)
    }

    private fun assertContainsAll(
        encoded: String,
        vararg fragments: String,
    ) {
        fragments.forEach { fragment ->
            assertTrue(
                encoded.contains(fragment),
                "Expected encoded JSON to contain `$fragment`, got: $encoded",
            )
        }
    }
}

package codelens.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip serialization tests for the wire types in `codelens.core.model`.
 *
 * These tests guard the JSON contract that the CLI and any other client
 * (third-party integrations, future ports, etc.) depend on. If a field is
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
                scannedAt = "2026-05-21T00:00:00Z",
            )

        val encoded = json.encodeToString(original)
        assertContainsAll(
            encoded,
            "\"name\"",
            "\"path\"",
            "\"status\"",
            "\"classCount\"",
            "\"scannedAt\"",
        )

        val decoded = json.decodeFromString<ProjectInfo>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `ProjectStatus enum names are stable`() {
        // The CLI parses these as exact string values; renaming any
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
                name = ClassName(fqn = "com.example.UserService", simpleName = "UserService", packageName = "com.example"),
                source = ClassSource.PROJECT,
                visibility = Visibility.PUBLIC,
                isInterface = false,
                superclass = "java.lang.Object",
                interfaces = listOf("java.lang.Runnable"),
                annotations =
                    listOf(
                        AnnotationInfo(
                            type = "javax.inject.Named",
                            parameters = mapOf("value" to AnnotationValue(AnnotationValueKind.STRING, value = "userService")),
                        ),
                    ),
                methods =
                    listOf(
                        MethodInfo(
                            name = "run",
                            descriptor = "(Ljava/lang/String;)V",
                            visibility = Visibility.PUBLIC,
                            returnType = "void",
                            parameters =
                                listOf(
                                    ParameterInfo(
                                        name = "input",
                                        type = "java.lang.String",
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
    fun `AnnotationInfo with typed attribute values round-trips and encodes sparsely`() {
        // Mirrors a real @RequestMapping: value/path are String arrays, method is
        // an enum array, plus a class literal and a nested annotation.
        val original =
            AnnotationInfo(
                type = "org.springframework.web.bind.annotation.RequestMapping",
                parameters =
                    mapOf(
                        "value" to
                            AnnotationValue(
                                AnnotationValueKind.ARRAY,
                                items = listOf(AnnotationValue(AnnotationValueKind.STRING, value = "/products/{id}")),
                            ),
                        "method" to
                            AnnotationValue(
                                AnnotationValueKind.ARRAY,
                                items =
                                    listOf(
                                        AnnotationValue(
                                            AnnotationValueKind.ENUM,
                                            value = "GET",
                                            enumType = "org.springframework.web.bind.annotation.RequestMethod",
                                        ),
                                    ),
                            ),
                        "clazz" to AnnotationValue(AnnotationValueKind.CLASS, value = "java.lang.String"),
                        "nested" to
                            AnnotationValue(
                                AnnotationValueKind.ANNOTATION,
                                annotation = AnnotationInfo(type = "com.example.Meta"),
                            ),
                    ),
            )

        val encoded = json.encodeToString(original)
        // @EncodeDefault(NEVER) keeps each node sparse even though this Json (like
        // the server's) sets encodeDefaults = true: absent optional fields are
        // omitted, not serialized as null.
        assertTrue(!encoded.contains("\"value\":null"), "value should be omitted when null, got: $encoded")
        assertTrue(!encoded.contains("\"items\":null"), "items should be omitted when null, got: $encoded")
        assertTrue(!encoded.contains("\"enumType\":null"), "enumType should be omitted when null, got: $encoded")
        assertTrue(!encoded.contains("\"annotation\":null"), "annotation should be omitted when null, got: $encoded")
        // The literal kind strings a consumer's jq branches on.
        assertContainsAll(encoded, "\"kind\":\"ARRAY\"", "\"kind\":\"ENUM\"", "\"kind\":\"CLASS\"", "\"kind\":\"STRING\"")

        val decoded = json.decodeFromString<AnnotationInfo>(encoded)
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
    fun `AnnotationValueKind enum names are stable`() {
        // Wire contract: consumers (a skill's jq) branch on these literal strings
        // (e.g. `.kind == "ENUM"`, `.kind == "ARRAY"`); renaming any would
        // silently break structured annotation-value extraction.
        assertEquals("STRING", AnnotationValueKind.STRING.name)
        assertEquals("BOOLEAN", AnnotationValueKind.BOOLEAN.name)
        assertEquals("BYTE", AnnotationValueKind.BYTE.name)
        assertEquals("SHORT", AnnotationValueKind.SHORT.name)
        assertEquals("INT", AnnotationValueKind.INT.name)
        assertEquals("LONG", AnnotationValueKind.LONG.name)
        assertEquals("FLOAT", AnnotationValueKind.FLOAT.name)
        assertEquals("DOUBLE", AnnotationValueKind.DOUBLE.name)
        assertEquals("CHAR", AnnotationValueKind.CHAR.name)
        assertEquals("CLASS", AnnotationValueKind.CLASS.name)
        assertEquals("ENUM", AnnotationValueKind.ENUM.name)
        assertEquals("ANNOTATION", AnnotationValueKind.ANNOTATION.name)
        assertEquals("ARRAY", AnnotationValueKind.ARRAY.name)
        assertEquals(13, AnnotationValueKind.entries.size)
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

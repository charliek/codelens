package codelens.server

import codelens.core.model.AnnotationInfo
import codelens.core.model.AnnotationScope
import codelens.core.model.AnnotationUsage
import codelens.core.model.AnnotationUsageTarget
import codelens.core.model.AnnotationUsagesResponse
import codelens.core.model.AnnotationValue
import codelens.core.model.AnnotationValueKind
import codelens.core.model.ClassSource
import codelens.server.routes.analysisRoutes
import codelens.server.services.AnalysisService
import codelens.server.services.EmptyClassGraphProvider
import codelens.server.services.StaticResolver
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * Route-level tests for `GET /api/v1/annotations/usages/{fqn}`: scope/pagination
 * parsing + validation, the deterministic sort (including the collision tiebreaker),
 * `countsByTarget`, and `appliedFilter`. Drives the real route with a fake provider
 * returning a fixed, scrambled list — the validation 400s in particular cannot be
 * reached through the CLI (which validates `--scope` client-side).
 */
class AnnotationUsagesRouteTest {
    private val json = Json { ignoreUnknownKeys = true }

    // Scrambled on purpose, with two METHOD usages that collide on every sort key
    // except the annotation's attributes (v=1 vs v=2) — exercises the final tiebreaker.
    private val fixed =
        listOf(
            usage(AnnotationUsageTarget.METHOD, "com.b.B", "com.b", method = "m", descriptor = "()V"),
            usage(AnnotationUsageTarget.CLASS, "com.a.A", "com.a"),
            usage(AnnotationUsageTarget.METHOD, "com.a.A", "com.a", method = "m", descriptor = "()V", v = "2"),
            usage(AnnotationUsageTarget.METHOD, "com.a.A", "com.a", method = "m", descriptor = "()V", v = "1"),
        )

    private fun usage(
        target: AnnotationUsageTarget,
        classFqn: String,
        packageName: String,
        method: String? = null,
        descriptor: String? = null,
        v: String? = null,
    ): AnnotationUsage =
        AnnotationUsage(
            target = target,
            classFqn = classFqn,
            classSimpleName = classFqn.substringAfterLast('.'),
            packageName = packageName,
            source = ClassSource.PROJECT,
            method = method,
            descriptor = descriptor,
            annotation =
                AnnotationInfo(
                    type = "com.x.A",
                    parameters =
                        if (v != null) mapOf("v" to AnnotationValue(AnnotationValueKind.STRING, value = v)) else emptyMap(),
                ),
        )

    private inner class FixedUsagesProvider : EmptyClassGraphProvider() {
        override fun getAnnotationUsages(
            annotationFqn: String,
            scope: AnnotationScope,
            includeLibraries: Boolean,
        ): List<AnnotationUsage> = fixed
    }

    private fun ApplicationTestBuilder.installRoutes(service: AnalysisService) {
        application {
            install(ContentNegotiation) {
                json(
                    Json {
                        encodeDefaults = true
                        ignoreUnknownKeys = true
                    },
                )
            }
            routing { analysisRoutes(service) }
        }
    }

    private fun newService(tempDir: Path): AnalysisService =
        AnalysisService(
            projectDir = tempDir.toFile().also { it.mkdirs() },
            classpathResolverOverride = StaticResolver(),
            classGraphProviderOverride = FixedUsagesProvider(),
        ).also { it.awaitInitialScan() }

    @Test
    fun `default scope is ALL and the result is deterministically sorted with a collision tiebreaker`(
        @TempDir tempDir: Path,
    ) = testApplication {
        val service = newService(tempDir)
        installRoutes(service)
        try {
            val resp = client.get("/api/v1/annotations/usages/com.x.A")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = json.decodeFromString<AnnotationUsagesResponse>(resp.bodyAsText())

            assertEquals(AnnotationScope.ALL, body.appliedFilter.scope)
            assertEquals(4, body.totalCount)
            assertEquals(mapOf("CLASS" to 1, "METHOD" to 3), body.countsByTarget)

            // classFqn asc, then target.ordinal (CLASS<METHOD), then annotation attrs (v=1 before v=2).
            val order = body.usages.map { Triple(it.target, it.classFqn, it.annotation.parameters["v"]?.value) }
            assertEquals(
                listOf(
                    Triple(AnnotationUsageTarget.CLASS, "com.a.A", null),
                    Triple(AnnotationUsageTarget.METHOD, "com.a.A", "1"),
                    Triple(AnnotationUsageTarget.METHOD, "com.a.A", "2"),
                    Triple(AnnotationUsageTarget.METHOD, "com.b.B", null),
                ),
                order,
            )
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `scope is parsed case-insensitively and echoed in appliedFilter`(
        @TempDir tempDir: Path,
    ) = testApplication {
        val service = newService(tempDir)
        installRoutes(service)
        try {
            val resp = client.get("/api/v1/annotations/usages/com.x.A?scope=Method")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = json.decodeFromString<AnnotationUsagesResponse>(resp.bodyAsText())
            assertEquals(AnnotationScope.METHOD, body.appliedFilter.scope)
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `invalid scope is a 400`(
        @TempDir tempDir: Path,
    ) = testApplication {
        val service = newService(tempDir)
        installRoutes(service)
        try {
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/annotations/usages/com.x.A?scope=bogus").status)
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `non-positive size and negative page are 400`(
        @TempDir tempDir: Path,
    ) = testApplication {
        val service = newService(tempDir)
        installRoutes(service)
        try {
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/annotations/usages/com.x.A?size=0").status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/annotations/usages/com.x.A?page=-1").status)
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `pagination slices the sorted result`(
        @TempDir tempDir: Path,
    ) = testApplication {
        val service = newService(tempDir)
        installRoutes(service)
        try {
            val page0 =
                json.decodeFromString<AnnotationUsagesResponse>(
                    client.get("/api/v1/annotations/usages/com.x.A?size=2").bodyAsText(),
                )
            assertEquals(4, page0.totalCount)
            assertEquals(2, page0.totalPages)
            assertEquals(2, page0.pageSize)
            assertEquals(0, page0.page)
            assertEquals(2, page0.usages.size)
            assertEquals(AnnotationUsageTarget.CLASS, page0.usages.first().target)

            val page1 =
                json.decodeFromString<AnnotationUsagesResponse>(
                    client.get("/api/v1/annotations/usages/com.x.A?size=2&page=1").bodyAsText(),
                )
            assertEquals(1, page1.page)
            assertEquals(2, page1.usages.size)
            assertEquals("com.b.B", page1.usages.last().classFqn)
        } finally {
            service.shutdown()
        }
    }
}

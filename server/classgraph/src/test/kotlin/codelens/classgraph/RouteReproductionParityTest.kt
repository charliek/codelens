package codelens.classgraph

import codelens.classgraph.ratpack.BytecodeRouteExtractor
import codelens.classgraph.ratpack.ExtractedRoute
import codelens.core.model.CallSite
import codelens.core.model.ConstantKind
import codelens.core.model.ratpack.HttpMethod
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The route-reproduction parity gate (plan §PR1).
 *
 * Proves the new general [CallSiteExtractor] (`getCalls`) can reproduce exactly
 * what the legacy, Ratpack-specific [BytecodeRouteExtractor] produced for the
 * hardest real case — `sample.api.UsersApi` in `test-fixtures/sample-ratpack-app`.
 * If parity holds here, the legacy extractor can be deleted (PR4) without
 * losing capability.
 *
 * This test references the legacy extractor on purpose and is removed together
 * with it in PR4; the permanent regression coverage for `calls` lives in
 * [CallSiteExtractorTest] and the e2e golden suite.
 *
 * It scans only the fixture's compiled output (no Ratpack jars are needed to
 * read invocation owner names from `UsersApi`'s constant pool) and skips
 * cleanly when the fixture has not been compiled, so `./gradlew test` on a
 * clean checkout still passes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RouteReproductionParityTest {
    private lateinit var provider: ClassGraphProviderImpl

    private val usersApiFqn = "sample.api.UsersApi"
    private val blockingHandlerFqn = "sample.handlers.BlockingHandler"

    @BeforeAll
    fun setup() {
        val classDirs = locateFixtureClassDirs()
        assumeTrue(
            classDirs.isNotEmpty(),
            "sample-ratpack-app is not compiled; run `./gradlew -p test-fixtures/sample-ratpack-app classes` to enable the parity gate",
        )
        provider = ClassGraphProviderImpl()
        provider.scan(classDirs, classDirs.toSet())
        assumeTrue(
            provider.getClass(usersApiFqn) != null,
            "compiled $usersApiFqn not found under the fixture build output",
        )
    }

    /**
     * The gate: the legacy extractor's routes for UsersApi must be exactly
     * reproducible from `getCalls` + a documented Chain-call filter.
     */
    @Test
    fun `getCalls reproduces the legacy route extraction for UsersApi`() {
        val legacy = BytecodeRouteExtractor(provider).extractRoutes(usersApiFqn)

        // Action<Chain> erasure emits a synthetic execute(Object) bridge as well
        // as the real execute(Chain); select the real one by descriptor. (Its
        // Chain calls are what define routes; the bridge only re-dispatches.)
        val calls =
            provider
                .getCalls(usersApiFqn, "execute")
                .methods
                .first { it.descriptor.contains("ratpack/handling/Chain") }
                .calls
        val reproduced = reproduceRoutes(calls)

        assertEquals(
            legacy,
            reproduced,
            "routes reproduced from getCalls must match the legacy BytecodeRouteExtractor output",
        )
        // Guard against a vacuous pass: the fixture defines real routes.
        assertTrue(legacy.isNotEmpty(), "expected the fixture to define at least one route")
    }

    /**
     * The new primitive sees real `Blocking.get` / `Promise` calls in a handler
     * body — the signal the heuristic PromiseDetector could only guess at.
     */
    @Test
    fun `getCalls surfaces real Blocking and Promise calls in a handler body`() {
        val calls =
            provider
                .getCalls(blockingHandlerFqn, "handle")
                .methods
                .single()
                .calls
        assertTrue(calls.isNotEmpty(), "handle(Context) should make calls")
        assertTrue(
            calls.any { it.ownerType == "ratpack.exec.Blocking" && it.methodName == "get" },
            "expected a real ratpack.exec.Blocking.get call; got ${calls.map { it.ownerType + "." + it.methodName }}",
        )
        assertTrue(
            calls.any { it.ownerType == "ratpack.exec.Promise" },
            "expected calls on ratpack.exec.Promise (map/then)",
        )
    }

    // ------------------------------------------------------------------------
    // Route reproduction: the documented filter the skill encodes — restrict
    // getCalls to Chain routing methods, take the string constant as the path
    // and the class-literal constant as the handler. Mirrors the legacy
    // extractor's per-call decisions so the outputs are byte-identical.
    // ------------------------------------------------------------------------

    private val httpMethodNames =
        mapOf(
            "get" to HttpMethod.GET,
            "post" to HttpMethod.POST,
            "put" to HttpMethod.PUT,
            "patch" to HttpMethod.PATCH,
            "delete" to HttpMethod.DELETE,
            "options" to HttpMethod.OPTIONS,
            "head" to HttpMethod.HEAD,
            "all" to HttpMethod.ALL,
        )

    private fun reproduceRoutes(calls: List<CallSite>): List<ExtractedRoute> {
        val routes = mutableListOf<ExtractedRoute>()
        for (call in calls) {
            if (call.ownerType != "ratpack.handling.Chain") continue
            val path = call.constantArgs.lastOrNull { it.kind == ConstantKind.STRING }?.value
            val handlerClass = call.constantArgs.lastOrNull { it.kind == ConstantKind.CLASS }?.value

            val httpMethod = httpMethodNames[call.methodName]
            when {
                httpMethod != null -> addHttpMethodRoute(routes, httpMethod, path, handlerClass)
                call.methodName == "prefix" ->
                    if (path != null) {
                        val nested = handlerClass != null && isChainAction(handlerClass)
                        routes.add(ExtractedRoute(path, HttpMethod.ALL, handlerClass, nested))
                    }
                call.methodName == "path" ->
                    if (path != null) {
                        routes.add(ExtractedRoute(path, HttpMethod.ALL, handlerClass, false))
                    }
            }
        }
        return routes
    }

    private fun addHttpMethodRoute(
        routes: MutableList<ExtractedRoute>,
        method: HttpMethod,
        path: String?,
        handlerClass: String?,
    ) {
        if (method == HttpMethod.ALL && (path == null || !looksLikePath(path))) {
            if (handlerClass != null) {
                routes.add(ExtractedRoute("", method, handlerClass, false))
            }
            return
        }
        if (path != null && looksLikePath(path)) {
            routes.add(ExtractedRoute(path, method, handlerClass, false))
        } else if (handlerClass != null) {
            routes.add(ExtractedRoute("", method, handlerClass, false))
        }
    }

    private fun looksLikePath(str: String): Boolean {
        if (str.length < 2) return false
        if (!Regex("^[a-zA-Z0-9/:_-]+$").matches(str)) return false
        if (str.startsWith("_") && !str.contains("/")) return false
        return true
    }

    private fun isChainAction(className: String): Boolean {
        val classInfo = provider.getClass(className) ?: return false
        return classInfo.interfaces.any { it.contains("ratpack.func.Action") } ||
            classInfo.methods.any { m ->
                m.name == "execute" && m.parameters.size == 1 && m.parameters[0].type.contains("Chain")
            }
    }

    // ------------------------------------------------------------------------

    private fun locateFixtureClassDirs(): List<File> {
        val repoRoot = findRepoRoot() ?: return emptyList()
        val classesRoot = File(repoRoot, "test-fixtures/sample-ratpack-app/build/classes")
        if (!classesRoot.isDirectory) return emptyList()
        return listOf(
            File(classesRoot, "java/main"),
            File(classesRoot, "kotlin/main"),
        ).filter { it.isDirectory }
    }

    private fun findRepoRoot(): File? {
        var dir: File? = File("").absoluteFile
        repeat(10) {
            val d = dir ?: return null
            if (File(d, "gradlew").exists() && File(d, "settings.gradle.kts").exists()) return d
            dir = d.parentFile
        }
        return null
    }
}

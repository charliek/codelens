package codelens.classgraph

import codelens.core.model.AnnotationInfo
import codelens.core.model.AnnotationScope
import codelens.core.model.AnnotationUsage
import codelens.core.model.AnnotationUsageTarget
import codelens.core.model.AnnotationValue
import codelens.core.model.AnnotationValueKind
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the scope-aware `annotations usages` projection (#43) end-to-end
 * through a real provider scan against the [AnnotationUsageSample] fixture. The
 * projection is in-memory over the already-converted class graph, so this proves
 * each scope selects the right targets, folds constructors into `method` (as
 * `<init>` with a derived signature), captures parameter index/type, and inherits
 * ClassGraph's meta-annotation expansion with the synthesized attribute payload.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnnotationUsagesProjectionTest {
    private lateinit var provider: ClassGraphProviderImpl

    private val pkg = "codelens.classgraph.fixtures"
    private val sampleFqn = "$pkg.AnnotationUsageSample"
    private val markerFqn = "$pkg.Marker"
    private val tagFqn = "$pkg.Tag"
    private val baseFqn = "$pkg.Base"

    @BeforeAll
    fun setup() {
        val classpathDirs =
            System
                .getProperty("java.class.path")
                .split(File.pathSeparator)
                .map { File(it) }
                .filter { it.isDirectory }

        provider = ClassGraphProviderImpl()
        provider.scan(classpathDirs, classpathDirs.toSet())
    }

    /** Usages of [fqn] at [scope], restricted to the fixture class so unrelated scans don't leak in. */
    private fun usages(
        fqn: String,
        scope: AnnotationScope,
    ): List<AnnotationUsage> = provider.getAnnotationUsages(fqn, scope, includeLibraries = false).filter { it.classFqn == sampleFqn }

    private fun tag(value: String): AnnotationInfo =
        AnnotationInfo(type = tagFqn, parameters = mapOf("value" to AnnotationValue(AnnotationValueKind.STRING, value = value)))

    @Test
    fun `class scope finds the class-level annotation`() {
        val result = usages(markerFqn, AnnotationScope.CLASS)
        assertEquals(1, result.size, "expected one class-level @Marker usage, got $result")
        assertEquals(AnnotationUsageTarget.CLASS, result.single().target)
        assertEquals(sampleFqn, result.single().classFqn)
    }

    @Test
    fun `method scope surfaces an annotated constructor as CONSTRUCTOR with init and a derived descriptor`() {
        val result = usages(markerFqn, AnnotationScope.METHOD)
        val ctor = assertNotNull(result.singleOrNull { it.target == AnnotationUsageTarget.CONSTRUCTOR }, "got $result")
        assertEquals("<init>", ctor.method)
        assertEquals("(java.lang.String)", ctor.descriptor)
    }

    @Test
    fun `all scope unions class and constructor for the same annotation`() {
        val targets = usages(markerFqn, AnnotationScope.ALL).map { it.target }.toSet()
        assertEquals(setOf(AnnotationUsageTarget.CLASS, AnnotationUsageTarget.CONSTRUCTOR), targets)
    }

    @Test
    fun `field scope returns the annotated field with its typed attribute`() {
        val result = usages(tagFqn, AnnotationScope.FIELD)
        assertEquals(1, result.size, "got $result")
        val usage = result.single()
        assertEquals(AnnotationUsageTarget.FIELD, usage.target)
        assertEquals("name", usage.field)
        assertEquals(tag("field"), usage.annotation)
    }

    @Test
    fun `method scope returns the annotated method with descriptor and typed attribute`() {
        val result = usages(tagFqn, AnnotationScope.METHOD)
        assertEquals(1, result.size, "got $result")
        val usage = result.single()
        assertEquals(AnnotationUsageTarget.METHOD, usage.target)
        assertEquals("value", usage.method)
        assertNotNull(usage.descriptor, "method usages carry the JVM descriptor")
        assertEquals(tag("method"), usage.annotation)
    }

    @Test
    fun `param scope captures method and constructor parameters with index and type`() {
        val result = usages(tagFqn, AnnotationScope.PARAM)
        assertEquals(2, result.size, "expected one method param and one ctor param, got $result")
        result.forEach { usage ->
            assertEquals(AnnotationUsageTarget.PARAMETER, usage.target)
            assertEquals(0, usage.parameterIndex)
            assertEquals("java.lang.String", usage.parameterType)
        }
        assertEquals(
            setOf("ctorParam", "methodParam"),
            result.map { it.annotation.parameters["value"]?.value }.toSet(),
        )
        // The constructor param is attached to `<init>`, the method param to the method.
        assertEquals("ctorParam", result.single { it.method == "<init>" }.annotation.parameters["value"]?.value)
    }

    @Test
    fun `meta-annotation match returns the synthesized instance with its declared attributes`() {
        // @Composed is meta-annotated with @Base(role="admin"); a @Composed method
        // is therefore found by querying the meta @Base, carrying role=admin — the
        // same mechanism that recovers the HTTP verb from @GetMapping's @RequestMapping.
        val result = usages(baseFqn, AnnotationScope.METHOD)
        assertEquals(1, result.size, "expected meta-expansion to match the @Composed method, got $result")
        val usage = result.single()
        assertEquals("composed", usage.method)
        assertEquals(baseFqn, usage.annotation.type)
        assertEquals(AnnotationValue(AnnotationValueKind.STRING, value = "admin"), usage.annotation.parameters["role"])
    }

    @Test
    fun `class scope does not return member usages`() {
        // @Tag is only on a field/method/params, never a class — class scope is empty.
        assertTrue(usages(tagFqn, AnnotationScope.CLASS).isEmpty())
    }
}

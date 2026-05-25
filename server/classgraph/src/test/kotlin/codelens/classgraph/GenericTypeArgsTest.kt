package codelens.classgraph

import codelens.core.model.DependencyType
import codelens.core.model.XrefKind
import codelens.core.model.XrefReference
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.assertTrue

/**
 * Exercises generic type-argument capture in `xref` / `deps` / class detail
 * against real compiled bytecode (the [GenericsSample] fixture).
 *
 * Hermetic: scans this test module's compiled output. Generic type arguments are
 * read from the bytecode type signature, so a type used only as a type argument
 * (`Map<String, GenericItem>`, `extends GenericBase<GenericItem>`, …) is now
 * counted as a reference to that argument, not just to its container.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenericTypeArgsTest {
    private lateinit var provider: ClassGraphProviderImpl

    private val sample = "codelens.classgraph.fixtures.GenericsSample"
    private val item = "codelens.classgraph.fixtures.GenericItem"

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

    private fun refsFrom(
        refs: List<XrefReference>,
        from: String,
    ) = refs.filter { it.fromFqn == from }

    @Test
    fun `xref counts a type used as a generic argument through every signature position`() {
        val refs = refsFrom(provider.getReferencesToType(item), sample)
        val kinds = refs.map { it.kind }.toSet()

        // `extends GenericBase<GenericItem>` and `implements GenericMarker<GenericItem>`.
        assertTrue(XrefKind.EXTENDS in kinds, "expected EXTENDS via GenericBase<GenericItem>; got $refs")
        assertTrue(XrefKind.IMPLEMENTS in kinds, "expected IMPLEMENTS via GenericMarker<GenericItem>; got $refs")

        // `Map<String, GenericItem> cache` and `Map<String, List<GenericItem>> nested`.
        assertTrue(
            refs.any { it.kind == XrefKind.FIELD && it.member == "cache" },
            "expected FIELD cache via Map<String, GenericItem>; got $refs",
        )
        assertTrue(
            refs.any { it.kind == XrefKind.FIELD && it.member == "nested" },
            "expected FIELD nested via the nested generic Map<String, List<GenericItem>>; got $refs",
        )

        // `List<GenericItem> getItems()` and `addAll(Collection<GenericItem>)`.
        assertTrue(
            refs.any { it.kind == XrefKind.RETURN && it.member == "getItems" },
            "expected RETURN getItems via List<GenericItem>; got $refs",
        )
        assertTrue(
            refs.any { it.kind == XrefKind.PARAM && it.member == "addAll" },
            "expected PARAM addAll via Collection<GenericItem>; got $refs",
        )
    }

    @Test
    fun `the container type is still counted alongside its arguments`() {
        // Capturing the argument must not displace the container reference.
        val mapRefs = refsFrom(provider.getReferencesToType("java.util.Map"), sample)
        assertTrue(
            mapRefs.any { it.kind == XrefKind.FIELD && it.member == "cache" },
            "expected FIELD cache to still reference java.util.Map; got $mapRefs",
        )

        val listRefs = refsFrom(provider.getReferencesToType("java.util.List"), sample)
        assertTrue(
            listRefs.any { it.kind == XrefKind.RETURN && it.member == "getItems" },
            "expected RETURN getItems to reference java.util.List; got $listRefs",
        )
        assertTrue(
            listRefs.any { it.kind == XrefKind.FIELD && it.member == "nested" },
            "expected the nested List<GenericItem> in field `nested` to reference java.util.List; got $listRefs",
        )
    }

    @Test
    fun `deps count a type reached only through generic arguments`() {
        val (outgoing, _) = provider.getDependencies(sample)
        assertTrue(
            outgoing.any { it.classFqn == item },
            "expected GenericsSample to depend on GenericItem (reached via type arguments); got $outgoing",
        )

        val (_, incoming) = provider.getDependencies(item)
        assertTrue(
            incoming.any { it.classFqn == sample && it.dependencyType == DependencyType.FIELD_TYPE },
            "expected GenericItem to have an incoming FIELD_TYPE dependency from GenericsSample; got $incoming",
        )
    }

    @Test
    fun `class detail renders the field type in generic form`() {
        val cache = provider.getClass(sample)?.fields?.firstOrNull { it.name == "cache" }
        val type = cache?.type ?: error("expected a `cache` field on $sample")
        assertTrue(type.startsWith("java.util.Map<"), "expected a generic Map type; got $type")
        assertTrue(type.contains(item), "expected the GenericItem argument in the type string; got $type")
    }
}

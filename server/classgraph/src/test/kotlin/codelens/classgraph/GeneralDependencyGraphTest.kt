package codelens.classgraph

import codelens.core.model.ClassInfo
import codelens.core.model.ClassName
import codelens.core.model.ClassSource
import codelens.core.model.FieldInfo
import codelens.core.model.Visibility
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the general, project-wide dependency graph and foundation
 * view added to [DependencyAnalyzer]. Uses a synthetic class map (no bytecode
 * needed — these views are derived from class signatures).
 */
class GeneralDependencyGraphTest {
    private fun classInfo(
        fqn: String,
        simpleName: String,
        packageName: String,
        source: ClassSource = ClassSource.PROJECT,
        superclass: String? = null,
        interfaces: List<String> = emptyList(),
        fields: List<FieldInfo> = emptyList(),
    ): ClassInfo =
        ClassInfo(
            name = ClassName(fqn = fqn, simpleName = simpleName, packageName = packageName),
            source = source,
            visibility = Visibility.PUBLIC,
            superclass = superclass,
            interfaces = interfaces,
            fields = fields,
        )

    private fun field(
        name: String,
        type: String,
    ) = FieldInfo(name = name, visibility = Visibility.PRIVATE, type = type)

    /** ServiceA is referenced (as a field) by two handlers. */
    private fun fanInClasses(): Map<String, ClassInfo> {
        val a = classInfo("p.ServiceA", "ServiceA", "p")
        val b = classInfo("p.HandlerB", "HandlerB", "p", fields = listOf(field("svc", "p.ServiceA")))
        val c = classInfo("p.HandlerC", "HandlerC", "p", fields = listOf(field("svc", "p.ServiceA")))
        return mapOf(a.name.fqn to a, b.name.fqn to b, c.name.fqn to c)
    }

    @Test
    fun `buildProjectGraph computes nodes, edges and degrees`() {
        val graph = DependencyAnalyzer(fanInClasses()).buildProjectGraph()

        assertEquals(3, graph.nodeCount)
        assertEquals(2, graph.edgeCount, "expected B->A and C->A")

        val a = graph.nodes.first { it.fqn == "p.ServiceA" }
        assertEquals(2, a.inDegree)
        assertEquals(0, a.outDegree)

        val b = graph.nodes.first { it.fqn == "p.HandlerB" }
        assertEquals(0, b.inDegree)
        assertEquals(1, b.outDegree)
    }

    @Test
    fun `foundationClasses filters by minDependents and ranks by in-degree`() {
        val foundation = DependencyAnalyzer(fanInClasses()).foundationClasses(minDependents = 2)

        assertEquals(1, foundation.size, "only ServiceA has >= 2 dependents")
        assertEquals("p.ServiceA", foundation[0].fqn)
        assertEquals(2, foundation[0].dependentCount)
        assertEquals(listOf("p.HandlerB", "p.HandlerC"), foundation[0].dependents)

        // Raising the threshold drops it.
        assertTrue(DependencyAnalyzer(fanInClasses()).foundationClasses(minDependents = 3).isEmpty())
    }

    @Test
    fun `library targets are excluded from the graph`() {
        val lib = classInfo("lib.Thing", "Thing", "lib", source = ClassSource.LIBRARY)
        val user = classInfo("p.User", "User", "p", fields = listOf(field("t", "lib.Thing")))
        val graph = DependencyAnalyzer(mapOf(lib.name.fqn to lib, user.name.fqn to user)).buildProjectGraph()

        assertEquals(1, graph.nodeCount, "only the project class is a node")
        assertEquals("p.User", graph.nodes.single().fqn)
        assertEquals(0, graph.edgeCount, "the library dependency is excluded")
    }

    @Test
    fun `projectGraphToDot renders nodes and edges deterministically`() {
        val dot = projectGraphToDot(DependencyAnalyzer(fanInClasses()).buildProjectGraph())
        assertTrue(dot.startsWith("digraph dependencies {"))
        assertTrue(dot.contains("\"p.HandlerB\" -> \"p.ServiceA\""), "expected B->A edge; got:\n$dot")
        assertTrue(dot.contains("\"p.HandlerC\" -> \"p.ServiceA\""), "expected C->A edge; got:\n$dot")
    }
}

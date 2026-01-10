package codelens.classgraph.ratpack

import codelens.classgraph.ClassGraphProvider
import codelens.core.model.*
import codelens.core.model.ratpack.*
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for DependencyAnalyzer.
 *
 * Tests the core graph algorithms:
 * - Cycle detection
 * - Tier grouping
 * - Foundation class identification
 * - Quick win identification
 */
class DependencyAnalyzerTest {

    private lateinit var mockProvider: ClassGraphProvider
    private lateinit var mockDetector: RatpackDetector
    private lateinit var mockComplexityCalculator: ComplexityCalculator
    private lateinit var analyzer: DependencyAnalyzer

    @BeforeEach
    fun setup() {
        mockProvider = mockk(relaxed = true)
        mockDetector = mockk(relaxed = true)
        mockComplexityCalculator = mockk(relaxed = true)
        analyzer = DependencyAnalyzer(
            mockProvider,
            ratpackDetectorOverride = mockDetector,
            complexityCalculatorOverride = mockComplexityCalculator
        )
    }

    // ========================================================================
    // Cycle Detection Tests
    // ========================================================================

    @Nested
    inner class CycleDetectionTests {

        @Test
        fun `should detect simple two-node cycle`() {
            // Setup: A -> B -> A
            setupHandlers(listOf("com.example.HandlerA", "com.example.HandlerB"))

            val handlerA = createHandlerClass("com.example.HandlerA", "HandlerA",
                fields = listOf(createField("serviceB", "com.example.HandlerB")))
            val handlerB = createHandlerClass("com.example.HandlerB", "HandlerB",
                fields = listOf(createField("serviceA", "com.example.HandlerA")))

            every { mockProvider.getClass("com.example.HandlerA") } returns handlerA
            every { mockProvider.getClass("com.example.HandlerB") } returns handlerB

            val analysis = analyzer.analyze()

            assertEquals(1, analysis.cycles.size, "Should detect one cycle")
            assertTrue(analysis.cycles[0].description.contains("HandlerA"))
            assertTrue(analysis.cycles[0].description.contains("HandlerB"))
        }

        @Test
        fun `should detect three-node cycle`() {
            // Setup: A -> B -> C -> A
            setupHandlers(listOf("com.example.A", "com.example.B", "com.example.C"))

            val handlerA = createHandlerClass("com.example.A", "A",
                fields = listOf(createField("depB", "com.example.B")))
            val handlerB = createHandlerClass("com.example.B", "B",
                fields = listOf(createField("depC", "com.example.C")))
            val handlerC = createHandlerClass("com.example.C", "C",
                fields = listOf(createField("depA", "com.example.A")))

            every { mockProvider.getClass("com.example.A") } returns handlerA
            every { mockProvider.getClass("com.example.B") } returns handlerB
            every { mockProvider.getClass("com.example.C") } returns handlerC

            val analysis = analyzer.analyze()

            assertEquals(1, analysis.cycles.size, "Should detect one cycle")
            val cycleClasses = analysis.cycles[0].classes
            assertEquals(4, cycleClasses.size, "Cycle should include all 3 nodes plus closing node")
        }

        @Test
        fun `should report no cycles for acyclic graph`() {
            // Setup: A -> B, A -> C (no cycles)
            setupHandlers(listOf("com.example.A", "com.example.B", "com.example.C"))

            val handlerA = createHandlerClass("com.example.A", "A",
                fields = listOf(
                    createField("depB", "com.example.B"),
                    createField("depC", "com.example.C")
                ))
            val handlerB = createHandlerClass("com.example.B", "B")
            val handlerC = createHandlerClass("com.example.C", "C")

            every { mockProvider.getClass("com.example.A") } returns handlerA
            every { mockProvider.getClass("com.example.B") } returns handlerB
            every { mockProvider.getClass("com.example.C") } returns handlerC

            val analysis = analyzer.analyze()

            assertEquals(0, analysis.cycles.size, "Should not detect any cycles")
            assertEquals(0, analysis.stats.cycleCount)
        }

        @Test
        fun `should not report duplicate cycles`() {
            // A -> B -> A should only be reported once, not twice
            setupHandlers(listOf("com.example.A", "com.example.B"))

            val handlerA = createHandlerClass("com.example.A", "A",
                fields = listOf(createField("depB", "com.example.B")))
            val handlerB = createHandlerClass("com.example.B", "B",
                fields = listOf(createField("depA", "com.example.A")))

            every { mockProvider.getClass("com.example.A") } returns handlerA
            every { mockProvider.getClass("com.example.B") } returns handlerB

            val analysis = analyzer.analyze()

            assertEquals(1, analysis.cycles.size, "Should report cycle only once")
        }
    }

    // ========================================================================
    // Tier Grouping Tests
    // ========================================================================

    @Nested
    inner class TierGroupingTests {

        @Test
        fun `should place handlers with no dependencies in tier 0`() {
            setupHandlers(listOf("com.example.IndependentHandler"))

            val handler = createHandlerClass("com.example.IndependentHandler", "IndependentHandler")
            every { mockProvider.getClass("com.example.IndependentHandler") } returns handler

            val analysis = analyzer.analyze()

            assertEquals(1, analysis.handlerTiers.size)
            assertEquals(0, analysis.handlerTiers[0].tier)
            assertTrue(analysis.handlerTiers[0].handlers.contains("IndependentHandler"))
        }

        @Test
        fun `should place handlers depending on tier 0 in tier 1`() {
            setupHandlers(listOf("com.example.Tier0Handler", "com.example.Tier1Handler"))

            val tier0 = createHandlerClass("com.example.Tier0Handler", "Tier0Handler")
            val tier1 = createHandlerClass("com.example.Tier1Handler", "Tier1Handler",
                fields = listOf(createField("dep", "com.example.Tier0Handler")))

            every { mockProvider.getClass("com.example.Tier0Handler") } returns tier0
            every { mockProvider.getClass("com.example.Tier1Handler") } returns tier1

            val analysis = analyzer.analyze()

            val tier0Group = analysis.handlerTiers.find { it.tier == 0 }
            val tier1Group = analysis.handlerTiers.find { it.tier == 1 }

            assertTrue(tier0Group?.handlers?.contains("Tier0Handler") == true)
            assertTrue(tier1Group?.handlers?.contains("Tier1Handler") == true)
        }

        @Test
        fun `should correctly assign multi-level tiers`() {
            // Tier0 (no deps) <- Tier1 <- Tier2
            setupHandlers(listOf(
                "com.example.Tier0",
                "com.example.Tier1",
                "com.example.Tier2"
            ))

            val tier0 = createHandlerClass("com.example.Tier0", "Tier0")
            val tier1 = createHandlerClass("com.example.Tier1", "Tier1",
                fields = listOf(createField("dep", "com.example.Tier0")))
            val tier2 = createHandlerClass("com.example.Tier2", "Tier2",
                fields = listOf(createField("dep", "com.example.Tier1")))

            every { mockProvider.getClass("com.example.Tier0") } returns tier0
            every { mockProvider.getClass("com.example.Tier1") } returns tier1
            every { mockProvider.getClass("com.example.Tier2") } returns tier2

            val analysis = analyzer.analyze()

            assertEquals(3, analysis.handlerTiers.size)
            assertEquals(0, analysis.handlerTiers[0].tier)
            assertEquals(1, analysis.handlerTiers[1].tier)
            assertEquals(2, analysis.handlerTiers[2].tier)
        }

        @Test
        fun `should not count non-handler dependencies for tier calculation`() {
            // Handler depends on a service, not another handler
            setupHandlers(listOf("com.example.MyHandler"))

            val service = createServiceClass("com.example.MyService", "MyService")
            val handler = createHandlerClass("com.example.MyHandler", "MyHandler",
                fields = listOf(createField("service", "com.example.MyService")))

            every { mockProvider.getClass("com.example.MyHandler") } returns handler
            every { mockProvider.getClass("com.example.MyService") } returns service

            val analysis = analyzer.analyze()

            // Handler should be tier 0 since it has no *handler* dependencies
            val tier0 = analysis.handlerTiers.find { it.tier == 0 }
            assertTrue(tier0?.handlers?.contains("MyHandler") == true,
                "Handler with only service dependencies should be tier 0")
        }
    }

    // ========================================================================
    // Foundation Class Tests
    // ========================================================================

    @Nested
    inner class FoundationClassTests {

        @Test
        fun `should identify class with many handler dependents as foundation`() {
            // Service used by 3+ handlers is a foundation class
            setupHandlers(listOf(
                "com.example.Handler1",
                "com.example.Handler2",
                "com.example.Handler3"
            ))

            val sharedService = createServiceClass("com.example.SharedService", "SharedService")
            val handler1 = createHandlerClass("com.example.Handler1", "Handler1",
                fields = listOf(createField("svc", "com.example.SharedService")))
            val handler2 = createHandlerClass("com.example.Handler2", "Handler2",
                fields = listOf(createField("svc", "com.example.SharedService")))
            val handler3 = createHandlerClass("com.example.Handler3", "Handler3",
                fields = listOf(createField("svc", "com.example.SharedService")))

            every { mockProvider.getClass("com.example.SharedService") } returns sharedService
            every { mockProvider.getClass("com.example.Handler1") } returns handler1
            every { mockProvider.getClass("com.example.Handler2") } returns handler2
            every { mockProvider.getClass("com.example.Handler3") } returns handler3

            val foundationClasses = analyzer.getFoundationClasses()

            assertEquals(1, foundationClasses.size)
            assertEquals("SharedService", foundationClasses[0].simpleName)
            assertEquals(3, foundationClasses[0].dependentCount)
        }

        @Test
        fun `should not include class with few dependents as foundation`() {
            // Service used by only 1 handler is NOT a foundation class
            setupHandlers(listOf("com.example.Handler"))

            val service = createServiceClass("com.example.SomeService", "SomeService")
            val handler = createHandlerClass("com.example.Handler", "Handler",
                fields = listOf(createField("svc", "com.example.SomeService")))

            every { mockProvider.getClass("com.example.SomeService") } returns service
            every { mockProvider.getClass("com.example.Handler") } returns handler

            val foundationClasses = analyzer.getFoundationClasses()

            assertEquals(0, foundationClasses.size, "Service with 1 dependent should not be foundation")
        }

        @Test
        fun `should sort foundation classes by dependent count descending`() {
            setupHandlers(listOf(
                "com.example.H1", "com.example.H2", "com.example.H3",
                "com.example.H4", "com.example.H5"
            ))

            val svc3 = createServiceClass("com.example.Svc3Deps", "Svc3Deps")
            val svc5 = createServiceClass("com.example.Svc5Deps", "Svc5Deps")

            // 5 handlers depend on Svc5Deps, 3 on Svc3Deps
            val h1 = createHandlerClass("com.example.H1", "H1",
                fields = listOf(createField("s1", "com.example.Svc5Deps")))
            val h2 = createHandlerClass("com.example.H2", "H2",
                fields = listOf(createField("s1", "com.example.Svc5Deps")))
            val h3 = createHandlerClass("com.example.H3", "H3",
                fields = listOf(
                    createField("s1", "com.example.Svc5Deps"),
                    createField("s2", "com.example.Svc3Deps")
                ))
            val h4 = createHandlerClass("com.example.H4", "H4",
                fields = listOf(
                    createField("s1", "com.example.Svc5Deps"),
                    createField("s2", "com.example.Svc3Deps")
                ))
            val h5 = createHandlerClass("com.example.H5", "H5",
                fields = listOf(
                    createField("s1", "com.example.Svc5Deps"),
                    createField("s2", "com.example.Svc3Deps")
                ))

            every { mockProvider.getClass("com.example.Svc3Deps") } returns svc3
            every { mockProvider.getClass("com.example.Svc5Deps") } returns svc5
            every { mockProvider.getClass("com.example.H1") } returns h1
            every { mockProvider.getClass("com.example.H2") } returns h2
            every { mockProvider.getClass("com.example.H3") } returns h3
            every { mockProvider.getClass("com.example.H4") } returns h4
            every { mockProvider.getClass("com.example.H5") } returns h5

            val foundationClasses = analyzer.getFoundationClasses()

            assertEquals(2, foundationClasses.size)
            assertEquals("Svc5Deps", foundationClasses[0].simpleName, "Svc5Deps should be first (5 dependents)")
            assertEquals("Svc3Deps", foundationClasses[1].simpleName, "Svc3Deps should be second (3 dependents)")
        }

        @Test
        fun `should correctly classify repository class type`() {
            setupHandlers(listOf("com.example.H1", "com.example.H2", "com.example.H3"))

            val repo = createClass("com.example.UserRepository", "UserRepository",
                source = ClassSource.PROJECT)
            val h1 = createHandlerClass("com.example.H1", "H1",
                fields = listOf(createField("repo", "com.example.UserRepository")))
            val h2 = createHandlerClass("com.example.H2", "H2",
                fields = listOf(createField("repo", "com.example.UserRepository")))
            val h3 = createHandlerClass("com.example.H3", "H3",
                fields = listOf(createField("repo", "com.example.UserRepository")))

            every { mockProvider.getClass("com.example.UserRepository") } returns repo
            every { mockProvider.getClass("com.example.H1") } returns h1
            every { mockProvider.getClass("com.example.H2") } returns h2
            every { mockProvider.getClass("com.example.H3") } returns h3

            val foundationClasses = analyzer.getFoundationClasses()

            assertEquals(1, foundationClasses.size)
            assertEquals(ClassType.REPOSITORY, foundationClasses[0].type)
        }
    }

    // ========================================================================
    // Quick Wins Tests
    // ========================================================================

    @Nested
    inner class QuickWinsTests {

        @Test
        fun `should identify handler with zero dependencies as quick win`() {
            setupHandlers(listOf("com.example.SimpleHandler"), ComplexityTier.LOW)

            val handler = createHandlerClass("com.example.SimpleHandler", "SimpleHandler")
            every { mockProvider.getClass("com.example.SimpleHandler") } returns handler

            val quickWins = analyzer.getQuickWins()

            assertEquals(1, quickWins.size)
            assertEquals("SimpleHandler", quickWins[0].simpleName)
            assertEquals(0, quickWins[0].dependencyCount)
        }

        @Test
        fun `should identify handler with one dependency as quick win`() {
            setupHandlers(listOf("com.example.Handler"), ComplexityTier.LOW)

            val service = createServiceClass("com.example.Service", "Service")
            val handler = createHandlerClass("com.example.Handler", "Handler",
                fields = listOf(createField("svc", "com.example.Service")))

            every { mockProvider.getClass("com.example.Service") } returns service
            every { mockProvider.getClass("com.example.Handler") } returns handler

            val quickWins = analyzer.getQuickWins()

            assertEquals(1, quickWins.size)
            assertEquals(1, quickWins[0].dependencyCount)
        }

        @Test
        fun `should not include high complexity handlers as quick wins`() {
            setupHandlers(listOf("com.example.ComplexHandler"), ComplexityTier.HIGH)

            val handler = createHandlerClass("com.example.ComplexHandler", "ComplexHandler")
            every { mockProvider.getClass("com.example.ComplexHandler") } returns handler

            val quickWins = analyzer.getQuickWins()

            assertEquals(0, quickWins.size, "HIGH complexity handler should not be a quick win")
        }

        @Test
        fun `should not include handler with many dependencies as quick win`() {
            setupHandlers(listOf("com.example.DependentHandler"), ComplexityTier.LOW)

            val svc1 = createServiceClass("com.example.Svc1", "Svc1")
            val svc2 = createServiceClass("com.example.Svc2", "Svc2")
            val handler = createHandlerClass("com.example.DependentHandler", "DependentHandler",
                fields = listOf(
                    createField("s1", "com.example.Svc1"),
                    createField("s2", "com.example.Svc2")
                ))

            every { mockProvider.getClass("com.example.Svc1") } returns svc1
            every { mockProvider.getClass("com.example.Svc2") } returns svc2
            every { mockProvider.getClass("com.example.DependentHandler") } returns handler

            val quickWins = analyzer.getQuickWins()

            assertEquals(0, quickWins.size, "Handler with 2+ dependencies should not be a quick win")
        }

        @Test
        fun `should sort quick wins by dependency count then complexity`() {
            setupHandlers(listOf(
                "com.example.ZeroDeps",
                "com.example.OneDep",
                "com.example.ZeroDepsMedium"
            ), listOf(ComplexityTier.LOW, ComplexityTier.LOW, ComplexityTier.MEDIUM))

            val service = createServiceClass("com.example.Service", "Service")
            val zeroDeps = createHandlerClass("com.example.ZeroDeps", "ZeroDeps")
            val oneDep = createHandlerClass("com.example.OneDep", "OneDep",
                fields = listOf(createField("svc", "com.example.Service")))
            val zeroDepsMedium = createHandlerClass("com.example.ZeroDepsMedium", "ZeroDepsMedium")

            every { mockProvider.getClass("com.example.Service") } returns service
            every { mockProvider.getClass("com.example.ZeroDeps") } returns zeroDeps
            every { mockProvider.getClass("com.example.OneDep") } returns oneDep
            every { mockProvider.getClass("com.example.ZeroDepsMedium") } returns zeroDepsMedium

            val quickWins = analyzer.getQuickWins()

            assertEquals(3, quickWins.size)
            // Zero deps with LOW should come first
            assertEquals("ZeroDeps", quickWins[0].simpleName)
            // Zero deps with MEDIUM should come second
            assertEquals("ZeroDepsMedium", quickWins[1].simpleName)
            // One dep should come last
            assertEquals("OneDep", quickWins[2].simpleName)
        }
    }

    // ========================================================================
    // Statistics Tests
    // ========================================================================

    @Nested
    inner class StatisticsTests {

        @Test
        fun `should calculate correct statistics`() {
            setupHandlers(listOf("com.example.H1", "com.example.H2", "com.example.H3"))

            val svc1 = createServiceClass("com.example.S1", "S1")
            val svc2 = createServiceClass("com.example.S2", "S2")

            val h1 = createHandlerClass("com.example.H1", "H1") // 0 deps
            val h2 = createHandlerClass("com.example.H2", "H2",
                fields = listOf(createField("s1", "com.example.S1"))) // 1 dep
            val h3 = createHandlerClass("com.example.H3", "H3",
                fields = listOf(
                    createField("s1", "com.example.S1"),
                    createField("s2", "com.example.S2")
                )) // 2 deps

            every { mockProvider.getClass("com.example.S1") } returns svc1
            every { mockProvider.getClass("com.example.S2") } returns svc2
            every { mockProvider.getClass("com.example.H1") } returns h1
            every { mockProvider.getClass("com.example.H2") } returns h2
            every { mockProvider.getClass("com.example.H3") } returns h3

            val analysis = analyzer.analyze()

            assertEquals(3, analysis.stats.totalHandlers)
            assertEquals(3, analysis.stats.totalDependencies) // 0 + 1 + 2
            assertEquals(2, analysis.stats.maxDependencies)
            assertEquals(1.0, analysis.stats.avgDependenciesPerHandler) // 3/3
        }
    }

    // ========================================================================
    // DOT Format Tests
    // ========================================================================

    @Nested
    inner class DotFormatTests {

        @Test
        fun `should generate valid DOT format`() {
            setupHandlers(listOf("com.example.Handler"))

            val handler = createHandlerClass("com.example.Handler", "Handler")
            every { mockProvider.getClass("com.example.Handler") } returns handler

            val dotFormat = analyzer.toDotFormat()

            assertTrue(dotFormat.startsWith("digraph Dependencies {"))
            assertTrue(dotFormat.contains("com.example.Handler"))
            assertTrue(dotFormat.endsWith("}\n"))
        }

        @Test
        fun `should include edges in DOT format`() {
            setupHandlers(listOf("com.example.Handler"))

            val service = createServiceClass("com.example.Service", "Service")
            val handler = createHandlerClass("com.example.Handler", "Handler",
                fields = listOf(createField("svc", "com.example.Service")))

            every { mockProvider.getClass("com.example.Service") } returns service
            every { mockProvider.getClass("com.example.Handler") } returns handler

            val dotFormat = analyzer.toDotFormat()

            assertTrue(dotFormat.contains("\"com.example.Handler\" -> \"com.example.Service\""))
        }

        @Test
        fun `should escape special characters in DOT format`() {
            setupHandlers(listOf("com.example.Handler"))

            // Create a handler with a label containing special characters (quotes)
            val handler = createHandlerClass("com.example.Handler", "Handler\"WithQuotes")
            every { mockProvider.getClass("com.example.Handler") } returns handler

            val dotFormat = analyzer.toDotFormat()

            // Should contain escaped quotes in the label
            assertTrue(dotFormat.contains("Handler\\\"WithQuotes"))
        }
    }

    // ========================================================================
    // Edge Case Tests
    // ========================================================================

    @Nested
    inner class EdgeCaseTests {

        @Test
        fun `should handle empty handler list gracefully`() {
            every { mockDetector.findAllHandlers(any()) } returns emptyList()

            val analysis = analyzer.analyze()

            assertEquals(0, analysis.stats.totalHandlers)
            assertEquals(0, analysis.stats.totalDependencies)
            assertEquals(0.0, analysis.stats.avgDependenciesPerHandler)
            assertTrue(analysis.foundationClasses.isEmpty())
            assertTrue(analysis.quickWins.isEmpty())
            assertTrue(analysis.cycles.isEmpty())
            assertTrue(analysis.handlerTiers.isEmpty())
        }

        @Test
        fun `should handle generic type dependencies`() {
            // Handler depends on List<Service> - should extract base type
            setupHandlers(listOf("com.example.Handler"), ComplexityTier.LOW)

            val service = createServiceClass("com.example.Service", "Service")
            val handler = createHandlerClass("com.example.Handler", "Handler",
                fields = listOf(createField("services", "java.util.List<com.example.Service>")))

            every { mockProvider.getClass("com.example.Service") } returns service
            every { mockProvider.getClass("com.example.Handler") } returns handler
            // The generic type should not be found directly
            every { mockProvider.getClass("java.util.List<com.example.Service>") } returns null

            val analysis = analyzer.analyze()

            // Handler should still work without the generic dependency being resolved
            assertEquals(1, analysis.stats.totalHandlers)
        }

        @Test
        fun `should handle single handler with no dependencies`() {
            setupHandlers(listOf("com.example.SingleHandler"), ComplexityTier.LOW)

            val handler = createHandlerClass("com.example.SingleHandler", "SingleHandler")
            every { mockProvider.getClass("com.example.SingleHandler") } returns handler

            val analysis = analyzer.analyze()

            assertEquals(1, analysis.stats.totalHandlers)
            assertEquals(0, analysis.stats.totalDependencies)
            assertEquals(1, analysis.quickWins.size)
            assertEquals(1, analysis.handlerTiers.size)
            assertEquals(0, analysis.handlerTiers[0].tier)
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private fun setupHandlers(
        fqns: List<String>,
        complexity: ComplexityTier = ComplexityTier.LOW
    ) {
        setupHandlers(fqns, fqns.map { complexity })
    }

    private fun setupHandlers(
        fqns: List<String>,
        complexities: List<ComplexityTier>
    ) {
        val summaries = fqns.mapIndexed { index, fqn ->
            val simpleName = fqn.substringAfterLast(".")
            val packageName = fqn.substringBeforeLast(".")
            HandlerSummary(
                fqn = fqn,
                simpleName = simpleName,
                packageName = packageName,
                handlerType = HandlerType.HANDLER,
                source = ClassSource.PROJECT,
                complexityScore = index * 10,
                complexityTier = complexities.getOrElse(index) { ComplexityTier.LOW },
                promiseOperationCount = 0,
                usesBlocking = false,
                hasInjectAnnotation = true
            )
        }

        // Mock the RatpackDetector
        every { mockDetector.findAllHandlers(any()) } returns summaries

        // Mock ComplexityCalculator
        fqns.forEachIndexed { index, fqn ->
            every { mockComplexityCalculator.calculate(fqn) } returns ComplexityResult(
                classFqn = fqn,
                score = index * 10,
                tier = complexities.getOrElse(index) { ComplexityTier.LOW },
                estimatedHours = 1.0,
                factors = emptyList(),
                migrationNotes = emptyList(),
                migrationPriority = index + 1,
                blockedBy = emptyList()
            )
        }
    }

    private fun createField(name: String, type: String, isFinal: Boolean = true): FieldInfo {
        return FieldInfo(
            name = name,
            type = type,
            visibility = Visibility.PRIVATE,
            isFinal = isFinal,
            isStatic = false
        )
    }

    private fun createHandlerClass(
        fqn: String,
        simpleName: String,
        fields: List<FieldInfo> = emptyList()
    ): ClassInfo {
        return createClass(
            fqn = fqn,
            simpleName = simpleName,
            interfaces = listOf("ratpack.handling.Handler"),
            fields = fields,
            source = ClassSource.PROJECT
        )
    }

    private fun createServiceClass(fqn: String, simpleName: String): ClassInfo {
        return createClass(
            fqn = fqn,
            simpleName = simpleName,
            source = ClassSource.PROJECT
        )
    }

    private fun createClass(
        fqn: String,
        simpleName: String,
        interfaces: List<String> = emptyList(),
        fields: List<FieldInfo> = emptyList(),
        source: ClassSource = ClassSource.PROJECT
    ): ClassInfo {
        return ClassInfo(
            name = ClassName(
                fqn = fqn,
                simpleName = simpleName,
                packageName = fqn.substringBeforeLast(".")
            ),
            source = source,
            visibility = Visibility.PUBLIC,
            isInterface = false,
            isAbstract = false,
            superclass = "java.lang.Object",
            interfaces = interfaces,
            fields = fields,
            methods = emptyList()
        )
    }
}

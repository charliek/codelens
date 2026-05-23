package codelens.server.services

import codelens.classgraph.ClassGraphProvider
import codelens.classgraph.ratpack.AntiPatternDetector
import codelens.classgraph.ratpack.ComplexityCalculator
import codelens.classgraph.ratpack.DependencyAnalyzer
import codelens.classgraph.ratpack.GuiceModuleDetector
import codelens.classgraph.ratpack.IntegrationDetector
import codelens.classgraph.ratpack.PromiseDetector
import codelens.classgraph.ratpack.RatpackDetector
import codelens.classgraph.ratpack.RouteAnalyzer
import codelens.core.model.ratpack.*
import org.slf4j.LoggerFactory

/**
 * Service for Ratpack-specific analysis.
 *
 * Provides:
 * - Handler discovery and analysis
 * - Promise usage detection
 * - Complexity scoring and migration planning
 * - Guice module analysis
 */
class RatpackAnalysisService(
    private val classGraphProvider: ClassGraphProvider,
) {
    private val logger = LoggerFactory.getLogger(RatpackAnalysisService::class.java)

    private val ratpackDetector by lazy { RatpackDetector(classGraphProvider) }
    private val promiseDetector by lazy { PromiseDetector(classGraphProvider) }
    private val complexityCalculator by lazy { ComplexityCalculator(classGraphProvider) }
    private val guiceModuleDetector by lazy { GuiceModuleDetector(classGraphProvider) }
    private val integrationDetector by lazy { IntegrationDetector(classGraphProvider) }
    private val antiPatternDetector by lazy { AntiPatternDetector(classGraphProvider) }
    private val routeAnalyzer by lazy { RouteAnalyzer(classGraphProvider) }
    private val dependencyAnalyzer by lazy { DependencyAnalyzer(classGraphProvider) }

    // =========================================================================
    // Handler Analysis
    // =========================================================================

    /**
     * List all Ratpack handlers.
     *
     * @param handlerType Filter by handler type (optional)
     * @param tier Filter by complexity tier (optional)
     * @param includeLibraries Include handlers from libraries
     * @return List of handler summaries
     */
    fun listHandlers(
        handlerType: HandlerType? = null,
        tier: ComplexityTier? = null,
        includeLibraries: Boolean = false,
    ): List<HandlerSummary> {
        val handlers = ratpackDetector.findAllHandlers(includeLibraries)

        return handlers.filter { handler ->
            (handlerType == null || handler.handlerType == handlerType) &&
                (tier == null || handler.complexityTier == tier)
        }
    }

    /**
     * Get detailed information about a handler.
     *
     * @param fqn Fully qualified class name
     * @return Handler info, or null if not found
     */
    fun getHandlerDetail(fqn: String): HandlerInfo? = ratpackDetector.getHandlerDetail(fqn)

    // =========================================================================
    // Promise Analysis
    // =========================================================================

    /**
     * Get project-wide Promise usage summary.
     *
     * @param includeLibraries Include library classes
     * @return Promise usage summary
     */
    fun getPromiseSummary(includeLibraries: Boolean = false): PromiseSummary = promiseDetector.getProjectSummary(includeLibraries)

    /**
     * Get Promise usage for a specific class.
     *
     * @param fqn Fully qualified class name
     * @return Promise usage info
     */
    fun getPromiseUsage(fqn: String): PromiseUsageInfo = promiseDetector.analyzeClass(fqn)

    /**
     * Search for classes with specific Promise usage patterns.
     *
     * @param usesBlocking Filter for Blocking usage
     * @param usesAsync Filter for async usage
     * @param usesFork Filter for fork usage
     * @param minOperations Minimum operation count
     * @return List of matching Promise usage info
     */
    fun searchPromiseUsage(
        usesBlocking: Boolean? = null,
        usesAsync: Boolean? = null,
        usesFork: Boolean? = null,
        minOperations: Int = 0,
    ): List<PromiseUsageInfo> =
        promiseDetector.search(
            usesBlocking = usesBlocking,
            usesAsync = usesAsync,
            usesFork = usesFork,
            minOperations = minOperations,
        )

    // =========================================================================
    // Complexity Analysis
    // =========================================================================

    /**
     * Get project-wide complexity summary.
     *
     * @return Complexity summary with tier breakdown and migration order
     */
    fun getComplexitySummary(): ComplexitySummary {
        val handlers = ratpackDetector.findAllHandlers(includeLibraries = false)
        return complexityCalculator.getProjectSummary(handlers)
    }

    /**
     * Get complexity score for a specific class.
     *
     * @param fqn Fully qualified class name
     * @return Complexity result
     */
    fun getComplexity(fqn: String): ComplexityResult = complexityCalculator.calculate(fqn)

    /**
     * Get suggested migration order.
     *
     * @return List of migration order items
     */
    fun getMigrationOrder(): List<MigrationOrderItem> {
        val summary = getComplexitySummary()
        return summary.migrationOrder
    }

    // =========================================================================
    // Guice Module Analysis
    // =========================================================================

    /**
     * List all Guice modules.
     *
     * @param includeLibraries Include modules from libraries
     * @return List of module summaries
     */
    fun listModules(includeLibraries: Boolean = false): List<GuiceModuleSummary> = guiceModuleDetector.findAllModules(includeLibraries)

    /**
     * Get detailed information about a Guice module.
     *
     * @param fqn Fully qualified class name
     * @return Module info, or null if not found
     */
    fun getModuleDetail(fqn: String): GuiceModuleInfo? = guiceModuleDetector.getModuleDetail(fqn)

    /**
     * Find all bindings for a specific type.
     *
     * @param typeFqn Fully qualified type name
     * @return List of (module FQN, binding) pairs
     */
    fun findBindingsForType(typeFqn: String): List<Pair<String, GuiceBinding>> = guiceModuleDetector.findBindingsForType(typeFqn)

    // =========================================================================
    // Integration Analysis
    // =========================================================================

    /**
     * Get project-wide integration summary.
     *
     * @param includeLibraries Include library classes
     * @return Integration summary
     */
    fun getIntegrationsSummary(includeLibraries: Boolean = false): ProjectIntegrationSummary =
        integrationDetector.getProjectSummary(includeLibraries)

    /**
     * Get integrations for a specific class.
     *
     * @param fqn Fully qualified class name
     * @return Class integrations, or null if class not found
     */
    fun getClassIntegrations(fqn: String): ClassIntegrations? = integrationDetector.analyzeClass(fqn)

    /**
     * Find classes by integration type.
     *
     * @param type Integration type
     * @param subType Optional subtype filter
     * @param includeLibraries Include library classes
     * @return List of class integrations
     */
    fun findIntegrationsByType(
        type: IntegrationType,
        subType: IntegrationSubType? = null,
        includeLibraries: Boolean = false,
    ): List<ClassIntegrations> = integrationDetector.findByType(type, subType, includeLibraries)

    // =========================================================================
    // Anti-Pattern Detection
    // =========================================================================

    /**
     * Get project-wide anti-pattern summary.
     *
     * @param severity Filter by severity level (optional)
     * @param type Filter by anti-pattern type (optional)
     * @param includeLibraries Include library classes
     * @return Anti-pattern summary
     */
    fun getAntiPatternSummary(
        severity: AntiPatternSeverity? = null,
        type: AntiPatternType? = null,
        includeLibraries: Boolean = false,
    ): AntiPatternSummary =
        antiPatternDetector.getProjectSummary(
            severityFilter = severity,
            typeFilter = type,
            includeLibraries = includeLibraries,
        )

    /**
     * Get anti-patterns for a specific class.
     *
     * @param fqn Fully qualified class name
     * @return List of anti-pattern instances
     */
    fun getClassAntiPatterns(fqn: String): List<AntiPatternInstance> = antiPatternDetector.analyzeClass(fqn)

    // =========================================================================
    // Route Analysis
    // =========================================================================

    /**
     * Get routing summary for the project.
     *
     * @param includeLibraries Include library classes
     * @return Routing summary
     */
    fun getRoutingSummary(includeLibraries: Boolean = false): RoutingSummary = routeAnalyzer.getRoutingSummary(includeLibraries)

    /**
     * Get route tree structure.
     *
     * @param includeLibraries Include library classes
     * @return Route tree root node
     */
    fun getRouteTree(includeLibraries: Boolean = false): RouteTreeNode {
        val summary = routeAnalyzer.getRoutingSummary(includeLibraries)
        return routeAnalyzer.buildRouteTree(summary.routes)
    }

    /**
     * Get Spring @RequestMapping equivalents for all routes.
     *
     * @param includeLibraries Include library classes
     * @return List of Spring mapping equivalents
     */
    fun getSpringMappings(includeLibraries: Boolean = false): SpringMappingsResponse {
        val summary = routeAnalyzer.getRoutingSummary(includeLibraries)
        val mappings = routeAnalyzer.generateSpringMappings(summary.routes)
        return SpringMappingsResponse(
            mappings = mappings,
            totalCount = mappings.size,
        )
    }

    // =========================================================================
    // Dependency Analysis
    // =========================================================================

    /**
     * Get full dependency analysis for all handlers.
     *
     * @return Dependency analysis with foundation classes, quick wins, cycles, and tiers
     */
    fun getDependencyAnalysis(): DependencyAnalysis = dependencyAnalyzer.analyze()

    /**
     * Get foundation classes (most depended-on classes).
     *
     * @return List of foundation classes sorted by dependent count
     */
    fun getFoundationClasses(): List<FoundationClass> = dependencyAnalyzer.getFoundationClasses()

    /**
     * Get quick win handlers (few dependencies, low complexity).
     *
     * @return List of quick win handlers
     */
    fun getQuickWins(): List<QuickWinHandler> = dependencyAnalyzer.getQuickWins()

    /**
     * Get full dependency graph for visualization.
     *
     * @return Dependency graph with nodes, edges, and cycles
     */
    fun getDependencyGraph(): DependencyGraph = dependencyAnalyzer.getDependencyGraph()

    /**
     * Get dependency graph in DOT format for Graphviz.
     *
     * @return DOT format string
     */
    fun getDependencyGraphDot(): String = dependencyAnalyzer.toDotFormat()
}

package codelens.server.services

import codelens.classgraph.ClassGraphProvider
import codelens.classgraph.ratpack.ComplexityCalculator
import codelens.classgraph.ratpack.GuiceModuleDetector
import codelens.classgraph.ratpack.PromiseDetector
import codelens.classgraph.ratpack.RatpackDetector
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
    private val classGraphProvider: ClassGraphProvider
) {
    private val logger = LoggerFactory.getLogger(RatpackAnalysisService::class.java)

    private val ratpackDetector by lazy { RatpackDetector(classGraphProvider) }
    private val promiseDetector by lazy { PromiseDetector(classGraphProvider) }
    private val complexityCalculator by lazy { ComplexityCalculator(classGraphProvider) }
    private val guiceModuleDetector by lazy { GuiceModuleDetector(classGraphProvider) }

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
        includeLibraries: Boolean = false
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
    fun getHandlerDetail(fqn: String): HandlerInfo? {
        return ratpackDetector.getHandlerDetail(fqn)
    }

    // =========================================================================
    // Promise Analysis
    // =========================================================================

    /**
     * Get project-wide Promise usage summary.
     *
     * @param includeLibraries Include library classes
     * @return Promise usage summary
     */
    fun getPromiseSummary(includeLibraries: Boolean = false): PromiseSummary {
        return promiseDetector.getProjectSummary(includeLibraries)
    }

    /**
     * Get Promise usage for a specific class.
     *
     * @param fqn Fully qualified class name
     * @return Promise usage info
     */
    fun getPromiseUsage(fqn: String): PromiseUsageInfo {
        return promiseDetector.analyzeClass(fqn)
    }

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
        minOperations: Int = 0
    ): List<PromiseUsageInfo> {
        return promiseDetector.search(
            usesBlocking = usesBlocking,
            usesAsync = usesAsync,
            usesFork = usesFork,
            minOperations = minOperations
        )
    }

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
    fun getComplexity(fqn: String): ComplexityResult {
        return complexityCalculator.calculate(fqn)
    }

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
    fun listModules(includeLibraries: Boolean = false): List<GuiceModuleSummary> {
        return guiceModuleDetector.findAllModules(includeLibraries)
    }

    /**
     * Get detailed information about a Guice module.
     *
     * @param fqn Fully qualified class name
     * @return Module info, or null if not found
     */
    fun getModuleDetail(fqn: String): GuiceModuleInfo? {
        return guiceModuleDetector.getModuleDetail(fqn)
    }

    /**
     * Find all bindings for a specific type.
     *
     * @param typeFqn Fully qualified type name
     * @return List of (module FQN, binding) pairs
     */
    fun findBindingsForType(typeFqn: String): List<Pair<String, GuiceBinding>> {
        return guiceModuleDetector.findBindingsForType(typeFqn)
    }
}

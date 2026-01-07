package codelens.classgraph.ratpack

import codelens.classgraph.ClassGraphProvider
import codelens.core.model.ClassInfo
import codelens.core.model.ClassSource
import codelens.core.model.ratpack.*
import org.slf4j.LoggerFactory
import kotlin.math.ceil

/**
 * Calculates migration complexity scores for Ratpack components.
 *
 * Complexity scoring factors:
 * - Promise chain depth and operations
 * - Blocking.get() usage
 * - Guice dependency injection
 * - Handler type complexity
 * - Method count and size indicators
 *
 * Score ranges:
 * - 0-25: LOW (simple migration)
 * - 26-50: MEDIUM (moderate effort)
 * - 51-75: HIGH (complex migration)
 * - 76-100: CRITICAL (significant effort)
 */
class ComplexityCalculator(
    private val classGraphProvider: ClassGraphProvider
) {
    private val logger = LoggerFactory.getLogger(ComplexityCalculator::class.java)

    companion object {
        // Complexity weights
        private const val WEIGHT_BLOCKING = 15
        private const val WEIGHT_PROMISE_ASYNC = 10
        private const val WEIGHT_EXECUTION_FORK = 12
        private const val WEIGHT_PARALLEL_BATCH = 20
        private const val WEIGHT_PROMISE_CHAIN_PER_DEPTH = 3
        private const val WEIGHT_PROMISE_OP_PER_COUNT = 2
        private const val WEIGHT_GUICE_DEPENDENCY_PER = 3
        private const val WEIGHT_METHOD_COUNT_PER = 1
        private const val WEIGHT_GROOVY_HANDLER = 8
        private const val WEIGHT_CHAIN_ACTION = 5

        // Score thresholds
        private const val THRESHOLD_LOW = 25
        private const val THRESHOLD_MEDIUM = 50
        private const val THRESHOLD_HIGH = 75

        // Hours per complexity point (for estimation)
        private const val HOURS_PER_POINT = 0.1
        private const val BASE_HOURS = 0.5
    }

    /**
     * Calculate complexity score for a class.
     *
     * @param fqn Fully qualified class name
     * @return Complexity result
     */
    fun calculate(fqn: String): ComplexityResult {
        val classInfo = classGraphProvider.getClass(fqn)
            ?: return emptyComplexityResult(fqn)

        val factors = mutableListOf<ComplexityFactor>()
        val migrationNotes = mutableListOf<String>()
        var totalScore = 0

        // Factor 1: Promise usage
        val promiseAnalysis = PromiseDetector(classGraphProvider).analyzeClass(fqn)
        val (promiseScore, promiseFactors, promiseNotes) = analyzePromiseComplexity(promiseAnalysis)
        totalScore += promiseScore
        factors.addAll(promiseFactors)
        migrationNotes.addAll(promiseNotes)

        // Factor 2: Handler type
        val (handlerScore, handlerFactor, handlerNote) = analyzeHandlerType(classInfo)
        totalScore += handlerScore
        handlerFactor?.let { factors.add(it) }
        handlerNote?.let { migrationNotes.add(it) }

        // Factor 3: Guice dependencies
        val (guiceScore, guiceFactor, guiceNotes) = analyzeGuiceDependencies(classInfo)
        totalScore += guiceScore
        guiceFactor?.let { factors.add(it) }
        migrationNotes.addAll(guiceNotes)

        // Factor 4: Method count (proxy for class size)
        val (methodScore, methodFactor) = analyzeMethodCount(classInfo)
        totalScore += methodScore
        methodFactor?.let { factors.add(it) }

        // Cap at 100
        totalScore = totalScore.coerceAtMost(100)

        val tier = when {
            totalScore <= THRESHOLD_LOW -> ComplexityTier.LOW
            totalScore <= THRESHOLD_MEDIUM -> ComplexityTier.MEDIUM
            totalScore <= THRESHOLD_HIGH -> ComplexityTier.HIGH
            else -> ComplexityTier.CRITICAL
        }

        val estimatedHours = BASE_HOURS + (totalScore * HOURS_PER_POINT)

        return ComplexityResult(
            classFqn = fqn,
            score = totalScore,
            tier = tier,
            estimatedHours = ceil(estimatedHours * 10) / 10, // Round to 1 decimal
            factors = factors,
            migrationNotes = migrationNotes,
            migrationPriority = calculatePriority(totalScore, promiseAnalysis),
            blockedBy = findBlockingDependencies(classInfo)
        )
    }

    /**
     * Get project-wide complexity summary for all handlers.
     *
     * @param handlers List of handler summaries
     * @return Project complexity summary
     */
    fun getProjectSummary(handlers: List<HandlerSummary>): ComplexitySummary {
        val tierBreakdown = mutableMapOf<ComplexityTier, Int>()
        var totalHours = 0.0
        var totalScore = 0

        // Store items with their scores to avoid recalculating during sort
        data class OrderItemWithScore(val item: MigrationOrderItem, val score: Int)
        val migrationOrderWithScores = mutableListOf<OrderItemWithScore>()

        for (handler in handlers) {
            val complexity = calculate(handler.fqn)

            tierBreakdown[complexity.tier] = (tierBreakdown[complexity.tier] ?: 0) + 1
            totalHours += complexity.estimatedHours
            totalScore += complexity.score

            migrationOrderWithScores.add(
                OrderItemWithScore(
                    item = MigrationOrderItem(
                        classFqn = handler.fqn,
                        simpleName = handler.simpleName,
                        tier = complexity.tier,
                        estimatedHours = complexity.estimatedHours,
                        order = 0, // Will be assigned below
                        reason = generateMigrationReason(complexity)
                    ),
                    score = complexity.score
                )
            )
        }

        // Sort migration order: LOW first (quick wins), then by score (using pre-calculated values)
        val sortedOrder = migrationOrderWithScores
            .sortedWith(
                compareBy(
                    { it.item.tier.ordinal },
                    { it.score }
                )
            )
            .mapIndexed { index, itemWithScore ->
                itemWithScore.item.copy(order = index + 1)
            }

        return ComplexitySummary(
            totalHandlers = handlers.size,
            tierBreakdown = tierBreakdown,
            totalEstimatedHours = ceil(totalHours * 10) / 10,
            averageScore = if (handlers.isNotEmpty()) totalScore.toDouble() / handlers.size else 0.0,
            migrationOrder = sortedOrder
        )
    }

    /**
     * Analyze Promise-related complexity.
     */
    private fun analyzePromiseComplexity(
        promiseAnalysis: PromiseUsageInfo
    ): Triple<Int, List<ComplexityFactor>, List<String>> {
        val factors = mutableListOf<ComplexityFactor>()
        val notes = mutableListOf<String>()
        var score = 0

        // Blocking usage
        if (promiseAnalysis.usesBlocking) {
            score += WEIGHT_BLOCKING
            factors.add(
                ComplexityFactor(
                    name = "Blocking Usage",
                    description = "Uses Blocking.get() which needs careful migration",
                    points = WEIGHT_BLOCKING,
                    maxPoints = WEIGHT_BLOCKING,
                    details = "Blocking operations need conversion to coroutines or reactive patterns"
                )
            )
            notes.add("Contains Blocking.get() - requires conversion to non-blocking pattern")
        }

        // Async Promise
        if (promiseAnalysis.usesAsync) {
            score += WEIGHT_PROMISE_ASYNC
            factors.add(
                ComplexityFactor(
                    name = "Async Promise",
                    description = "Uses Promise.async() for async operations",
                    points = WEIGHT_PROMISE_ASYNC,
                    maxPoints = WEIGHT_PROMISE_ASYNC,
                    details = "Promise.async patterns need conversion to suspend functions"
                )
            )
            notes.add("Uses Promise.async() - convert to suspend function or Flow")
        }

        // Execution fork
        if (promiseAnalysis.usesFork) {
            score += WEIGHT_EXECUTION_FORK
            factors.add(
                ComplexityFactor(
                    name = "Execution Fork",
                    description = "Uses Execution.fork() for parallel work",
                    points = WEIGHT_EXECUTION_FORK,
                    maxPoints = WEIGHT_EXECUTION_FORK,
                    details = "Fork patterns need conversion to coroutine scope"
                )
            )
            notes.add("Uses Execution.fork() - migrate to coroutine scope or async{}")
        }

        // Parallel batch
        if (promiseAnalysis.usesParallelBatch) {
            score += WEIGHT_PARALLEL_BATCH
            factors.add(
                ComplexityFactor(
                    name = "Parallel Batch",
                    description = "Uses ParallelBatch for concurrent operations",
                    points = WEIGHT_PARALLEL_BATCH,
                    maxPoints = WEIGHT_PARALLEL_BATCH,
                    details = "ParallelBatch is complex to migrate - use async/awaitAll"
                )
            )
            notes.add("Uses ParallelBatch - requires async/awaitAll pattern")
        }

        // Chain depth
        if (promiseAnalysis.maxChainDepth > 1) {
            val chainPoints = (promiseAnalysis.maxChainDepth * WEIGHT_PROMISE_CHAIN_PER_DEPTH)
                .coerceAtMost(15)
            score += chainPoints
            factors.add(
                ComplexityFactor(
                    name = "Promise Chain Depth",
                    description = "Deep Promise chains are harder to migrate",
                    points = chainPoints,
                    maxPoints = 15,
                    details = "Chain depth: ${promiseAnalysis.maxChainDepth} - consider breaking into smaller functions"
                )
            )
        }

        // Operation count
        if (promiseAnalysis.totalOperationCount > 3) {
            val opPoints = ((promiseAnalysis.totalOperationCount - 3) * WEIGHT_PROMISE_OP_PER_COUNT)
                .coerceAtMost(10)
            score += opPoints
            factors.add(
                ComplexityFactor(
                    name = "Promise Operation Count",
                    description = "Many Promise operations increase migration complexity",
                    points = opPoints,
                    maxPoints = 10,
                    details = "${promiseAnalysis.totalOperationCount} Promise operations detected"
                )
            )
        }

        return Triple(score, factors, notes)
    }

    /**
     * Analyze handler type complexity.
     */
    private fun analyzeHandlerType(classInfo: ClassInfo): Triple<Int, ComplexityFactor?, String?> {
        // Check for GroovyHandler (more complex)
        if (classInfo.superclass?.contains("GroovyHandler") == true ||
            classInfo.superclass?.contains("groovy") == true
        ) {
            return Triple(
                WEIGHT_GROOVY_HANDLER,
                ComplexityFactor(
                    name = "Groovy Handler",
                    description = "Groovy handlers have additional DSL complexity",
                    points = WEIGHT_GROOVY_HANDLER,
                    maxPoints = WEIGHT_GROOVY_HANDLER,
                    details = "Consider migrating Groovy to Kotlin first"
                ),
                "GroovyHandler - consider converting to Kotlin before migration"
            )
        }

        // Check for Chain Action
        val isChainAction = classInfo.methods.any { method ->
            method.name == "execute" &&
                method.parameters.any { it.type.contains(RatpackTypes.CHAIN) }
        }
        if (isChainAction) {
            return Triple(
                WEIGHT_CHAIN_ACTION,
                ComplexityFactor(
                    name = "Chain Action",
                    description = "Chain actions define routing structure",
                    points = WEIGHT_CHAIN_ACTION,
                    maxPoints = WEIGHT_CHAIN_ACTION,
                    details = "Chain actions need conversion to Ktor routing DSL"
                ),
                "Chain Action - convert to Ktor routing DSL"
            )
        }

        return Triple(0, null, null)
    }

    /**
     * Analyze Guice dependency complexity.
     */
    private fun analyzeGuiceDependencies(
        classInfo: ClassInfo
    ): Triple<Int, ComplexityFactor?, List<String>> {
        val notes = mutableListOf<String>()

        // Count injected dependencies
        var injectedCount = 0

        // Check for @Inject annotations on fields
        for (field in classInfo.fields) {
            if (field.annotations.any { it.type in RatpackTypes.INJECT_ANNOTATIONS }) {
                injectedCount++
            }
        }

        // Check for final fields (likely constructor injected)
        injectedCount += classInfo.fields.count { it.isFinal && !it.isStatic }

        if (injectedCount == 0) {
            return Triple(0, null, notes)
        }

        val guicePoints = (injectedCount * WEIGHT_GUICE_DEPENDENCY_PER).coerceAtMost(15)

        if (injectedCount > 5) {
            notes.add("Many dependencies ($injectedCount) - consider refactoring before migration")
        }

        return Triple(
            guicePoints,
            ComplexityFactor(
                name = "Guice Dependencies",
                description = "Injected dependencies need DI framework migration",
                points = guicePoints,
                maxPoints = 15,
                details = "$injectedCount dependencies to migrate to new DI framework"
            ),
            notes
        )
    }

    /**
     * Analyze method count as proxy for class size.
     */
    private fun analyzeMethodCount(classInfo: ClassInfo): Pair<Int, ComplexityFactor?> {
        val publicMethods = classInfo.methods.count {
            !it.isSynthetic && it.visibility == codelens.core.model.Visibility.PUBLIC
        }

        if (publicMethods <= 5) {
            return Pair(0, null)
        }

        val methodPoints = ((publicMethods - 5) * WEIGHT_METHOD_COUNT_PER).coerceAtMost(10)

        return Pair(
            methodPoints,
            ComplexityFactor(
                name = "Class Size",
                description = "Larger classes require more migration effort",
                points = methodPoints,
                maxPoints = 10,
                details = "$publicMethods public methods - consider splitting if > 10"
            )
        )
    }

    /**
     * Calculate migration priority (lower = migrate first).
     */
    private fun calculatePriority(score: Int, promiseAnalysis: PromiseUsageInfo): Int {
        // Lower score = higher priority (migrate simple things first)
        // But also consider if class is a dependency for others
        return when {
            score <= THRESHOLD_LOW -> 1
            score <= THRESHOLD_MEDIUM -> 2
            score <= THRESHOLD_HIGH -> 3
            else -> 4
        }
    }

    /**
     * Find dependencies that should be migrated first.
     */
    private fun findBlockingDependencies(classInfo: ClassInfo): List<String> {
        val blockedBy = mutableListOf<String>()

        // Check field types for other project classes
        for (field in classInfo.fields) {
            val fieldType = field.type.substringBefore("<").trim()
            val fieldClass = classGraphProvider.getClass(fieldType)
            if (fieldClass != null && fieldClass.source == ClassSource.PROJECT) {
                // Check if this dependency is a handler or has Promise usage
                val depPromise = PromiseDetector(classGraphProvider).analyzeClass(fieldType)
                if (depPromise.totalOperationCount > 0) {
                    blockedBy.add(fieldType)
                }
            }
        }

        return blockedBy.distinct()
    }

    /**
     * Generate a human-readable migration reason.
     */
    private fun generateMigrationReason(complexity: ComplexityResult): String {
        return when (complexity.tier) {
            ComplexityTier.LOW -> "Quick win - simple migration"
            ComplexityTier.MEDIUM -> "Moderate complexity"
            ComplexityTier.HIGH -> "Complex - allocate dedicated time"
            ComplexityTier.CRITICAL -> "Critical complexity - careful planning needed"
        }
    }

    /**
     * Create empty complexity result for unknown class.
     */
    private fun emptyComplexityResult(fqn: String): ComplexityResult {
        return ComplexityResult(
            classFqn = fqn,
            score = 0,
            tier = ComplexityTier.LOW,
            estimatedHours = BASE_HOURS,
            factors = emptyList(),
            migrationNotes = listOf("Class not found in scan results"),
            migrationPriority = 0,
            blockedBy = emptyList()
        )
    }
}

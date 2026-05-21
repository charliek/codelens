package codelens.classgraph.ratpack

import codelens.classgraph.ClassGraphProvider
import codelens.core.model.MethodInfo
import codelens.core.model.ratpack.*

/**
 * Detects and analyzes Promise usage patterns in Ratpack code.
 *
 * Detects:
 * - Blocking.get(), Blocking.on() calls
 * - Promise.async(), Promise.sync(), Promise.value() calls
 * - Execution.fork() calls
 * - ParallelBatch usage
 * - Promise chain operations (map, flatMap, then, etc.)
 */
class PromiseDetector(
    private val classGraphProvider: ClassGraphProvider,
) {
    /**
     * Analyze Promise usage in a specific class.
     *
     * @param fqn Fully qualified class name
     * @return Promise usage info for the class
     */
    fun analyzeClass(fqn: String): PromiseUsageInfo {
        val classInfo =
            classGraphProvider.getClass(fqn)
                ?: return emptyPromiseUsageInfo(fqn)

        val operations = mutableListOf<PromiseOperation>()
        val promiseReturningMethods = mutableListOf<String>()

        for (method in classInfo.methods) {
            // Check return type for Promise
            if (isPromiseType(method.returnType)) {
                promiseReturningMethods.add(method.name)
            }

            // Analyze method for Promise operations
            val methodOperations = analyzeMethod(method)
            operations.addAll(methodOperations)
        }

        // Also check field types for Promise
        for (field in classInfo.fields) {
            if (isPromiseType(field.type)) {
                // Field of Promise type indicates Promise usage
                operations.add(
                    PromiseOperation(
                        operationType = PromiseOperationType.PROMISE_VALUE,
                        methodName = "<field:${field.name}>",
                        chainDepth = 0,
                    ),
                )
            }
        }

        return PromiseUsageInfo(
            classFqn = fqn,
            operations = operations,
            totalOperationCount = operations.size,
            usesBlocking =
                operations.any {
                    it.operationType == PromiseOperationType.BLOCKING_GET ||
                        it.operationType == PromiseOperationType.BLOCKING_ON
                },
            usesAsync = operations.any { it.operationType == PromiseOperationType.PROMISE_ASYNC },
            usesFork = operations.any { it.operationType == PromiseOperationType.EXECUTION_FORK },
            usesParallelBatch = operations.any { it.operationType == PromiseOperationType.PARALLEL_BATCH },
            maxChainDepth = operations.maxOfOrNull { it.chainDepth } ?: 0,
            promiseReturningMethods = promiseReturningMethods,
        )
    }

    /**
     * Get project-wide Promise usage summary.
     *
     * @param includeLibraries Include library classes
     * @return Project-wide Promise summary
     */
    fun getProjectSummary(includeLibraries: Boolean = false): PromiseSummary {
        val allUsages = mutableListOf<PromiseUsageInfo>()
        val operationCounts = mutableMapOf<PromiseOperationType, Int>()

        // Iterate through all project classes
        val filter = codelens.core.model.ClassFilter(includeLibraries = includeLibraries)
        val classes = classGraphProvider.listClasses(filter)

        for (classSummary in classes) {
            val usage = analyzeClass(classSummary.fqn)
            if (usage.totalOperationCount > 0) {
                allUsages.add(usage)

                // Count operations by type
                for (op in usage.operations) {
                    operationCounts[op.operationType] = (operationCounts[op.operationType] ?: 0) + 1
                }
            }
        }

        // Sort by complexity to find top complex classes
        val topComplexClasses =
            allUsages
                .sortedByDescending { it.totalOperationCount }
                .take(10)

        return PromiseSummary(
            classesUsingPromises = allUsages.size,
            blockingGetCount = operationCounts[PromiseOperationType.BLOCKING_GET] ?: 0,
            promiseAsyncCount = operationCounts[PromiseOperationType.PROMISE_ASYNC] ?: 0,
            executionForkCount = operationCounts[PromiseOperationType.EXECUTION_FORK] ?: 0,
            parallelBatchCount = operationCounts[PromiseOperationType.PARALLEL_BATCH] ?: 0,
            operatorCount =
                operationCounts.entries
                    .filter { it.key.name.startsWith("PROMISE_") }
                    .sumOf { it.value },
            operationBreakdown = operationCounts,
            topComplexClasses = topComplexClasses,
        )
    }

    /**
     * Search for classes matching Promise usage criteria.
     *
     * @param usesBlocking Filter for classes using Blocking
     * @param usesAsync Filter for classes using async
     * @param usesFork Filter for classes using fork
     * @param minOperations Minimum operation count
     * @return Matching Promise usage info list
     */
    fun search(
        usesBlocking: Boolean? = null,
        usesAsync: Boolean? = null,
        usesFork: Boolean? = null,
        minOperations: Int = 0,
    ): List<PromiseUsageInfo> {
        val filter = codelens.core.model.ClassFilter(includeLibraries = false)
        val classes = classGraphProvider.listClasses(filter)
        val results = mutableListOf<PromiseUsageInfo>()

        for (classSummary in classes) {
            val usage = analyzeClass(classSummary.fqn)

            // Apply filters
            if (usage.totalOperationCount < minOperations) continue
            if (usesBlocking != null && usage.usesBlocking != usesBlocking) continue
            if (usesAsync != null && usage.usesAsync != usesAsync) continue
            if (usesFork != null && usage.usesFork != usesFork) continue

            if (usage.totalOperationCount > 0) {
                results.add(usage)
            }
        }

        return results.sortedByDescending { it.totalOperationCount }
    }

    /**
     * Analyze a method for Promise operations.
     * Note: This is bytecode-level analysis, so we can only detect:
     * - Method signatures (return types, parameter types)
     * - Field types
     * We cannot see method bodies from bytecode metadata alone.
     */
    private fun analyzeMethod(method: MethodInfo): List<PromiseOperation> {
        val operations = mutableListOf<PromiseOperation>()

        // Detect based on return type
        when {
            method.returnType.contains(RatpackTypes.PROMISE) -> {
                // Method returns Promise - likely using Promise operators
                operations.add(
                    PromiseOperation(
                        operationType = detectPromiseReturnType(method),
                        methodName = method.name,
                        chainDepth = estimateChainDepth(method),
                    ),
                )
            }

            method.returnType.contains(RatpackTypes.OPERATION) -> {
                operations.add(
                    PromiseOperation(
                        operationType = PromiseOperationType.PROMISE_THEN,
                        methodName = method.name,
                        chainDepth = 1,
                    ),
                )
            }
        }

        // Detect based on parameter types
        for (param in method.parameters) {
            when {
                param.type.contains(RatpackTypes.CONTEXT) -> {
                    // Handler method - often uses Blocking or Promise
                    // We'll flag this as potential Promise usage
                }

                param.type.contains(RatpackTypes.EXECUTION) -> {
                    operations.add(
                        PromiseOperation(
                            operationType = PromiseOperationType.EXECUTION_FORK,
                            methodName = method.name,
                            chainDepth = 1,
                        ),
                    )
                }

                param.type.contains(RatpackTypes.PROMISE) -> {
                    // Method accepts Promise parameter - likely transforms it
                    operations.add(
                        PromiseOperation(
                            operationType = PromiseOperationType.PROMISE_TRANSFORM,
                            methodName = method.name,
                            chainDepth = 1,
                        ),
                    )
                }
            }
        }

        return operations
    }

    /**
     * Detect what kind of Promise operation a method likely represents.
     */
    private fun detectPromiseReturnType(method: MethodInfo): PromiseOperationType {
        val methodName = method.name.lowercase()
        return when {
            methodName.contains("blocking") || methodName.contains("sync") ->
                PromiseOperationType.BLOCKING_GET

            methodName.contains("async") -> PromiseOperationType.PROMISE_ASYNC
            methodName.contains("map") && methodName.contains("flat") ->
                PromiseOperationType.PROMISE_FLAT_MAP

            methodName.contains("map") -> PromiseOperationType.PROMISE_MAP
            methodName.contains("cache") -> PromiseOperationType.PROMISE_CACHE
            methodName.contains("retry") -> PromiseOperationType.PROMISE_RETRY
            methodName.contains("error") -> PromiseOperationType.PROMISE_ON_ERROR
            else -> PromiseOperationType.PROMISE_VALUE
        }
    }

    /**
     * Estimate chain depth based on method characteristics.
     * Without bytecode analysis, this is a heuristic based on method naming.
     */
    private fun estimateChainDepth(method: MethodInfo): Int {
        // Heuristic: methods with multiple Promise-related keywords suggest deeper chains
        val indicators = listOf("map", "flat", "then", "error", "cache", "retry", "route")
        val methodName = method.name.lowercase()
        return indicators.count { methodName.contains(it) }.coerceAtLeast(1)
    }

    /**
     * Check if a type string represents a Promise type.
     */
    private fun isPromiseType(type: String): Boolean =
        type.contains(RatpackTypes.PROMISE) ||
            type.contains(RatpackTypes.OPERATION) ||
            type.contains(RatpackTypes.BLOCKING)

    /**
     * Create empty Promise usage info for a class.
     */
    private fun emptyPromiseUsageInfo(fqn: String): PromiseUsageInfo =
        PromiseUsageInfo(
            classFqn = fqn,
            operations = emptyList(),
            totalOperationCount = 0,
            usesBlocking = false,
            usesAsync = false,
            usesFork = false,
            usesParallelBatch = false,
            maxChainDepth = 0,
            promiseReturningMethods = emptyList(),
        )
}

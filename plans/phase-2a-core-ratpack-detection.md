# Phase 2A: Core Ratpack Detection

**Status**: Not Started
**Target**: MVP Ratpack analysis with bytecode-level detection
**Features**: 1-4 (Handler Discovery, Promise Detection, Complexity Scoring, Guice Analysis)

---

## Overview

Phase 2A establishes the foundation for Ratpack-specific analysis. All features use ClassGraph bytecode analysis (no source parsing). The goal is to identify what needs migration and estimate complexity.

**Success Criteria**:
- Can scan any Ratpack project and identify all handlers
- Produces accurate complexity scores that match manual assessment
- JSON output is useful for LLM-assisted migration

---

## Table of Contents

1. [Data Models](#data-models)
2. [Feature 1: Handler Discovery](#feature-1-handler-discovery--classification)
3. [Feature 2: Promise Detection](#feature-2-promise-usage-detection)
4. [Feature 3: Complexity Scoring](#feature-3-migration-complexity-scoring)
5. [Feature 4: Guice Analysis](#feature-4-guice-di-analysis)
6. [API Endpoints](#api-endpoints)
7. [CLI Commands](#cli-commands)
8. [Testing Specifications](#testing-specifications)
9. [File Manifest](#files-to-createmodify)

---

## Data Models

### Kotlin Data Models (server/core)

#### File: `server/core/src/main/kotlin/codelens/core/model/ratpack/RatpackModels.kt`

```kotlin
package codelens.core.model.ratpack

import codelens.core.model.ClassSource
import codelens.core.model.MethodInfo
import kotlinx.serialization.Serializable

// ============================================================================
// Handler Models
// ============================================================================

/**
 * Classification of Ratpack handler types.
 */
@Serializable
enum class HandlerType {
    /** Implements ratpack.handling.Handler directly */
    HANDLER,
    /** Implements ratpack.func.Action<Chain> */
    CHAIN_ACTION,
    /** Has a method that accepts Context and calls next() */
    INLINE_HANDLER,
    /** Extends GroovyHandler or similar base class */
    GROOVY_HANDLER
}

/**
 * Summary info about a handler for list views.
 */
@Serializable
data class HandlerSummary(
    /** Fully qualified class name */
    val fqn: String,
    /** Simple class name */
    val simpleName: String,
    /** Package name */
    val packageName: String,
    /** Type of handler */
    val handlerType: HandlerType,
    /** Source of the class */
    val source: ClassSource,
    /** Complexity score (0-100) */
    val complexityScore: Int,
    /** Complexity tier: LOW, MEDIUM, HIGH, CRITICAL */
    val complexityTier: ComplexityTier,
    /** Number of Promise operations detected */
    val promiseOperationCount: Int,
    /** Whether this handler uses Blocking.get() */
    val usesBlocking: Boolean
)

/**
 * Full detailed information about a handler.
 */
@Serializable
data class HandlerInfo(
    /** Fully qualified class name */
    val fqn: String,
    /** Simple class name */
    val simpleName: String,
    /** Package name */
    val packageName: String,
    /** Type of handler */
    val handlerType: HandlerType,
    /** Source of the class */
    val source: ClassSource,
    /** Superclass FQN if any */
    val superclass: String?,
    /** Implemented interfaces */
    val interfaces: List<String>,
    /** The handler method(s) - usually just handle(Context) */
    val handlerMethods: List<MethodInfo>,
    /** All methods in this class */
    val allMethods: List<MethodInfo>,
    /** Promise usage analysis for this handler */
    val promiseAnalysis: PromiseUsageInfo,
    /** Complexity result for this handler */
    val complexity: ComplexityResult,
    /** Guice dependencies injected into this handler */
    val injectedDependencies: List<InjectedDependency>
)

/**
 * A dependency injected via constructor or @Inject.
 */
@Serializable
data class InjectedDependency(
    /** Name of the parameter/field */
    val name: String,
    /** Type FQN */
    val typeFqn: String,
    /** Injection source: CONSTRUCTOR, FIELD, METHOD */
    val injectionType: InjectionType
)

@Serializable
enum class InjectionType {
    CONSTRUCTOR,
    FIELD,
    METHOD
}

// ============================================================================
// Promise Models
// ============================================================================

/**
 * Types of Promise operations detected.
 */
@Serializable
enum class PromiseOperationType {
    /** ratpack.exec.Blocking.get() */
    BLOCKING_GET,
    /** ratpack.exec.Blocking.on() */
    BLOCKING_ON,
    /** ratpack.exec.Promise.async() */
    PROMISE_ASYNC,
    /** ratpack.exec.Promise.sync() */
    PROMISE_SYNC,
    /** ratpack.exec.Promise.value() */
    PROMISE_VALUE,
    /** ratpack.exec.Execution.fork() */
    EXECUTION_FORK,
    /** ratpack.exec.ParallelBatch */
    PARALLEL_BATCH,
    /** .map() on Promise */
    PROMISE_MAP,
    /** .flatMap() on Promise */
    PROMISE_FLAT_MAP,
    /** .then() on Promise */
    PROMISE_THEN,
    /** .onError() on Promise */
    PROMISE_ON_ERROR,
    /** .route() on Promise */
    PROMISE_ROUTE,
    /** .cache() on Promise */
    PROMISE_CACHE,
    /** .retry() on Promise */
    PROMISE_RETRY,
    /** .transform() on Promise */
    PROMISE_TRANSFORM
}

/**
 * A detected Promise operation usage.
 */
@Serializable
data class PromiseOperation(
    /** Type of operation */
    val operationType: PromiseOperationType,
    /** Method where this was detected */
    val methodName: String,
    /** Estimated chain depth (how many operations chained) */
    val chainDepth: Int
)

/**
 * Promise usage summary for a single class.
 */
@Serializable
data class PromiseUsageInfo(
    /** Class FQN */
    val classFqn: String,
    /** All Promise operations in this class */
    val operations: List<PromiseOperation>,
    /** Total operation count */
    val totalOperationCount: Int,
    /** Uses Blocking.get() */
    val usesBlocking: Boolean,
    /** Uses Promise.async() */
    val usesAsync: Boolean,
    /** Uses Execution.fork() */
    val usesFork: Boolean,
    /** Uses ParallelBatch */
    val usesParallelBatch: Boolean,
    /** Maximum detected chain depth */
    val maxChainDepth: Int,
    /** Methods with Promise return types */
    val promiseReturningMethods: List<String>
)

/**
 * Project-wide Promise usage summary.
 */
@Serializable
data class PromiseSummary(
    /** Total classes using Promises */
    val classesUsingPromises: Int,
    /** Total Blocking.get() usages */
    val blockingGetCount: Int,
    /** Total Promise.async() usages */
    val promiseAsyncCount: Int,
    /** Total Execution.fork() usages */
    val executionForkCount: Int,
    /** Total ParallelBatch usages */
    val parallelBatchCount: Int,
    /** Total Promise operator calls */
    val operatorCount: Int,
    /** Breakdown by operation type */
    val operationBreakdown: Map<PromiseOperationType, Int>,
    /** Classes with highest Promise complexity */
    val topComplexClasses: List<PromiseUsageInfo>
)

// ============================================================================
// Complexity Models
// ============================================================================

/**
 * Complexity tier classification.
 */
@Serializable
enum class ComplexityTier {
    /** Score 0-25: Simple migration, likely 1-2 hours */
    LOW,
    /** Score 26-50: Moderate migration, likely 2-4 hours */
    MEDIUM,
    /** Score 51-75: Complex migration, likely 4-8 hours */
    HIGH,
    /** Score 76-100: Critical complexity, likely 8+ hours */
    CRITICAL
}

/**
 * Individual factor contributing to complexity score.
 */
@Serializable
data class ComplexityFactor(
    /** Factor name */
    val name: String,
    /** Factor description */
    val description: String,
    /** Points contributed to score */
    val points: Int,
    /** Maximum possible points for this factor */
    val maxPoints: Int,
    /** Details about why points were assigned */
    val details: String
)

/**
 * Full complexity analysis result.
 */
@Serializable
data class ComplexityResult(
    /** Class FQN */
    val classFqn: String,
    /** Total complexity score (0-100) */
    val score: Int,
    /** Complexity tier */
    val tier: ComplexityTier,
    /** Estimated migration effort in hours */
    val estimatedHours: Double,
    /** Individual factors contributing to score */
    val factors: List<ComplexityFactor>,
    /** Migration notes/warnings */
    val migrationNotes: List<String>,
    /** Suggested migration order priority (1 = first) */
    val migrationPriority: Int,
    /** Dependencies that should be migrated first */
    val blockedBy: List<String>
)

/**
 * Project-wide complexity summary.
 */
@Serializable
data class ComplexitySummary(
    /** Total handlers analyzed */
    val totalHandlers: Int,
    /** Handlers by tier */
    val tierBreakdown: Map<ComplexityTier, Int>,
    /** Total estimated hours */
    val totalEstimatedHours: Double,
    /** Average complexity score */
    val averageScore: Double,
    /** Suggested migration order */
    val migrationOrder: List<MigrationOrderItem>
)

/**
 * Item in the suggested migration order.
 */
@Serializable
data class MigrationOrderItem(
    /** Class FQN */
    val classFqn: String,
    /** Simple name */
    val simpleName: String,
    /** Complexity tier */
    val tier: ComplexityTier,
    /** Estimated hours */
    val estimatedHours: Double,
    /** Order number */
    val order: Int,
    /** Reason for this position */
    val reason: String
)

// ============================================================================
// Guice Models
// ============================================================================

/**
 * Type of Guice module.
 */
@Serializable
enum class GuiceModuleType {
    /** Extends com.google.inject.AbstractModule */
    ABSTRACT_MODULE,
    /** Extends ratpack.guice.ConfigurableModule */
    CONFIGURABLE_MODULE,
    /** Has @Provides methods but doesn't extend Module */
    PROVIDER_CLASS
}

/**
 * A binding configured in a Guice module.
 */
@Serializable
data class GuiceBinding(
    /** Type being bound (interface/class FQN) */
    val boundType: String,
    /** Implementation type (null for @Provides) */
    val toType: String?,
    /** Scope annotation if any */
    val scope: String?,
    /** Is this a multibinding (Set/Map) */
    val isMultibinding: Boolean,
    /** Binding source: BIND, PROVIDES, PROVIDES_INTO_SET, etc */
    val bindingSource: BindingSource
)

@Serializable
enum class BindingSource {
    /** bind(X).to(Y) */
    BIND_TO,
    /** bind(X).toInstance(y) */
    BIND_TO_INSTANCE,
    /** bind(X).toProvider(P) */
    BIND_TO_PROVIDER,
    /** @Provides method */
    PROVIDES,
    /** @ProvidesIntoSet method */
    PROVIDES_INTO_SET,
    /** @ProvidesIntoMap method */
    PROVIDES_INTO_MAP
}

/**
 * Summary info about a Guice module for list views.
 */
@Serializable
data class GuiceModuleSummary(
    /** Fully qualified class name */
    val fqn: String,
    /** Simple class name */
    val simpleName: String,
    /** Package name */
    val packageName: String,
    /** Module type */
    val moduleType: GuiceModuleType,
    /** Number of bindings */
    val bindingCount: Int,
    /** Number of @Provides methods */
    val providesMethodCount: Int
)

/**
 * Full Guice module information.
 */
@Serializable
data class GuiceModuleInfo(
    /** Fully qualified class name */
    val fqn: String,
    /** Simple class name */
    val simpleName: String,
    /** Package name */
    val packageName: String,
    /** Module type */
    val moduleType: GuiceModuleType,
    /** Configuration type for ConfigurableModule (null otherwise) */
    val configType: String?,
    /** All bindings in this module */
    val bindings: List<GuiceBinding>,
    /** All @Provides methods */
    val providesMethods: List<ProvidesMethodInfo>,
    /** Other modules installed by this module */
    val installedModules: List<String>
)

/**
 * Info about a @Provides method.
 */
@Serializable
data class ProvidesMethodInfo(
    /** Method name */
    val methodName: String,
    /** Return type (what is provided) */
    val providesType: String,
    /** Scope annotation if any */
    val scope: String?,
    /** Is @ProvidesIntoSet */
    val intoSet: Boolean,
    /** Is @ProvidesIntoMap */
    val intoMap: Boolean,
    /** Dependencies (method parameters) */
    val dependencies: List<String>
)
```

---

## Feature 1: Handler Discovery & Classification

### Server Implementation

#### File: `server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/RatpackTypes.kt`

```kotlin
package codelens.classgraph.ratpack

/**
 * Constants for Ratpack type detection.
 */
object RatpackTypes {
    // Core Handler types
    const val HANDLER = "ratpack.handling.Handler"
    const val CONTEXT = "ratpack.handling.Context"
    const val CHAIN = "ratpack.handling.Chain"
    const val CHAIN_ACTION = "ratpack.func.Action"  // Action<Chain>
    const val GROOVY_HANDLER = "ratpack.groovy.handling.GroovyHandler"

    // Promise types
    const val PROMISE = "ratpack.exec.Promise"
    const val BLOCKING = "ratpack.exec.Blocking"
    const val EXECUTION = "ratpack.exec.Execution"
    const val OPERATION = "ratpack.exec.Operation"
    const val PARALLEL_BATCH = "ratpack.exec.util.ParallelBatch"

    // Guice types
    const val ABSTRACT_MODULE = "com.google.inject.AbstractModule"
    const val CONFIGURABLE_MODULE = "ratpack.guice.ConfigurableModule"
    const val GUICE_MODULE = "com.google.inject.Module"
    const val PROVIDES = "com.google.inject.Provides"
    const val PROVIDES_INTO_SET = "com.google.inject.multibindings.ProvidesIntoSet"
    const val PROVIDES_INTO_MAP = "com.google.inject.multibindings.ProvidesIntoMap"
    const val SINGLETON = "com.google.inject.Singleton"
    const val INJECT = "com.google.inject.Inject"
    const val JAKARTA_INJECT = "jakarta.inject.Inject"
    const val JAVAX_INJECT = "javax.inject.Inject"

    // Scope annotations
    val SCOPE_ANNOTATIONS = setOf(
        SINGLETON,
        "com.google.inject.servlet.RequestScoped",
        "com.google.inject.servlet.SessionScoped",
        "ratpack.handling.RequestScoped"
    )

    // Promise operators (method names on Promise)
    val PROMISE_OPERATORS = setOf(
        "map", "flatMap", "then", "onError", "route",
        "cache", "retry", "transform", "apply", "flatOp",
        "mapError", "onYield", "wiretap", "throttle",
        "time", "close", "result"
    )

    // Blocking method names
    val BLOCKING_METHODS = setOf("get", "on", "exec")
}
```

#### File: `server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/RatpackDetector.kt`

```kotlin
package codelens.classgraph.ratpack

import codelens.classgraph.ClassGraphProvider
import codelens.core.model.*
import codelens.core.model.ratpack.*
import org.slf4j.LoggerFactory

/**
 * Detects Ratpack handlers in the scanned codebase.
 *
 * Detection Strategy:
 * 1. Direct implementation of ratpack.handling.Handler
 * 2. Implementation of Action<Chain> (ratpack.func.Action with Chain type param)
 * 3. Classes extending GroovyHandler
 * 4. Classes with methods accepting Context parameter
 */
class RatpackDetector(
    private val classGraphProvider: ClassGraphProvider,
    private val promiseDetector: PromiseDetector,
    private val complexityCalculator: ComplexityCalculator
) {
    private val logger = LoggerFactory.getLogger(RatpackDetector::class.java)

    /**
     * Find all handlers in the project.
     */
    fun findAllHandlers(): List<HandlerSummary> {
        val handlers = mutableListOf<HandlerSummary>()

        // Strategy 1: Direct Handler implementations
        val (directImpls, indirectImpls) = classGraphProvider.getImplementations(
            RatpackTypes.HANDLER,
            includeLibraries = false
        )

        (directImpls + indirectImpls).forEach { classSummary ->
            classGraphProvider.getClass(classSummary.fqn)?.let { classInfo ->
                handlers.add(buildHandlerSummary(classInfo, HandlerType.HANDLER))
            }
        }

        // Strategy 2: Action<Chain> implementations
        findChainActionClasses().forEach { classInfo ->
            // Avoid duplicates
            if (handlers.none { it.fqn == classInfo.name.fqn }) {
                handlers.add(buildHandlerSummary(classInfo, HandlerType.CHAIN_ACTION))
            }
        }

        // Strategy 3: GroovyHandler extensions
        val (groovyDirect, groovyIndirect) = classGraphProvider.getImplementations(
            RatpackTypes.GROOVY_HANDLER,
            includeLibraries = false
        )

        (groovyDirect + groovyIndirect).forEach { classSummary ->
            if (handlers.none { it.fqn == classSummary.fqn }) {
                classGraphProvider.getClass(classSummary.fqn)?.let { classInfo ->
                    handlers.add(buildHandlerSummary(classInfo, HandlerType.GROOVY_HANDLER))
                }
            }
        }

        logger.info("Found ${handlers.size} handlers")
        return handlers.sortedBy { it.fqn }
    }

    /**
     * Get detailed handler information.
     */
    fun getHandlerDetail(fqn: String): HandlerInfo? {
        val classInfo = classGraphProvider.getClass(fqn) ?: return null

        // Determine handler type
        val handlerType = determineHandlerType(classInfo)

        // Get Promise analysis
        val promiseAnalysis = promiseDetector.analyzeClass(fqn)

        // Get complexity
        val complexity = complexityCalculator.calculate(fqn)

        // Find injected dependencies
        val injectedDeps = findInjectedDependencies(classInfo)

        // Find handler methods
        val handlerMethods = findHandlerMethods(classInfo, handlerType)

        return HandlerInfo(
            fqn = classInfo.name.fqn,
            simpleName = classInfo.name.simpleName,
            packageName = classInfo.name.packageName,
            handlerType = handlerType,
            source = classInfo.source,
            superclass = classInfo.superclass,
            interfaces = classInfo.interfaces,
            handlerMethods = handlerMethods,
            allMethods = classInfo.methods.filter { !it.isSynthetic },
            promiseAnalysis = promiseAnalysis,
            complexity = complexity,
            injectedDependencies = injectedDeps
        )
    }

    /**
     * Find classes implementing Action<Chain>.
     */
    private fun findChainActionClasses(): List<ClassInfo> {
        // Get all Action implementations, then filter for Chain type parameter
        val actionClasses = classGraphProvider.listClasses(ClassFilter(
            implementsInterface = RatpackTypes.CHAIN_ACTION,
            includeLibraries = false
        ))

        return actionClasses.mapNotNull { summary ->
            classGraphProvider.getClass(summary.fqn)?.let { classInfo ->
                // Check if any interface is Action<Chain>
                val hasChainAction = classInfo.interfaces.any { iface ->
                    iface.contains("Action") &&
                    (iface.contains("Chain") || isChainActionType(classInfo))
                }
                if (hasChainAction) classInfo else null
            }
        }
    }

    /**
     * Check if class is an Action<Chain> by looking at method signatures.
     */
    private fun isChainActionType(classInfo: ClassInfo): Boolean {
        // Look for execute(Chain) method
        return classInfo.methods.any { method ->
            method.name == "execute" &&
            method.parameters.size == 1 &&
            method.parameters[0].type.contains("Chain")
        }
    }

    /**
     * Determine handler type from class info.
     */
    private fun determineHandlerType(classInfo: ClassInfo): HandlerType {
        return when {
            classInfo.interfaces.contains(RatpackTypes.HANDLER) -> HandlerType.HANDLER
            classInfo.superclass == RatpackTypes.GROOVY_HANDLER -> HandlerType.GROOVY_HANDLER
            isChainActionType(classInfo) -> HandlerType.CHAIN_ACTION
            hasContextHandlerMethod(classInfo) -> HandlerType.INLINE_HANDLER
            else -> HandlerType.HANDLER
        }
    }

    /**
     * Check if class has a method that looks like a handler.
     */
    private fun hasContextHandlerMethod(classInfo: ClassInfo): Boolean {
        return classInfo.methods.any { method ->
            method.parameters.any { param ->
                param.type.contains(RatpackTypes.CONTEXT)
            }
        }
    }

    /**
     * Find handler methods in the class.
     */
    private fun findHandlerMethods(classInfo: ClassInfo, handlerType: HandlerType): List<MethodInfo> {
        return when (handlerType) {
            HandlerType.HANDLER -> classInfo.methods.filter {
                it.name == "handle" &&
                it.parameters.size == 1 &&
                it.parameters[0].type.contains("Context")
            }
            HandlerType.CHAIN_ACTION -> classInfo.methods.filter {
                it.name == "execute" &&
                it.parameters.size == 1 &&
                it.parameters[0].type.contains("Chain")
            }
            HandlerType.GROOVY_HANDLER -> classInfo.methods.filter {
                it.name == "handle" || it.name == "doHandle"
            }
            HandlerType.INLINE_HANDLER -> classInfo.methods.filter {
                it.parameters.any { param ->
                    param.type.contains("Context")
                }
            }
        }
    }

    /**
     * Find dependencies injected into this handler.
     */
    private fun findInjectedDependencies(classInfo: ClassInfo): List<InjectedDependency> {
        val deps = mutableListOf<InjectedDependency>()

        // Constructor injection - find constructor with @Inject or most params
        val constructors = classInfo.methods.filter { it.name == "<init>" }
        val injectConstructor = constructors.find { ctor ->
            ctor.annotations.any { ann ->
                ann.type in listOf(RatpackTypes.INJECT, RatpackTypes.JAVAX_INJECT, RatpackTypes.JAKARTA_INJECT)
            }
        } ?: constructors.maxByOrNull { it.parameters.size }

        injectConstructor?.parameters?.forEach { param ->
            deps.add(InjectedDependency(
                name = param.name,
                typeFqn = param.type,
                injectionType = InjectionType.CONSTRUCTOR
            ))
        }

        // Field injection
        classInfo.fields.filter { field ->
            field.annotations.any { ann ->
                ann.type in listOf(RatpackTypes.INJECT, RatpackTypes.JAVAX_INJECT, RatpackTypes.JAKARTA_INJECT)
            }
        }.forEach { field ->
            deps.add(InjectedDependency(
                name = field.name,
                typeFqn = field.type,
                injectionType = InjectionType.FIELD
            ))
        }

        return deps
    }

    /**
     * Build handler summary from class info.
     */
    private fun buildHandlerSummary(classInfo: ClassInfo, handlerType: HandlerType): HandlerSummary {
        val promiseAnalysis = promiseDetector.analyzeClass(classInfo.name.fqn)
        val complexity = complexityCalculator.calculate(classInfo.name.fqn)

        return HandlerSummary(
            fqn = classInfo.name.fqn,
            simpleName = classInfo.name.simpleName,
            packageName = classInfo.name.packageName,
            handlerType = handlerType,
            source = classInfo.source,
            complexityScore = complexity.score,
            complexityTier = complexity.tier,
            promiseOperationCount = promiseAnalysis.totalOperationCount,
            usesBlocking = promiseAnalysis.usesBlocking
        )
    }
}
```

---

## Feature 2: Promise Usage Detection

#### File: `server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/PromiseDetector.kt`

```kotlin
package codelens.classgraph.ratpack

import codelens.classgraph.ClassGraphProvider
import codelens.core.model.*
import codelens.core.model.ratpack.*
import org.slf4j.LoggerFactory

/**
 * Detects Promise-related usage in the codebase.
 *
 * Detection Strategy:
 * Uses ClassGraph's class dependency analysis to find:
 * 1. Classes that have Blocking, Promise, Execution as dependencies
 * 2. Methods that return Promise types
 * 3. Infers operator usage from method dependencies
 *
 * LIMITATION: Bytecode analysis cannot detect exact method call chains.
 * We estimate chain depth based on dependency count and method complexity.
 */
class PromiseDetector(
    private val classGraphProvider: ClassGraphProvider
) {
    private val logger = LoggerFactory.getLogger(PromiseDetector::class.java)

    /**
     * Analyze Promise usage for a single class.
     */
    fun analyzeClass(fqn: String): PromiseUsageInfo {
        val classInfo = classGraphProvider.getClass(fqn)
            ?: return emptyPromiseUsageInfo(fqn)

        val operations = mutableListOf<PromiseOperation>()

        // Get class dependencies
        val (outgoing, _) = classGraphProvider.getDependencies(fqn, includeLibraries = true)

        // Analyze dependencies for Promise-related types
        val usesBlocking = outgoing.any { it.classFqn == RatpackTypes.BLOCKING }
        val usesPromise = outgoing.any { it.classFqn == RatpackTypes.PROMISE }
        val usesFork = outgoing.any { it.classFqn == RatpackTypes.EXECUTION }
        val usesParallelBatch = outgoing.any { it.classFqn == RatpackTypes.PARALLEL_BATCH }

        // Track Blocking operations
        if (usesBlocking) {
            operations.add(PromiseOperation(
                operationType = PromiseOperationType.BLOCKING_GET,
                methodName = "detected via dependency",
                chainDepth = 1
            ))
        }

        // Find Promise-returning methods
        val promiseReturningMethods = classInfo.methods
            .filter { it.returnType.contains("Promise") }
            .map { it.name }

        // Estimate operations from Promise-returning methods
        promiseReturningMethods.forEach { methodName ->
            // Each Promise-returning method likely has operators
            operations.add(PromiseOperation(
                operationType = PromiseOperationType.PROMISE_MAP,
                methodName = methodName,
                chainDepth = estimateChainDepth(classInfo, methodName)
            ))
        }

        // Check for async/fork patterns
        if (usesFork) {
            operations.add(PromiseOperation(
                operationType = PromiseOperationType.EXECUTION_FORK,
                methodName = "detected via dependency",
                chainDepth = 2
            ))
        }

        if (usesParallelBatch) {
            operations.add(PromiseOperation(
                operationType = PromiseOperationType.PARALLEL_BATCH,
                methodName = "detected via dependency",
                chainDepth = 3
            ))
        }

        val maxChainDepth = operations.maxOfOrNull { it.chainDepth } ?: 0

        return PromiseUsageInfo(
            classFqn = fqn,
            operations = operations,
            totalOperationCount = operations.size,
            usesBlocking = usesBlocking,
            usesAsync = promiseReturningMethods.isNotEmpty(),
            usesFork = usesFork,
            usesParallelBatch = usesParallelBatch,
            maxChainDepth = maxChainDepth,
            promiseReturningMethods = promiseReturningMethods
        )
    }

    /**
     * Get project-wide Promise usage summary.
     */
    fun getProjectSummary(): PromiseSummary {
        val allClasses = classGraphProvider.listClasses(ClassFilter(includeLibraries = false))

        var blockingGetCount = 0
        var promiseAsyncCount = 0
        var executionForkCount = 0
        var parallelBatchCount = 0
        val operationBreakdown = mutableMapOf<PromiseOperationType, Int>()
        val classAnalyses = mutableListOf<PromiseUsageInfo>()

        allClasses.forEach { classSummary ->
            val analysis = analyzeClass(classSummary.fqn)

            if (analysis.totalOperationCount > 0) {
                classAnalyses.add(analysis)

                if (analysis.usesBlocking) blockingGetCount++
                if (analysis.usesAsync) promiseAsyncCount++
                if (analysis.usesFork) executionForkCount++
                if (analysis.usesParallelBatch) parallelBatchCount++

                analysis.operations.forEach { op ->
                    operationBreakdown[op.operationType] =
                        operationBreakdown.getOrDefault(op.operationType, 0) + 1
                }
            }
        }

        // Sort by complexity (total operations * chain depth)
        val topComplex = classAnalyses
            .sortedByDescending { it.totalOperationCount * it.maxChainDepth }
            .take(10)

        return PromiseSummary(
            classesUsingPromises = classAnalyses.size,
            blockingGetCount = blockingGetCount,
            promiseAsyncCount = promiseAsyncCount,
            executionForkCount = executionForkCount,
            parallelBatchCount = parallelBatchCount,
            operatorCount = operationBreakdown.values.sum(),
            operationBreakdown = operationBreakdown,
            topComplexClasses = topComplex
        )
    }

    /**
     * Search for classes matching Promise usage criteria.
     */
    fun search(
        hasBlocking: Boolean? = null,
        hasAsync: Boolean? = null,
        hasFork: Boolean? = null,
        minChainDepth: Int? = null
    ): List<PromiseUsageInfo> {
        val allClasses = classGraphProvider.listClasses(ClassFilter(includeLibraries = false))

        return allClasses.mapNotNull { classSummary ->
            val analysis = analyzeClass(classSummary.fqn)

            // Apply filters
            if (analysis.totalOperationCount == 0) return@mapNotNull null
            if (hasBlocking == true && !analysis.usesBlocking) return@mapNotNull null
            if (hasAsync == true && !analysis.usesAsync) return@mapNotNull null
            if (hasFork == true && !analysis.usesFork) return@mapNotNull null
            if (minChainDepth != null && analysis.maxChainDepth < minChainDepth) return@mapNotNull null

            analysis
        }.sortedByDescending { it.totalOperationCount }
    }

    /**
     * Estimate chain depth based on method complexity.
     * Since we can't trace exact calls, we use heuristics.
     */
    private fun estimateChainDepth(classInfo: ClassInfo, methodName: String): Int {
        val method = classInfo.methods.find { it.name == methodName }
            ?: return 1

        // Heuristic: More parameters and dependencies = likely more chaining
        val paramCount = method.parameters.size

        // Check for common patterns in parameter types
        val hasErrorHandler = method.parameters.any {
            it.type.contains("Consumer") || it.type.contains("Action")
        }

        return when {
            paramCount >= 3 && hasErrorHandler -> 4
            paramCount >= 2 -> 3
            hasErrorHandler -> 2
            else -> 1
        }
    }

    private fun emptyPromiseUsageInfo(fqn: String) = PromiseUsageInfo(
        classFqn = fqn,
        operations = emptyList(),
        totalOperationCount = 0,
        usesBlocking = false,
        usesAsync = false,
        usesFork = false,
        usesParallelBatch = false,
        maxChainDepth = 0,
        promiseReturningMethods = emptyList()
    )
}
```

---

## Feature 3: Migration Complexity Scoring

#### File: `server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/ComplexityCalculator.kt`

```kotlin
package codelens.classgraph.ratpack

import codelens.classgraph.ClassGraphProvider
import codelens.core.model.*
import codelens.core.model.ratpack.*
import org.slf4j.LoggerFactory

/**
 * Calculates migration complexity scores for Ratpack handlers.
 *
 * Scoring Formula (0-100 scale):
 *
 * Base Factors (max 40 points):
 * - Handler type: HANDLER=5, CHAIN_ACTION=10, GROOVY_HANDLER=15
 * - Method count: 1-5=0, 6-10=5, 11-20=10, 21+=15
 * - Field count (dependencies): 1-3=0, 4-6=5, 7+=10
 *
 * Promise Factors (max 40 points):
 * - Uses Blocking.get(): +10
 * - Uses Execution.fork(): +10
 * - Uses ParallelBatch: +10
 * - Chain depth (per level over 1): +3 (max 10)
 *
 * Anti-pattern Factors (max 20 points):
 * - Nested blocking calls (inferred): +10
 * - Multiple Promise chains in one method (inferred): +5
 * - Mixing blocking and async (both present): +5
 */
class ComplexityCalculator(
    private val classGraphProvider: ClassGraphProvider,
    private val promiseDetector: PromiseDetector
) {
    private val logger = LoggerFactory.getLogger(ComplexityCalculator::class.java)

    companion object {
        // Scoring weights
        const val MAX_SCORE = 100

        // Base factors
        const val HANDLER_TYPE_HANDLER = 5
        const val HANDLER_TYPE_CHAIN_ACTION = 10
        const val HANDLER_TYPE_GROOVY = 15

        const val METHOD_COUNT_MEDIUM = 5    // 6-10 methods
        const val METHOD_COUNT_HIGH = 10     // 11-20 methods
        const val METHOD_COUNT_EXTREME = 15  // 21+ methods

        const val FIELD_COUNT_MEDIUM = 5     // 4-6 fields
        const val FIELD_COUNT_HIGH = 10      // 7+ fields

        // Promise factors
        const val BLOCKING_GET_PENALTY = 10
        const val EXECUTION_FORK_PENALTY = 10
        const val PARALLEL_BATCH_PENALTY = 10
        const val CHAIN_DEPTH_PENALTY_PER_LEVEL = 3
        const val CHAIN_DEPTH_MAX_PENALTY = 10

        // Anti-pattern factors
        const val MIXED_BLOCKING_ASYNC_PENALTY = 5
        const val MULTIPLE_PROMISE_CHAINS_PENALTY = 5
        const val NESTED_BLOCKING_PENALTY = 10

        // Hours estimation
        fun estimateHours(score: Int): Double = when {
            score <= 25 -> 1.5
            score <= 50 -> 3.5
            score <= 75 -> 6.0
            else -> 10.0
        }

        fun scoreTier(score: Int): ComplexityTier = when {
            score <= 25 -> ComplexityTier.LOW
            score <= 50 -> ComplexityTier.MEDIUM
            score <= 75 -> ComplexityTier.HIGH
            else -> ComplexityTier.CRITICAL
        }
    }

    /**
     * Calculate complexity for a single class.
     */
    fun calculate(fqn: String): ComplexityResult {
        val classInfo = classGraphProvider.getClass(fqn)
            ?: return emptyComplexityResult(fqn)

        val promiseAnalysis = promiseDetector.analyzeClass(fqn)
        val factors = mutableListOf<ComplexityFactor>()
        var totalScore = 0
        val migrationNotes = mutableListOf<String>()

        // === Base Factors ===

        // Handler type
        val handlerType = determineHandlerType(classInfo)
        val handlerTypePoints = when (handlerType) {
            HandlerType.HANDLER -> HANDLER_TYPE_HANDLER
            HandlerType.CHAIN_ACTION -> HANDLER_TYPE_CHAIN_ACTION
            HandlerType.GROOVY_HANDLER -> HANDLER_TYPE_GROOVY
            HandlerType.INLINE_HANDLER -> HANDLER_TYPE_HANDLER
        }
        factors.add(ComplexityFactor(
            name = "Handler Type",
            description = "Complexity based on handler implementation style",
            points = handlerTypePoints,
            maxPoints = 15,
            details = "Type: ${handlerType.name}"
        ))
        totalScore += handlerTypePoints

        // Method count
        val methodCount = classInfo.methods.filter { !it.isSynthetic }.size
        val methodPoints = when {
            methodCount <= 5 -> 0
            methodCount <= 10 -> METHOD_COUNT_MEDIUM
            methodCount <= 20 -> METHOD_COUNT_HIGH
            else -> METHOD_COUNT_EXTREME
        }
        factors.add(ComplexityFactor(
            name = "Method Count",
            description = "More methods = more logic to migrate",
            points = methodPoints,
            maxPoints = 15,
            details = "$methodCount methods"
        ))
        totalScore += methodPoints

        // Field count (dependencies)
        val fieldCount = classInfo.fields.size
        val fieldPoints = when {
            fieldCount <= 3 -> 0
            fieldCount <= 6 -> FIELD_COUNT_MEDIUM
            else -> FIELD_COUNT_HIGH
        }
        factors.add(ComplexityFactor(
            name = "Dependency Count",
            description = "More dependencies = more integration work",
            points = fieldPoints,
            maxPoints = 10,
            details = "$fieldCount fields/dependencies"
        ))
        totalScore += fieldPoints

        // === Promise Factors ===

        if (promiseAnalysis.usesBlocking) {
            factors.add(ComplexityFactor(
                name = "Blocking.get() Usage",
                description = "Blocking calls need conversion to suspend functions",
                points = BLOCKING_GET_PENALTY,
                maxPoints = 10,
                details = "Uses Blocking.get()"
            ))
            totalScore += BLOCKING_GET_PENALTY
            migrationNotes.add("Convert Blocking.get() calls to coroutine context switches")
        }

        if (promiseAnalysis.usesFork) {
            factors.add(ComplexityFactor(
                name = "Execution.fork() Usage",
                description = "Fork patterns need careful coroutine scope handling",
                points = EXECUTION_FORK_PENALTY,
                maxPoints = 10,
                details = "Uses Execution.fork()"
            ))
            totalScore += EXECUTION_FORK_PENALTY
            migrationNotes.add("Replace Execution.fork() with coroutine launch/async")
        }

        if (promiseAnalysis.usesParallelBatch) {
            factors.add(ComplexityFactor(
                name = "ParallelBatch Usage",
                description = "Parallel patterns need async/awaitAll conversion",
                points = PARALLEL_BATCH_PENALTY,
                maxPoints = 10,
                details = "Uses ParallelBatch"
            ))
            totalScore += PARALLEL_BATCH_PENALTY
            migrationNotes.add("Replace ParallelBatch with coroutineScope { listOf(...).map { async { } } }")
        }

        // Chain depth
        val chainDepthPenalty = minOf(
            (promiseAnalysis.maxChainDepth - 1) * CHAIN_DEPTH_PENALTY_PER_LEVEL,
            CHAIN_DEPTH_MAX_PENALTY
        ).coerceAtLeast(0)
        if (chainDepthPenalty > 0) {
            factors.add(ComplexityFactor(
                name = "Promise Chain Depth",
                description = "Deeper chains are harder to flatten",
                points = chainDepthPenalty,
                maxPoints = 10,
                details = "Max chain depth: ${promiseAnalysis.maxChainDepth}"
            ))
            totalScore += chainDepthPenalty
        }

        // === Anti-pattern Factors ===

        if (promiseAnalysis.usesBlocking && promiseAnalysis.usesAsync) {
            factors.add(ComplexityFactor(
                name = "Mixed Blocking/Async",
                description = "Mixing patterns complicates migration",
                points = MIXED_BLOCKING_ASYNC_PENALTY,
                maxPoints = 5,
                details = "Uses both Blocking.get() and Promise async"
            ))
            totalScore += MIXED_BLOCKING_ASYNC_PENALTY
            migrationNotes.add("Analyze data flow to unify blocking and async patterns")
        }

        if (promiseAnalysis.promiseReturningMethods.size > 3) {
            factors.add(ComplexityFactor(
                name = "Multiple Promise Chains",
                description = "Many Promise-returning methods increase complexity",
                points = MULTIPLE_PROMISE_CHAINS_PENALTY,
                maxPoints = 5,
                details = "${promiseAnalysis.promiseReturningMethods.size} Promise-returning methods"
            ))
            totalScore += MULTIPLE_PROMISE_CHAINS_PENALTY
        }

        // Cap at MAX_SCORE
        totalScore = minOf(totalScore, MAX_SCORE)

        // Find dependencies (other handlers this depends on)
        val (outgoing, _) = classGraphProvider.getDependencies(fqn, includeLibraries = false)
        val blockedBy = outgoing
            .filter { isHandlerClass(it.classFqn) }
            .map { it.classFqn }

        return ComplexityResult(
            classFqn = fqn,
            score = totalScore,
            tier = scoreTier(totalScore),
            estimatedHours = estimateHours(totalScore),
            factors = factors,
            migrationNotes = migrationNotes,
            migrationPriority = calculatePriority(totalScore, blockedBy.size),
            blockedBy = blockedBy
        )
    }

    /**
     * Get project-wide complexity summary.
     */
    fun getProjectSummary(handlers: List<HandlerSummary>): ComplexitySummary {
        val complexities = handlers.mapNotNull {
            calculate(it.fqn).takeIf { c -> c.score > 0 }
        }

        val tierBreakdown = ComplexityTier.entries.associateWith { tier ->
            complexities.count { it.tier == tier }
        }

        val totalHours = complexities.sumOf { it.estimatedHours }
        val avgScore = if (complexities.isNotEmpty())
            complexities.map { it.score }.average()
        else 0.0

        // Build migration order - simple handlers with no dependencies first
        val migrationOrder = buildMigrationOrder(complexities)

        return ComplexitySummary(
            totalHandlers = handlers.size,
            tierBreakdown = tierBreakdown,
            totalEstimatedHours = totalHours,
            averageScore = avgScore,
            migrationOrder = migrationOrder
        )
    }

    /**
     * Build suggested migration order.
     *
     * Order Strategy:
     * 1. Handlers with no dependencies on other handlers (leaf nodes)
     * 2. Lower complexity handlers first within each group
     * 3. Handlers that many others depend on (infrastructure) early
     */
    private fun buildMigrationOrder(complexities: List<ComplexityResult>): List<MigrationOrderItem> {
        // Sort by: blockedBy count ASC, then score ASC
        val sorted = complexities.sortedWith(
            compareBy({ it.blockedBy.size }, { it.score })
        )

        return sorted.mapIndexed { index, result ->
            val reason = when {
                result.blockedBy.isEmpty() && result.tier == ComplexityTier.LOW ->
                    "No dependencies, low complexity - ideal starting point"
                result.blockedBy.isEmpty() ->
                    "No dependencies"
                result.tier == ComplexityTier.LOW ->
                    "Low complexity"
                else ->
                    "Blocked by ${result.blockedBy.size} handlers"
            }

            MigrationOrderItem(
                classFqn = result.classFqn,
                simpleName = result.classFqn.substringAfterLast('.'),
                tier = result.tier,
                estimatedHours = result.estimatedHours,
                order = index + 1,
                reason = reason
            )
        }
    }

    private fun calculatePriority(score: Int, blockedByCount: Int): Int {
        // Lower priority number = should migrate first
        // Penalize by complexity and dependencies
        return score + (blockedByCount * 20)
    }

    private fun determineHandlerType(classInfo: ClassInfo): HandlerType {
        return when {
            classInfo.interfaces.contains(RatpackTypes.HANDLER) -> HandlerType.HANDLER
            classInfo.superclass == RatpackTypes.GROOVY_HANDLER -> HandlerType.GROOVY_HANDLER
            classInfo.methods.any { m ->
                m.name == "execute" && m.parameters.any { it.type.contains("Chain") }
            } -> HandlerType.CHAIN_ACTION
            else -> HandlerType.INLINE_HANDLER
        }
    }

    private fun isHandlerClass(fqn: String): Boolean {
        val classInfo = classGraphProvider.getClass(fqn) ?: return false
        return classInfo.interfaces.contains(RatpackTypes.HANDLER) ||
               classInfo.superclass == RatpackTypes.GROOVY_HANDLER
    }

    private fun emptyComplexityResult(fqn: String) = ComplexityResult(
        classFqn = fqn,
        score = 0,
        tier = ComplexityTier.LOW,
        estimatedHours = 0.0,
        factors = emptyList(),
        migrationNotes = emptyList(),
        migrationPriority = 0,
        blockedBy = emptyList()
    )
}
```

---

## Feature 4: Guice DI Analysis

#### File: `server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/GuiceModuleDetector.kt`

```kotlin
package codelens.classgraph.ratpack

import codelens.classgraph.ClassGraphProvider
import codelens.core.model.*
import codelens.core.model.ratpack.*
import org.slf4j.LoggerFactory

/**
 * Detects and analyzes Guice modules in the codebase.
 */
class GuiceModuleDetector(
    private val classGraphProvider: ClassGraphProvider
) {
    private val logger = LoggerFactory.getLogger(GuiceModuleDetector::class.java)

    /**
     * Find all Guice modules in the project.
     */
    fun findAllModules(): List<GuiceModuleSummary> {
        val modules = mutableListOf<GuiceModuleSummary>()

        // Find AbstractModule subclasses
        val (abstractDirect, abstractIndirect) = classGraphProvider.getImplementations(
            RatpackTypes.ABSTRACT_MODULE,
            includeLibraries = false
        )

        (abstractDirect + abstractIndirect).forEach { classSummary ->
            classGraphProvider.getClass(classSummary.fqn)?.let { classInfo ->
                modules.add(buildModuleSummary(classInfo, GuiceModuleType.ABSTRACT_MODULE))
            }
        }

        // Find ConfigurableModule subclasses
        val (configDirect, configIndirect) = classGraphProvider.getImplementations(
            RatpackTypes.CONFIGURABLE_MODULE,
            includeLibraries = false
        )

        (configDirect + configIndirect).forEach { classSummary ->
            // Avoid duplicates
            if (modules.none { it.fqn == classSummary.fqn }) {
                classGraphProvider.getClass(classSummary.fqn)?.let { classInfo ->
                    modules.add(buildModuleSummary(classInfo, GuiceModuleType.CONFIGURABLE_MODULE))
                }
            }
        }

        // Find classes with @Provides methods that aren't already modules
        findProviderClasses().forEach { classInfo ->
            if (modules.none { it.fqn == classInfo.name.fqn }) {
                modules.add(buildModuleSummary(classInfo, GuiceModuleType.PROVIDER_CLASS))
            }
        }

        logger.info("Found ${modules.size} Guice modules")
        return modules.sortedBy { it.fqn }
    }

    /**
     * Get detailed module information.
     */
    fun getModuleDetail(fqn: String): GuiceModuleInfo? {
        val classInfo = classGraphProvider.getClass(fqn) ?: return null

        val moduleType = determineModuleType(classInfo)
        val bindings = extractBindings(classInfo)
        val providesMethods = extractProvidesMethods(classInfo)
        val configType = extractConfigType(classInfo)
        val installedModules = extractInstalledModules(classInfo)

        return GuiceModuleInfo(
            fqn = classInfo.name.fqn,
            simpleName = classInfo.name.simpleName,
            packageName = classInfo.name.packageName,
            moduleType = moduleType,
            configType = configType,
            bindings = bindings,
            providesMethods = providesMethods,
            installedModules = installedModules
        )
    }

    /**
     * Find all bindings for a specific type across all modules.
     */
    fun findBindingsForType(typeFqn: String): List<Pair<String, GuiceBinding>> {
        val results = mutableListOf<Pair<String, GuiceBinding>>()

        findAllModules().forEach { moduleSummary ->
            getModuleDetail(moduleSummary.fqn)?.bindings?.forEach { binding ->
                if (binding.boundType == typeFqn) {
                    results.add(moduleSummary.fqn to binding)
                }
            }
        }

        return results
    }

    /**
     * Find classes that have @Provides methods but don't extend Module.
     */
    private fun findProviderClasses(): List<ClassInfo> {
        return classGraphProvider.listClasses(ClassFilter(includeLibraries = false))
            .mapNotNull { summary ->
                classGraphProvider.getClass(summary.fqn)?.let { classInfo ->
                    val hasProvides = classInfo.methods.any { method ->
                        method.annotations.any { ann ->
                            ann.type == RatpackTypes.PROVIDES ||
                            ann.type == RatpackTypes.PROVIDES_INTO_SET ||
                            ann.type == RatpackTypes.PROVIDES_INTO_MAP
                        }
                    }
                    if (hasProvides) classInfo else null
                }
            }
    }

    /**
     * Determine the module type.
     */
    private fun determineModuleType(classInfo: ClassInfo): GuiceModuleType {
        return when {
            classInfo.superclass?.contains("ConfigurableModule") == true ||
            classInfo.interfaces.any { it.contains("ConfigurableModule") } ->
                GuiceModuleType.CONFIGURABLE_MODULE

            classInfo.superclass?.contains("AbstractModule") == true ||
            classInfo.interfaces.any { it.contains("Module") } ->
                GuiceModuleType.ABSTRACT_MODULE

            else -> GuiceModuleType.PROVIDER_CLASS
        }
    }

    /**
     * Extract bindings from module.
     *
     * NOTE: This is limited because we can't see method bodies.
     * We can only detect @Provides methods, not bind() calls.
     */
    private fun extractBindings(classInfo: ClassInfo): List<GuiceBinding> {
        val bindings = mutableListOf<GuiceBinding>()

        // Extract from @Provides methods
        classInfo.methods.forEach { method ->
            val providesAnn = method.annotations.find { it.type == RatpackTypes.PROVIDES }
            val intoSetAnn = method.annotations.find { it.type == RatpackTypes.PROVIDES_INTO_SET }
            val intoMapAnn = method.annotations.find { it.type == RatpackTypes.PROVIDES_INTO_MAP }

            val scope = method.annotations.find { ann ->
                RatpackTypes.SCOPE_ANNOTATIONS.contains(ann.type)
            }?.type?.substringAfterLast('.')

            when {
                providesAnn != null -> bindings.add(GuiceBinding(
                    boundType = method.returnType,
                    toType = null,
                    scope = scope,
                    isMultibinding = false,
                    bindingSource = BindingSource.PROVIDES
                ))
                intoSetAnn != null -> bindings.add(GuiceBinding(
                    boundType = method.returnType,
                    toType = null,
                    scope = scope,
                    isMultibinding = true,
                    bindingSource = BindingSource.PROVIDES_INTO_SET
                ))
                intoMapAnn != null -> bindings.add(GuiceBinding(
                    boundType = method.returnType,
                    toType = null,
                    scope = scope,
                    isMultibinding = true,
                    bindingSource = BindingSource.PROVIDES_INTO_MAP
                ))
            }
        }

        return bindings
    }

    /**
     * Extract @Provides method information.
     */
    private fun extractProvidesMethods(classInfo: ClassInfo): List<ProvidesMethodInfo> {
        return classInfo.methods
            .filter { method ->
                method.annotations.any { ann ->
                    ann.type == RatpackTypes.PROVIDES ||
                    ann.type == RatpackTypes.PROVIDES_INTO_SET ||
                    ann.type == RatpackTypes.PROVIDES_INTO_MAP
                }
            }
            .map { method ->
                val scope = method.annotations.find { ann ->
                    RatpackTypes.SCOPE_ANNOTATIONS.contains(ann.type)
                }?.type?.substringAfterLast('.')

                val intoSet = method.annotations.any { it.type == RatpackTypes.PROVIDES_INTO_SET }
                val intoMap = method.annotations.any { it.type == RatpackTypes.PROVIDES_INTO_MAP }

                ProvidesMethodInfo(
                    methodName = method.name,
                    providesType = method.returnType,
                    scope = scope,
                    intoSet = intoSet,
                    intoMap = intoMap,
                    dependencies = method.parameters.map { it.type }
                )
            }
    }

    /**
     * Extract configuration type for ConfigurableModule.
     */
    private fun extractConfigType(classInfo: ClassInfo): String? {
        // Look for getConfigType() method or generic type parameter
        val configMethod = classInfo.methods.find { it.name == "getConfigType" }
        return configMethod?.returnType?.let {
            // Extract from Class<X> return type
            if (it.contains("<") && it.contains(">")) {
                it.substringAfter("<").substringBefore(">")
            } else null
        }
    }

    /**
     * Find modules installed by this module.
     *
     * NOTE: Limited because we can't see install() calls in method bodies.
     * We can only infer from field types and constructor parameters.
     */
    private fun extractInstalledModules(classInfo: ClassInfo): List<String> {
        val moduleTypes = mutableListOf<String>()

        // Check fields for module types
        classInfo.fields
            .filter { field ->
                field.type.contains("Module") ||
                field.type.endsWith("Provider")
            }
            .forEach { field ->
                moduleTypes.add(field.type)
            }

        // Check constructor parameters for module types
        classInfo.methods
            .find { it.name == "<init>" }
            ?.parameters
            ?.filter { it.type.contains("Module") }
            ?.forEach { param ->
                moduleTypes.add(param.type)
            }

        return moduleTypes.distinct()
    }

    private fun buildModuleSummary(classInfo: ClassInfo, moduleType: GuiceModuleType): GuiceModuleSummary {
        val providesMethods = classInfo.methods.count { method ->
            method.annotations.any { ann ->
                ann.type == RatpackTypes.PROVIDES ||
                ann.type == RatpackTypes.PROVIDES_INTO_SET ||
                ann.type == RatpackTypes.PROVIDES_INTO_MAP
            }
        }

        return GuiceModuleSummary(
            fqn = classInfo.name.fqn,
            simpleName = classInfo.name.simpleName,
            packageName = classInfo.name.packageName,
            moduleType = moduleType,
            bindingCount = providesMethods, // Limited to what we can detect
            providesMethodCount = providesMethods
        )
    }
}
```

---

## API Endpoints

### File: `server/app/src/main/kotlin/codelens/server/routes/RatpackRoutes.kt`

```kotlin
package codelens.server.routes

import codelens.core.model.*
import codelens.core.model.ratpack.*
import codelens.server.services.RatpackAnalysisService
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Routes for Ratpack-specific analysis endpoints.
 */
fun Route.ratpackRoutes(ratpackService: RatpackAnalysisService) {
    route("/api/v1/ratpack") {

        // ========== Handler Endpoints ==========

        /**
         * GET /api/v1/ratpack/handlers
         * List all handlers with optional filtering.
         *
         * Query Parameters:
         * - type: Filter by handler type (HANDLER, CHAIN_ACTION, GROOVY_HANDLER)
         * - minComplexity: Minimum complexity score (0-100)
         * - maxComplexity: Maximum complexity score (0-100)
         * - tier: Filter by complexity tier (LOW, MEDIUM, HIGH, CRITICAL)
         * - package: Filter by package pattern
         * - usesBlocking: Filter for handlers using Blocking.get() (true/false)
         * - page: Page number (0-based)
         * - size: Page size (default 50)
         */
        get("/handlers") {
            val typeFilter = call.request.queryParameters["type"]
                ?.let { HandlerType.valueOf(it) }
            val minComplexity = call.request.queryParameters["minComplexity"]?.toIntOrNull()
            val maxComplexity = call.request.queryParameters["maxComplexity"]?.toIntOrNull()
            val tierFilter = call.request.queryParameters["tier"]
                ?.let { ComplexityTier.valueOf(it) }
            val packageFilter = call.request.queryParameters["package"]
            val usesBlocking = call.request.queryParameters["usesBlocking"]?.toBoolean()
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
            val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 50

            val handlers = ratpackService.listHandlers(
                typeFilter = typeFilter,
                minComplexity = minComplexity,
                maxComplexity = maxComplexity,
                tierFilter = tierFilter,
                packageFilter = packageFilter,
                usesBlocking = usesBlocking
            )

            // Paginate
            val totalCount = handlers.size
            val totalPages = if (totalCount == 0) 1 else (totalCount + size - 1) / size
            val startIndex = page * size
            val endIndex = minOf(startIndex + size, totalCount)
            val pagedHandlers = if (startIndex < totalCount) {
                handlers.subList(startIndex, endIndex)
            } else emptyList()

            call.respond(HandlersListResponse(
                handlers = pagedHandlers,
                totalCount = totalCount,
                page = page,
                pageSize = size,
                totalPages = totalPages
            ))
        }

        /**
         * GET /api/v1/ratpack/handlers/{fqn}
         * Get detailed handler information.
         */
        get("/handlers/{fqn...}") {
            val fqn = call.parameters.getAll("fqn")?.joinToString(".")
            if (fqn.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(
                    code = 400,
                    type = "BadRequest",
                    message = "Handler FQN is required"
                ))
                return@get
            }

            val handler = ratpackService.getHandlerDetail(fqn)
            if (handler != null) {
                call.respond(HandlerDetailResponse(handler = handler))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(
                    code = 404,
                    type = "NotFound",
                    message = "Handler not found: $fqn"
                ))
            }
        }

        // ========== Promise Endpoints ==========

        /**
         * GET /api/v1/ratpack/promises
         * Get project-wide Promise usage summary.
         */
        get("/promises") {
            val summary = ratpackService.getPromiseSummary()
            call.respond(summary)
        }

        /**
         * GET /api/v1/ratpack/promises/{fqn}
         * Get Promise usage for a specific class.
         */
        get("/promises/{fqn...}") {
            val fqn = call.parameters.getAll("fqn")?.joinToString(".")
            if (fqn.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(
                    code = 400,
                    type = "BadRequest",
                    message = "Class FQN is required"
                ))
                return@get
            }

            val analysis = ratpackService.getPromiseAnalysis(fqn)
            call.respond(analysis)
        }

        /**
         * GET /api/v1/ratpack/promises/search
         * Search for classes by Promise usage criteria.
         *
         * Query Parameters:
         * - hasBlocking: Classes using Blocking.get()
         * - hasAsync: Classes with Promise-returning methods
         * - hasFork: Classes using Execution.fork()
         * - minChainDepth: Minimum Promise chain depth
         */
        get("/promises/search") {
            val hasBlocking = call.request.queryParameters["hasBlocking"]?.toBoolean()
            val hasAsync = call.request.queryParameters["hasAsync"]?.toBoolean()
            val hasFork = call.request.queryParameters["hasFork"]?.toBoolean()
            val minChainDepth = call.request.queryParameters["minChainDepth"]?.toIntOrNull()

            val results = ratpackService.searchPromises(
                hasBlocking = hasBlocking,
                hasAsync = hasAsync,
                hasFork = hasFork,
                minChainDepth = minChainDepth
            )

            call.respond(PromiseSearchResponse(
                results = results,
                totalCount = results.size
            ))
        }

        // ========== Complexity Endpoints ==========

        /**
         * GET /api/v1/ratpack/complexity
         * Get project-wide complexity summary.
         */
        get("/complexity") {
            val summary = ratpackService.getComplexitySummary()
            call.respond(summary)
        }

        /**
         * GET /api/v1/ratpack/complexity/{fqn}
         * Get complexity analysis for a specific handler.
         */
        get("/complexity/{fqn...}") {
            val fqn = call.parameters.getAll("fqn")?.joinToString(".")
            if (fqn.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(
                    code = 400,
                    type = "BadRequest",
                    message = "Handler FQN is required"
                ))
                return@get
            }

            val result = ratpackService.getComplexity(fqn)
            call.respond(result)
        }

        /**
         * GET /api/v1/ratpack/migration-order
         * Get suggested migration order for all handlers.
         */
        get("/migration-order") {
            val order = ratpackService.getMigrationOrder()
            call.respond(MigrationOrderResponse(
                order = order,
                totalHandlers = order.size
            ))
        }

        // ========== Guice Endpoints ==========

        /**
         * GET /api/v1/ratpack/modules
         * List all Guice modules.
         */
        get("/modules") {
            val modules = ratpackService.listModules()
            call.respond(ModulesListResponse(
                modules = modules,
                totalCount = modules.size
            ))
        }

        /**
         * GET /api/v1/ratpack/modules/{fqn}
         * Get detailed module information.
         */
        get("/modules/{fqn...}") {
            val fqn = call.parameters.getAll("fqn")?.joinToString(".")
            if (fqn.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(
                    code = 400,
                    type = "BadRequest",
                    message = "Module FQN is required"
                ))
                return@get
            }

            val module = ratpackService.getModuleDetail(fqn)
            if (module != null) {
                call.respond(ModuleDetailResponse(module = module))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(
                    code = 404,
                    type = "NotFound",
                    message = "Module not found: $fqn"
                ))
            }
        }

        /**
         * GET /api/v1/ratpack/modules/binding/{type}
         * Find bindings for a specific type.
         */
        get("/modules/binding/{type...}") {
            val typeFqn = call.parameters.getAll("type")?.joinToString(".")
            if (typeFqn.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(
                    code = 400,
                    type = "BadRequest",
                    message = "Type FQN is required"
                ))
                return@get
            }

            val bindings = ratpackService.findBindingsForType(typeFqn)
            call.respond(BindingSearchResponse(
                typeFqn = typeFqn,
                bindings = bindings,
                totalCount = bindings.size
            ))
        }
    }
}

// Response models for the routes
@kotlinx.serialization.Serializable
data class HandlersListResponse(
    val handlers: List<HandlerSummary>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int
)

@kotlinx.serialization.Serializable
data class HandlerDetailResponse(
    val handler: HandlerInfo
)

@kotlinx.serialization.Serializable
data class PromiseSearchResponse(
    val results: List<PromiseUsageInfo>,
    val totalCount: Int
)

@kotlinx.serialization.Serializable
data class MigrationOrderResponse(
    val order: List<MigrationOrderItem>,
    val totalHandlers: Int
)

@kotlinx.serialization.Serializable
data class ModulesListResponse(
    val modules: List<GuiceModuleSummary>,
    val totalCount: Int
)

@kotlinx.serialization.Serializable
data class ModuleDetailResponse(
    val module: GuiceModuleInfo
)

@kotlinx.serialization.Serializable
data class BindingSearchResponse(
    val typeFqn: String,
    val bindings: List<BindingWithModule>,
    val totalCount: Int
)

@kotlinx.serialization.Serializable
data class BindingWithModule(
    val moduleFqn: String,
    val binding: GuiceBinding
)
```

---

## CLI Commands

### File: `cli/src/codelens_cli/commands/handlers.py`

```python
"""Handler analysis commands."""

from typing import Optional

import typer

from codelens_cli.commands.common import ensure_server_running, get_client, handle_api_errors
from codelens_cli.output import is_tty, print_json
from rich.console import Console
from rich.table import Table

app = typer.Typer(
    name="handlers",
    help="Analyze Ratpack handlers in the codebase.",
    no_args_is_help=True,
)

console = Console()


def _tier_style(tier: str) -> str:
    """Get Rich style for complexity tier."""
    return {
        "LOW": "[green]LOW[/green]",
        "MEDIUM": "[yellow]MEDIUM[/yellow]",
        "HIGH": "[red]HIGH[/red]",
        "CRITICAL": "[bold red]CRITICAL[/bold red]",
    }.get(tier, tier)


@app.command(name="list")
def list_handlers(
    project: Optional[str] = typer.Option(None, "--project", "-p", help="Project directory"),
    handler_type: Optional[str] = typer.Option(None, "--type", "-t", help="Filter by type: HANDLER, CHAIN_ACTION, GROOVY_HANDLER"),
    tier: Optional[str] = typer.Option(None, "--tier", help="Filter by complexity tier: LOW, MEDIUM, HIGH, CRITICAL"),
    min_complexity: Optional[int] = typer.Option(None, "--min-complexity", help="Minimum complexity score (0-100)"),
    max_complexity: Optional[int] = typer.Option(None, "--max-complexity", help="Maximum complexity score (0-100)"),
    package: Optional[str] = typer.Option(None, "--package", help="Filter by package pattern"),
    uses_blocking: Optional[bool] = typer.Option(None, "--uses-blocking", help="Filter for handlers using Blocking.get()"),
    page: int = typer.Option(0, "--page", help="Page number (0-based)"),
    size: int = typer.Option(50, "--size", help="Page size"),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """List all Ratpack handlers in the codebase."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        params = {"page": page, "size": size}
        if handler_type:
            params["type"] = handler_type
        if tier:
            params["tier"] = tier
        if min_complexity is not None:
            params["minComplexity"] = min_complexity
        if max_complexity is not None:
            params["maxComplexity"] = max_complexity
        if package:
            params["package"] = package
        if uses_blocking is not None:
            params["usesBlocking"] = str(uses_blocking).lower()

        result = client._get("/api/v1/ratpack/handlers", params=params)

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_handlers_list(result)


def _print_handlers_list(response: dict) -> None:
    """Print handlers list in table format."""
    handlers = response.get("handlers", [])
    total = response.get("totalCount", 0)
    page = response.get("page", 0)
    page_size = response.get("pageSize", 50)
    total_pages = response.get("totalPages", 1)

    if total == 0:
        console.print("[yellow]No handlers found.[/yellow]")
        return

    start = page * page_size + 1
    end = start + len(handlers) - 1

    console.print(f"\n[bold]Handlers[/bold] ({start}-{end} of {total})")
    console.print()

    table = Table(show_header=True, header_style="bold")
    table.add_column("Handler", style="cyan")
    table.add_column("Type", justify="center")
    table.add_column("Score", justify="right")
    table.add_column("Tier", justify="center")
    table.add_column("Promise Ops", justify="right")
    table.add_column("Blocking", justify="center")

    for h in handlers:
        blocking_str = "[red]Yes[/red]" if h.get("usesBlocking") else "[dim]No[/dim]"
        table.add_row(
            h["simpleName"],
            h["handlerType"],
            str(h["complexityScore"]),
            _tier_style(h["complexityTier"]),
            str(h["promiseOperationCount"]),
            blocking_str,
        )

    console.print(table)

    if total_pages > 1:
        console.print(f"\nPage {page + 1} of {total_pages}. Use --page to navigate.")
    console.print()


@app.command(name="show")
def show_handler(
    fqn: str = typer.Argument(help="Fully qualified handler class name"),
    project: Optional[str] = typer.Option(None, "--project", "-p", help="Project directory"),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show detailed information about a specific handler."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client._get(f"/api/v1/ratpack/handlers/{fqn}")

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_handler_detail(result.get("handler", {}))


def _print_handler_detail(handler: dict) -> None:
    """Print detailed handler information."""
    console.print(f"\n[bold cyan]{handler.get('fqn', 'Unknown')}[/bold cyan]")
    console.print()

    # Basic info
    info_table = Table(show_header=False, box=None, padding=(0, 2))
    info_table.add_column("Key", style="dim")
    info_table.add_column("Value")

    info_table.add_row("Package:", handler.get("packageName", ""))
    info_table.add_row("Type:", handler.get("handlerType", ""))
    if handler.get("superclass"):
        info_table.add_row("Extends:", handler["superclass"])
    if handler.get("interfaces"):
        info_table.add_row("Implements:", ", ".join(handler["interfaces"]))

    console.print(info_table)

    # Complexity
    complexity = handler.get("complexity", {})
    console.print(f"\n[bold]Complexity Analysis[/bold]")
    console.print(f"  Score: {complexity.get('score', 0)} ({_tier_style(complexity.get('tier', 'LOW'))})")
    console.print(f"  Estimated Hours: {complexity.get('estimatedHours', 0):.1f}")

    factors = complexity.get("factors", [])
    if factors:
        console.print("\n  [dim]Contributing Factors:[/dim]")
        for factor in factors:
            console.print(f"    - {factor['name']}: +{factor['points']} ({factor['details']})")

    notes = complexity.get("migrationNotes", [])
    if notes:
        console.print("\n  [dim]Migration Notes:[/dim]")
        for note in notes:
            console.print(f"    - {note}")

    # Promise usage
    promise = handler.get("promiseAnalysis", {})
    if promise.get("totalOperationCount", 0) > 0:
        console.print(f"\n[bold]Promise Usage[/bold]")
        console.print(f"  Total Operations: {promise.get('totalOperationCount', 0)}")
        console.print(f"  Max Chain Depth: {promise.get('maxChainDepth', 0)}")
        if promise.get("usesBlocking"):
            console.print("  Uses Blocking.get(): [red]Yes[/red]")
        if promise.get("usesFork"):
            console.print("  Uses Execution.fork(): [yellow]Yes[/yellow]")
        if promise.get("usesParallelBatch"):
            console.print("  Uses ParallelBatch: [yellow]Yes[/yellow]")

    # Injected dependencies
    deps = handler.get("injectedDependencies", [])
    if deps:
        console.print(f"\n[bold]Injected Dependencies ({len(deps)})[/bold]")
        for dep in deps:
            console.print(f"  - {dep['name']}: {dep['typeFqn']} ({dep['injectionType']})")

    console.print()
```

### File: `cli/src/codelens_cli/commands/migration.py`

```python
"""Migration complexity commands."""

from typing import Optional

import typer

from codelens_cli.commands.common import ensure_server_running, get_client, handle_api_errors
from codelens_cli.output import is_tty, print_json
from rich.console import Console
from rich.table import Table
from rich.panel import Panel

app = typer.Typer(
    name="migration",
    help="Analyze migration complexity and planning.",
    no_args_is_help=True,
)

console = Console()


def _tier_style(tier: str) -> str:
    """Get Rich style for complexity tier."""
    return {
        "LOW": "[green]LOW[/green]",
        "MEDIUM": "[yellow]MEDIUM[/yellow]",
        "HIGH": "[red]HIGH[/red]",
        "CRITICAL": "[bold red]CRITICAL[/bold red]",
    }.get(tier, tier)


@app.command(name="complexity")
def show_complexity(
    project: Optional[str] = typer.Option(None, "--project", "-p", help="Project directory"),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show project-wide complexity summary."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client._get("/api/v1/ratpack/complexity")

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_complexity_summary(result)


def _print_complexity_summary(summary: dict) -> None:
    """Print complexity summary."""
    console.print("\n[bold]Migration Complexity Summary[/bold]")
    console.print()

    # Overview stats
    stats_table = Table(show_header=False, box=None, padding=(0, 2))
    stats_table.add_column("Key", style="dim")
    stats_table.add_column("Value")

    stats_table.add_row("Total Handlers:", str(summary.get("totalHandlers", 0)))
    stats_table.add_row("Average Score:", f"{summary.get('averageScore', 0):.1f}")
    stats_table.add_row("Total Estimated Hours:", f"{summary.get('totalEstimatedHours', 0):.1f}")

    console.print(stats_table)

    # Tier breakdown
    console.print("\n[bold]By Complexity Tier:[/bold]")
    tier_breakdown = summary.get("tierBreakdown", {})
    for tier in ["LOW", "MEDIUM", "HIGH", "CRITICAL"]:
        count = tier_breakdown.get(tier, 0)
        if count > 0:
            console.print(f"  {_tier_style(tier)}: {count} handlers")

    console.print()


@app.command(name="order")
def show_migration_order(
    project: Optional[str] = typer.Option(None, "--project", "-p", help="Project directory"),
    limit: int = typer.Option(20, "--limit", "-n", help="Number of handlers to show"),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Show suggested migration order for handlers."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client._get("/api/v1/ratpack/migration-order")

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_migration_order(result, limit)


def _print_migration_order(response: dict, limit: int) -> None:
    """Print migration order."""
    order = response.get("order", [])
    total = response.get("totalHandlers", 0)

    console.print(f"\n[bold]Suggested Migration Order[/bold] (showing {min(limit, len(order))} of {total})")
    console.print()

    table = Table(show_header=True, header_style="bold")
    table.add_column("#", justify="right", style="dim")
    table.add_column("Handler", style="cyan")
    table.add_column("Tier", justify="center")
    table.add_column("Hours", justify="right")
    table.add_column("Reason")

    for item in order[:limit]:
        table.add_row(
            str(item.get("order", 0)),
            item.get("simpleName", ""),
            _tier_style(item.get("tier", "LOW")),
            f"{item.get('estimatedHours', 0):.1f}",
            item.get("reason", ""),
        )

    console.print(table)
    console.print()


@app.command(name="analyze")
def analyze_handler(
    fqn: str = typer.Argument(help="Fully qualified handler class name"),
    project: Optional[str] = typer.Option(None, "--project", "-p", help="Project directory"),
    json_output: bool = typer.Option(False, "--json", help="Output as JSON"),
) -> None:
    """Get detailed complexity analysis for a specific handler."""
    server, project_path = ensure_server_running(project, json_output)
    client = get_client(server)

    with handle_api_errors():
        result = client._get(f"/api/v1/ratpack/complexity/{fqn}")

        if json_output or not is_tty():
            print_json(result)
        else:
            _print_complexity_detail(result)


def _print_complexity_detail(result: dict) -> None:
    """Print detailed complexity breakdown."""
    console.print(f"\n[bold cyan]{result.get('classFqn', 'Unknown')}[/bold cyan]")
    console.print()

    score = result.get("score", 0)
    tier = result.get("tier", "LOW")
    hours = result.get("estimatedHours", 0)

    # Summary panel
    summary = f"Score: {score}/100 | Tier: {tier} | Est. Hours: {hours:.1f}"
    console.print(Panel(summary, title="Complexity Score"))

    # Factors breakdown
    factors = result.get("factors", [])
    if factors:
        console.print("\n[bold]Contributing Factors:[/bold]")
        factor_table = Table(show_header=True, header_style="bold")
        factor_table.add_column("Factor")
        factor_table.add_column("Points", justify="right")
        factor_table.add_column("Max", justify="right")
        factor_table.add_column("Details")

        for factor in factors:
            factor_table.add_row(
                factor["name"],
                str(factor["points"]),
                str(factor["maxPoints"]),
                factor["details"],
            )

        console.print(factor_table)

    # Migration notes
    notes = result.get("migrationNotes", [])
    if notes:
        console.print("\n[bold]Migration Notes:[/bold]")
        for note in notes:
            console.print(f"  [yellow]![/yellow] {note}")

    # Blocked by
    blocked_by = result.get("blockedBy", [])
    if blocked_by:
        console.print("\n[bold]Blocked By:[/bold]")
        console.print("  Migrate these handlers first:")
        for dep in blocked_by:
            console.print(f"    - {dep}")

    console.print()
```

### CLI Models to Add

Add to `cli/src/codelens_cli/models.py`:

```python
# ============================================================================
# Ratpack Models
# ============================================================================

class HandlerType(str, Enum):
    """Classification of Ratpack handler types."""
    HANDLER = "HANDLER"
    CHAIN_ACTION = "CHAIN_ACTION"
    INLINE_HANDLER = "INLINE_HANDLER"
    GROOVY_HANDLER = "GROOVY_HANDLER"


class ComplexityTier(str, Enum):
    """Complexity tier classification."""
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


class HandlerSummary(BaseModel):
    """Summary info about a handler."""
    fqn: str
    simple_name: str = Field(alias="simpleName")
    package_name: str = Field(alias="packageName")
    handler_type: HandlerType = Field(alias="handlerType")
    source: ClassSource
    complexity_score: int = Field(alias="complexityScore")
    complexity_tier: ComplexityTier = Field(alias="complexityTier")
    promise_operation_count: int = Field(alias="promiseOperationCount")
    uses_blocking: bool = Field(alias="usesBlocking")

    class Config:
        populate_by_name = True


class HandlersListResponse(BaseModel):
    """Response for handlers list endpoint."""
    handlers: list[HandlerSummary]
    total_count: int = Field(alias="totalCount")
    page: int
    page_size: int = Field(alias="pageSize")
    total_pages: int = Field(alias="totalPages")

    class Config:
        populate_by_name = True
```

---

## Testing Specifications

### Test Fixtures to Create

Expand `test-fixtures/sample-ratpack-app/src/main/java/` with:

```
sample/
├── handlers/
│   ├── SimpleHandler.java          # Basic Handler implementation
│   ├── ComplexHandler.java         # Handler with many dependencies
│   ├── BlockingHandler.java        # Uses Blocking.get()
│   ├── AsyncHandler.java           # Uses Promise chains
│   └── ChainConfigAction.java      # Implements Action<Chain>
├── promises/
│   ├── SimpleBlockingService.java  # Single Blocking.get()
│   ├── ChainedPromiseService.java  # Multi-level Promise chain
│   └── ParallelService.java        # Uses ParallelBatch
└── modules/
    ├── AppModule.java              # Standard AbstractModule
    └── ConfigModule.java           # ConfigurableModule example
```

### Sample Test Fixture: SimpleHandler.java

```java
package sample.handlers;

import ratpack.handling.Context;
import ratpack.handling.Handler;

public class SimpleHandler implements Handler {
    @Override
    public void handle(Context ctx) throws Exception {
        ctx.render("Hello, World!");
    }
}
```

### Sample Test Fixture: BlockingHandler.java

```java
package sample.handlers;

import ratpack.handling.Context;
import ratpack.handling.Handler;
import ratpack.exec.Blocking;

import javax.inject.Inject;

public class BlockingHandler implements Handler {
    private final DatabaseService dbService;

    @Inject
    public BlockingHandler(DatabaseService dbService) {
        this.dbService = dbService;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        Blocking.get(() -> dbService.fetchData(ctx.getPathTokens().get("id")))
            .map(data -> data.toUpperCase())
            .then(result -> ctx.render(result));
    }
}
```

### Unit Test: RatpackDetectorTest.kt

```kotlin
package codelens.classgraph.ratpack

import codelens.classgraph.ClassGraphProvider
import codelens.core.model.*
import codelens.core.model.ratpack.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RatpackDetectorTest {

    private lateinit var classGraphProvider: ClassGraphProvider
    private lateinit var promiseDetector: PromiseDetector
    private lateinit var complexityCalculator: ComplexityCalculator
    private lateinit var detector: RatpackDetector

    @BeforeEach
    fun setup() {
        classGraphProvider = mock()
        promiseDetector = mock()
        complexityCalculator = mock()
        detector = RatpackDetector(classGraphProvider, promiseDetector, complexityCalculator)
    }

    @Test
    fun `findAllHandlers should detect Handler implementations`() {
        // Setup mock responses
        val handlerImpl = createClassSummary("com.example.MyHandler")
        whenever(classGraphProvider.getImplementations(RatpackTypes.HANDLER, false))
            .thenReturn(listOf(handlerImpl) to emptyList())
        whenever(classGraphProvider.getImplementations(RatpackTypes.GROOVY_HANDLER, false))
            .thenReturn(emptyList<ClassSummary>() to emptyList())

        val handlerInfo = createClassInfo("com.example.MyHandler",
            interfaces = listOf(RatpackTypes.HANDLER))
        whenever(classGraphProvider.getClass("com.example.MyHandler"))
            .thenReturn(handlerInfo)

        val emptyPromise = PromiseUsageInfo("com.example.MyHandler", emptyList(), 0, false, false, false, false, 0, emptyList())
        whenever(promiseDetector.analyzeClass(any())).thenReturn(emptyPromise)

        val lowComplexity = ComplexityResult("com.example.MyHandler", 10, ComplexityTier.LOW, 1.5, emptyList(), emptyList(), 1, emptyList())
        whenever(complexityCalculator.calculate(any())).thenReturn(lowComplexity)

        // Execute
        val handlers = detector.findAllHandlers()

        // Verify
        assertEquals(1, handlers.size)
        assertEquals("com.example.MyHandler", handlers[0].fqn)
        assertEquals(HandlerType.HANDLER, handlers[0].handlerType)
    }

    @Test
    fun `getHandlerDetail should return complete info`() {
        val fqn = "com.example.MyHandler"

        val classInfo = createClassInfo(fqn,
            interfaces = listOf(RatpackTypes.HANDLER),
            methods = listOf(
                MethodInfo("handle", Visibility.PUBLIC, "void",
                    listOf(ParameterInfo("ctx", "ratpack.handling.Context", emptyList())))
            ))
        whenever(classGraphProvider.getClass(fqn)).thenReturn(classInfo)

        val promiseAnalysis = PromiseUsageInfo(fqn, emptyList(), 0, false, false, false, false, 0, emptyList())
        whenever(promiseDetector.analyzeClass(fqn)).thenReturn(promiseAnalysis)

        val complexity = ComplexityResult(fqn, 15, ComplexityTier.LOW, 1.5, emptyList(), emptyList(), 1, emptyList())
        whenever(complexityCalculator.calculate(fqn)).thenReturn(complexity)

        // Execute
        val detail = detector.getHandlerDetail(fqn)

        // Verify
        assertNotNull(detail)
        assertEquals(fqn, detail.fqn)
        assertEquals(HandlerType.HANDLER, detail.handlerType)
        assertEquals(1, detail.handlerMethods.size)
        assertEquals("handle", detail.handlerMethods[0].name)
    }

    // Test helpers
    private fun createClassSummary(fqn: String): ClassSummary {
        return ClassSummary(
            fqn = fqn,
            simpleName = fqn.substringAfterLast('.'),
            packageName = fqn.substringBeforeLast('.'),
            source = ClassSource.PROJECT,
            isInterface = false,
            isAbstract = false,
            isEnum = false,
            isAnnotation = false,
            methodCount = 1,
            fieldCount = 0
        )
    }

    private fun createClassInfo(
        fqn: String,
        interfaces: List<String> = emptyList(),
        methods: List<MethodInfo> = emptyList()
    ): ClassInfo {
        return ClassInfo(
            name = ClassName(fqn, fqn.substringAfterLast('.'), fqn.substringBeforeLast('.')),
            source = ClassSource.PROJECT,
            visibility = Visibility.PUBLIC,
            interfaces = interfaces,
            methods = methods
        )
    }
}
```

### Unit Test: ComplexityCalculatorTest.kt

```kotlin
package codelens.classgraph.ratpack

import codelens.classgraph.ClassGraphProvider
import codelens.core.model.*
import codelens.core.model.ratpack.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComplexityCalculatorTest {

    @Test
    fun `simple handler should score LOW`() {
        val classGraphProvider = mock<ClassGraphProvider>()
        val promiseDetector = mock<PromiseDetector>()
        val calculator = ComplexityCalculator(classGraphProvider, promiseDetector)

        val fqn = "com.example.SimpleHandler"
        val classInfo = createSimpleHandlerClass(fqn)
        whenever(classGraphProvider.getClass(fqn)).thenReturn(classInfo)
        whenever(classGraphProvider.getDependencies(fqn, false))
            .thenReturn(emptyList<DependencyInfo>() to emptyList())

        val emptyPromise = PromiseUsageInfo(fqn, emptyList(), 0, false, false, false, false, 0, emptyList())
        whenever(promiseDetector.analyzeClass(fqn)).thenReturn(emptyPromise)

        val result = calculator.calculate(fqn)

        assertEquals(ComplexityTier.LOW, result.tier)
        assertTrue(result.score <= 25)
        assertTrue(result.estimatedHours <= 2.0)
    }

    @Test
    fun `handler with Blocking_get should add 10 points`() {
        val classGraphProvider = mock<ClassGraphProvider>()
        val promiseDetector = mock<PromiseDetector>()
        val calculator = ComplexityCalculator(classGraphProvider, promiseDetector)

        val fqn = "com.example.BlockingHandler"
        val classInfo = createSimpleHandlerClass(fqn)
        whenever(classGraphProvider.getClass(fqn)).thenReturn(classInfo)
        whenever(classGraphProvider.getDependencies(fqn, false))
            .thenReturn(emptyList<DependencyInfo>() to emptyList())

        val blockingPromise = PromiseUsageInfo(fqn,
            listOf(PromiseOperation(PromiseOperationType.BLOCKING_GET, "handle", 1)),
            1, true, false, false, false, 1, emptyList())
        whenever(promiseDetector.analyzeClass(fqn)).thenReturn(blockingPromise)

        val result = calculator.calculate(fqn)

        val blockingFactor = result.factors.find { it.name == "Blocking.get() Usage" }
        assertNotNull(blockingFactor)
        assertEquals(10, blockingFactor.points)
        assertTrue(result.migrationNotes.any { it.contains("Blocking.get()") })
    }

    @Test
    fun `handler with all complexity factors should score CRITICAL`() {
        val classGraphProvider = mock<ClassGraphProvider>()
        val promiseDetector = mock<PromiseDetector>()
        val calculator = ComplexityCalculator(classGraphProvider, promiseDetector)

        val fqn = "com.example.CriticalHandler"
        val classInfo = createComplexHandlerClass(fqn)
        whenever(classGraphProvider.getClass(fqn)).thenReturn(classInfo)
        whenever(classGraphProvider.getDependencies(fqn, false))
            .thenReturn(emptyList<DependencyInfo>() to emptyList())

        // All flags true, high chain depth
        val complexPromise = PromiseUsageInfo(fqn,
            listOf(
                PromiseOperation(PromiseOperationType.BLOCKING_GET, "handle", 1),
                PromiseOperation(PromiseOperationType.EXECUTION_FORK, "process", 2),
                PromiseOperation(PromiseOperationType.PARALLEL_BATCH, "batch", 3)
            ),
            3, true, true, true, true, 5,
            listOf("getData", "processData", "saveData", "notifyAsync")
        )
        whenever(promiseDetector.analyzeClass(fqn)).thenReturn(complexPromise)

        val result = calculator.calculate(fqn)

        assertEquals(ComplexityTier.CRITICAL, result.tier)
        assertTrue(result.score >= 76)
        assertTrue(result.estimatedHours >= 8.0)
    }

    // Helper to create simple handler
    private fun createSimpleHandlerClass(fqn: String): ClassInfo {
        return ClassInfo(
            name = ClassName(fqn, fqn.substringAfterLast('.'), fqn.substringBeforeLast('.')),
            source = ClassSource.PROJECT,
            visibility = Visibility.PUBLIC,
            interfaces = listOf(RatpackTypes.HANDLER),
            methods = listOf(
                MethodInfo("handle", Visibility.PUBLIC, "void",
                    listOf(ParameterInfo("ctx", "ratpack.handling.Context", emptyList())))
            )
        )
    }

    // Helper to create complex handler
    private fun createComplexHandlerClass(fqn: String): ClassInfo {
        return ClassInfo(
            name = ClassName(fqn, fqn.substringAfterLast('.'), fqn.substringBeforeLast('.')),
            source = ClassSource.PROJECT,
            visibility = Visibility.PUBLIC,
            interfaces = listOf(RatpackTypes.HANDLER),
            methods = (1..25).map { i ->
                MethodInfo("method$i", Visibility.PUBLIC, "void", emptyList())
            },
            fields = (1..10).map { i ->
                FieldInfo("field$i", Visibility.PRIVATE, "com.example.Service$i")
            },
            superclass = RatpackTypes.GROOVY_HANDLER
        )
    }
}
```

### Expected Test Results for Real Projects

| Project | Expected Handlers | Expected Complexity Distribution |
|---------|------------------|----------------------------------|
| moonracer | ~17 | 5 LOW, 8 MEDIUM, 3 HIGH, 1 CRITICAL |
| pumbaa | ~28 | 10 LOW, 12 MEDIUM, 5 HIGH, 1 CRITICAL |
| warrantor | ~8 | 4 LOW, 3 MEDIUM, 1 HIGH |

---

## Files to Create/Modify

### New Files (Server)

| File | Description |
|------|-------------|
| `server/core/src/main/kotlin/codelens/core/model/ratpack/RatpackModels.kt` | All data models for Ratpack analysis |
| `server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/RatpackTypes.kt` | Type constants |
| `server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/RatpackDetector.kt` | Handler detection |
| `server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/PromiseDetector.kt` | Promise analysis |
| `server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/ComplexityCalculator.kt` | Complexity scoring |
| `server/classgraph/src/main/kotlin/codelens/classgraph/ratpack/GuiceModuleDetector.kt` | Guice module detection |
| `server/app/src/main/kotlin/codelens/server/routes/RatpackRoutes.kt` | API routes |
| `server/app/src/main/kotlin/codelens/server/services/RatpackAnalysisService.kt` | Service layer |

### New Files (CLI)

| File | Description |
|------|-------------|
| `cli/src/codelens_cli/commands/handlers.py` | Handler commands |
| `cli/src/codelens_cli/commands/promises.py` | Promise commands |
| `cli/src/codelens_cli/commands/migration.py` | Migration commands |
| `cli/src/codelens_cli/commands/modules.py` | Module commands |

### Modified Files

| File | Changes |
|------|---------|
| `server/app/src/main/kotlin/codelens/server/Application.kt` | Register RatpackRoutes |
| `cli/src/codelens_cli/main.py` | Register new command groups |
| `cli/src/codelens_cli/models.py` | Add Ratpack models |
| `cli/src/codelens_cli/client.py` | Add Ratpack API methods |

### Test Files

| File | Description |
|------|-------------|
| `server/classgraph/src/test/kotlin/codelens/classgraph/ratpack/RatpackDetectorTest.kt` | Unit tests |
| `server/classgraph/src/test/kotlin/codelens/classgraph/ratpack/PromiseDetectorTest.kt` | Unit tests |
| `server/classgraph/src/test/kotlin/codelens/classgraph/ratpack/ComplexityCalculatorTest.kt` | Unit tests |
| `server/classgraph/src/test/kotlin/codelens/classgraph/ratpack/GuiceModuleDetectorTest.kt` | Unit tests |
| `cli/tests/test_handlers.py` | CLI tests |
| `cli/tests/test_migration.py` | CLI tests |

### Test Fixtures

| File | Description |
|------|-------------|
| `test-fixtures/sample-ratpack-app/src/main/java/sample/handlers/SimpleHandler.java` | Basic handler |
| `test-fixtures/sample-ratpack-app/src/main/java/sample/handlers/BlockingHandler.java` | Blocking handler |
| `test-fixtures/sample-ratpack-app/src/main/java/sample/handlers/AsyncHandler.java` | Async handler |
| `test-fixtures/sample-ratpack-app/src/main/java/sample/modules/AppModule.java` | Guice module |
| `test-fixtures/sample-ratpack-app/build.gradle.kts` | Add Ratpack dependencies |

---

## Progress Tracking

### Feature 1: Handler Discovery & Classification

#### Server Implementation
- [ ] Create `RatpackModels.kt` with all data classes
- [ ] Create `RatpackTypes.kt` constants file
- [ ] Implement `RatpackDetector.findAllHandlers()`
- [ ] Implement `RatpackDetector.getHandlerDetail()`
- [ ] Implement handler type detection logic
- [ ] Implement injected dependency detection
- [ ] Add `/api/v1/ratpack/handlers` endpoint
- [ ] Add `/api/v1/ratpack/handlers/{fqn}` endpoint
- [ ] Write `RatpackDetectorTest.kt`

#### CLI Implementation
- [ ] Add `handlers` command group to `main.py`
- [ ] Implement `handlers list` command
- [ ] Implement `handlers show` command
- [ ] Add Rich table formatting
- [ ] Write `test_handlers.py`

### Feature 2: Promise Usage Detection

#### Server Implementation
- [ ] Implement `PromiseDetector.analyzeClass()`
- [ ] Implement `PromiseDetector.getProjectSummary()`
- [ ] Implement `PromiseDetector.search()`
- [ ] Add `/api/v1/ratpack/promises` endpoint
- [ ] Add `/api/v1/ratpack/promises/{fqn}` endpoint
- [ ] Add `/api/v1/ratpack/promises/search` endpoint
- [ ] Write `PromiseDetectorTest.kt`

#### CLI Implementation
- [ ] Add `promises` command group
- [ ] Implement `promises summary` command
- [ ] Implement `promises show` command
- [ ] Implement `promises search` command
- [ ] Write `test_promises.py`

### Feature 3: Migration Complexity Scoring

#### Server Implementation
- [ ] Implement `ComplexityCalculator.calculate()`
- [ ] Implement scoring algorithm with all factors
- [ ] Implement `ComplexityCalculator.getProjectSummary()`
- [ ] Implement migration order calculation
- [ ] Add `/api/v1/ratpack/complexity` endpoint
- [ ] Add `/api/v1/ratpack/complexity/{fqn}` endpoint
- [ ] Add `/api/v1/ratpack/migration-order` endpoint
- [ ] Write `ComplexityCalculatorTest.kt`

#### CLI Implementation
- [ ] Add `migration` command group
- [ ] Implement `migration complexity` command
- [ ] Implement `migration order` command
- [ ] Implement `migration analyze` command
- [ ] Write `test_migration.py`

### Feature 4: Guice DI Analysis

#### Server Implementation
- [ ] Implement `GuiceModuleDetector.findAllModules()`
- [ ] Implement `GuiceModuleDetector.getModuleDetail()`
- [ ] Implement `GuiceModuleDetector.findBindingsForType()`
- [ ] Add `/api/v1/ratpack/modules` endpoint
- [ ] Add `/api/v1/ratpack/modules/{fqn}` endpoint
- [ ] Add `/api/v1/ratpack/modules/binding/{type}` endpoint
- [ ] Write `GuiceModuleDetectorTest.kt`

#### CLI Implementation
- [ ] Add `modules` command group
- [ ] Implement `modules list` command
- [ ] Implement `modules show` command
- [ ] Implement `modules binding` command
- [ ] Write `test_modules.py`

### Integration & Testing
- [ ] Expand test fixtures with Ratpack examples
- [ ] Create `RatpackAnalysisService` to coordinate detectors
- [ ] Register routes in `Application.kt`
- [ ] Update CLI models and client
- [ ] Integration test against moonracer
- [ ] Integration test against pumbaa
- [ ] Integration test against warrantor

---

## Acceptance Criteria

### Functional
- [ ] `codelens handlers list` returns all handlers from target project
- [ ] `codelens handlers show X` returns detailed handler info with complexity
- [ ] `codelens promises summary` shows project-wide Promise usage
- [ ] `codelens migration complexity` provides accurate effort estimates
- [ ] `codelens migration order` suggests correct migration sequence
- [ ] `codelens modules list` finds all Guice modules
- [ ] All commands support `--json` flag for LLM consumption

### Quality
- [ ] Unit test coverage > 80% for new code
- [ ] Integration tests pass against test fixtures
- [ ] Manual verification against 3 real projects successful

### Performance
- [ ] Handler detection completes in < 1 second for typical project
- [ ] Full analysis (all features) completes in < 5 seconds

---

## Key Insights & Takeaways

*This section should be updated during implementation with important discoveries.*

### Technical Insights
-

### Pattern Discoveries
-

### Performance Notes
-

---

## Deviations Log

*Document any changes from the original plan and why.*

| Date | Original Plan | Actual Implementation | Reason |
|------|---------------|----------------------|--------|
| | | | |

---

## Blockers & Issues

*Track any blockers encountered during implementation.*

| Issue | Status | Resolution |
|-------|--------|------------|
| | | |

---

## Notes for Next Phase

*Capture anything learned that affects Phase 2B planning.*

-

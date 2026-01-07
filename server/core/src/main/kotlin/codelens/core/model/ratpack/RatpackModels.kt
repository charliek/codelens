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

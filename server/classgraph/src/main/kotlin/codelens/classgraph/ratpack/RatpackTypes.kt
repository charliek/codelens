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

    // Inject annotations
    val INJECT_ANNOTATIONS = setOf(
        INJECT,
        JAKARTA_INJECT,
        JAVAX_INJECT
    )

    // Promise operators (method names on Promise)
    val PROMISE_OPERATORS = setOf(
        "map", "flatMap", "then", "onError", "route",
        "cache", "retry", "transform", "apply", "flatOp",
        "mapError", "onYield", "wiretap", "throttle",
        "time", "close", "result", "next", "nextOp",
        "flatRight", "right", "left", "flatLeft",
        "mapIf", "flatMapIf", "retryIf"
    )

    // Blocking method names
    val BLOCKING_METHODS = setOf("get", "on", "exec")
}

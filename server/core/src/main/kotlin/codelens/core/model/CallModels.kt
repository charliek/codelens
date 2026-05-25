package codelens.core.model

import kotlinx.serialization.Serializable

// Models for the general call-site extraction primitive (`calls`).
//
// A single linear bytecode instruction scan over a method body produces a list
// of the invocations that method makes, each annotated with the constant
// arguments (LDC-loaded strings/numbers/class literals) observed immediately
// before the call. These are raw facts — no framework-specific filtering is
// applied. Consumers (the CLI / an LLM via a skill) narrow and interpret them.
//
// This is a Tier-1 analysis: a linear scan with a sliding window of recent
// constants, not operand-stack simulation. Known limits (documented, not
// fixed): arguments produced by other calls or computed at runtime resolve as
// "unknown" (absent from CallSite.constantArgs); lambda / method-reference
// targets compile to `invokedynamic` and carry no `.class` literal.

/** The kind of a captured bytecode constant. */
@Serializable
enum class ConstantKind {
    /** A `String` constant (`LDC "..."`). */
    STRING,

    /** An `int` constant loaded via `LDC`. */
    INT,

    /** A `long` constant loaded via `LDC`. */
    LONG,

    /** A `float` constant loaded via `LDC`. */
    FLOAT,

    /** A `double` constant loaded via `LDC`. */
    DOUBLE,

    /** A class literal (`LDC Foo.class`); [ConstantArg.value] is the dotted FQN. */
    CLASS,
}

/**
 * A single constant value observed on the operand stack near a call site.
 */
@Serializable
data class ConstantArg(
    /** What kind of constant this is. */
    val kind: ConstantKind,
    /**
     * String rendering of the constant. For [ConstantKind.CLASS] this is the
     * dotted fully-qualified class name; for numeric kinds it is the decimal
     * rendering; for [ConstantKind.STRING] it is the raw string.
     */
    val value: String,
)

/**
 * A single invocation made by a method body.
 */
@Serializable
data class CallSite(
    /** Dotted FQN of the type that declares the invoked method (the call receiver's static type). */
    val ownerType: String,
    /** The invoked method's name (`<init>` for constructors). */
    val methodName: String,
    /** The invoked method's JVM descriptor (e.g. `(Ljava/lang/String;)V`). */
    val descriptor: String,
    /** True when the invocation is `invokeinterface`. */
    val isInterface: Boolean,
    /**
     * Constants observed (via `LDC`) since the previous invocation, in program
     * order. Best-effort association of likely arguments to this call.
     */
    val constantArgs: List<ConstantArg> = emptyList(),
    /** Source line number of the call, when debug info is present; otherwise null. */
    val lineNumber: Int? = null,
)

/**
 * The calls made by one method, keyed by the enclosing method.
 */
@Serializable
data class MethodCalls(
    /** The enclosing method's name. */
    val methodName: String,
    /** The enclosing method's JVM descriptor. */
    val descriptor: String,
    /** Invocations the method makes, in program order. */
    val calls: List<CallSite>,
)

/**
 * Result of extracting call sites from a class.
 */
@Serializable
data class CallSiteList(
    /** The class that was scanned. */
    val fqn: String,
    /** Per-method call lists. */
    val methods: List<MethodCalls>,
)

/**
 * API response wrapper for the `calls` endpoint.
 */
@Serializable
data class CallsResponse(
    /** The class that was scanned. */
    val fqn: String,
    /** Per-method call lists. */
    val methods: List<MethodCalls>,
    /** Total number of call sites across all returned methods. */
    val totalCalls: Int,
)

package codelens.core.model

import kotlinx.serialization.Serializable

// Models for the inverse type cross-reference primitive (`xref`): everything
// across the project that references a given type.
//
// References are gathered from two passes over the scanned classes:
//   - a cheap signature-level pass over ClassInfo (supertypes, field types,
//     parameter/return types, annotations), and
//   - a bytecode-level pass reusing the call-site scan (instantiations and
//     call receivers).
//
// The signature pass honors includeLibraries; the bytecode pass always scans
// project classes only (reading library bytecode wholesale is impractical).

/** How a class references a target type. */
@Serializable
enum class XrefKind {
    /** Referencing class extends the target type. */
    EXTENDS,

    /** Referencing class implements the target interface. */
    IMPLEMENTS,

    /** Referencing class has a field of the target type. */
    FIELD,

    /** A method/constructor of the referencing class takes the target type as a parameter. */
    PARAM,

    /** A method of the referencing class returns the target type. */
    RETURN,

    /** The referencing class/member is annotated with the target type. */
    ANNOTATION,

    /** The referencing class instantiates the target type (`new` / `<init>`). */
    INSTANTIATION,

    /** The referencing class invokes a method on the target type. */
    CALL_RECEIVER,
}

/** One reference from a class (or one of its members) to the target type. */
@Serializable
data class XrefReference(
    /** FQN of the referencing class. */
    val fromFqn: String,
    /** Simple name of the referencing class. */
    val fromSimpleName: String,
    /** Source of the referencing class (PROJECT / LIBRARY / JDK). */
    val fromSource: ClassSource,
    /** The kind of reference. */
    val kind: XrefKind,
    /** The member (method/field/`<init>`) where the reference occurs, when applicable. */
    val member: String? = null,
    /** Extra context: the raw type string, the invoked method name, or the annotated target. */
    val detail: String? = null,
    /** Source line of a bytecode-level reference, when debug info is present. */
    val lineNumber: Int? = null,
)

/** Summary of the filter that was applied (echoed back for display). */
@Serializable
data class XrefFilterSummary(
    val includeLibraries: Boolean,
    val kind: String? = null,
    val scopeImplementing: String? = null,
)

/**
 * Response for the `xref` endpoint: a paginated slice of references plus
 * aggregates over the full result so common fan-outs stay off the wire.
 */
@Serializable
data class XrefResponse(
    /** The type that was cross-referenced. */
    val typeFqn: String,
    /** The page of references (already filtered by kind, sorted, and sliced). */
    val references: List<XrefReference>,
    /** Total references after the kind filter, before pagination. */
    val totalCount: Int,
    /** Current page (0-based). */
    val page: Int,
    /** Page size. */
    val pageSize: Int,
    /** Total pages. */
    val totalPages: Int,
    /** Count by reference kind across the full result (before the kind filter and pagination). */
    val countsByKind: Map<String, Int>,
    /** Count by referencing package across the kind-filtered result (before pagination). */
    val countsByPackage: Map<String, Int>,
    /** The filter that was applied. */
    val appliedFilter: XrefFilterSummary,
)

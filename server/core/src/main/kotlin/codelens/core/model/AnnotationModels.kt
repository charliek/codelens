package codelens.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

// Models for the `annotations usages` primitive: every place a given annotation
// is applied — across class, method, constructor, field, and parameter targets —
// with the matched annotation's typed attribute values inline.
//
// This is an in-memory projection over the already-converted ClassInfo graph
// (annotation attribute values are typed at scan time, #41); no extra bytecode
// scanning. The annotation lists ClassGraph stores are meta-expanded, so querying
// a meta-annotation (e.g. @RequestMapping) matches the synthesized instance on a
// @GetMapping method, carrying the aliased attributes (path + method=GET).

/** Which declaration sites to scan for the annotation. */
@Serializable
enum class AnnotationScope {
    /** Class/interface/enum/annotation declarations. */
    CLASS,

    /** Methods (and constructors, surfaced with target=CONSTRUCTOR). */
    METHOD,

    /** Fields. */
    FIELD,

    /** Method and constructor parameters. */
    PARAM,

    /** Every target (the default): class + method + constructor + field + parameter. */
    ALL,
}

/**
 * The declaration site an annotation usage was found on.
 *
 * Named [AnnotationUsageTarget] (not `AnnotationTarget`) to avoid colliding with
 * the auto-imported `kotlin.annotation.AnnotationTarget`. Declaration order is the
 * sort/wire order and is locked by an enum-name stability test.
 */
@Serializable
enum class AnnotationUsageTarget {
    CLASS,
    METHOD,
    CONSTRUCTOR,
    FIELD,
    PARAMETER,
}

/**
 * One place a queried annotation is applied, with the matched annotation's typed
 * attributes inline.
 *
 * The member-identity fields are sparse (`@EncodeDefault(NEVER)`) — only those
 * relevant to [target] are populated (matching the idiom on [AnnotationValue] /
 * [CallSite]):
 *   - METHOD: [method] + [descriptor] (the erased JVM descriptor)
 *   - CONSTRUCTOR: [method] = `<init>` + [descriptor] (a derived `(type,…)`
 *     parameter-type signature, since constructors carry no JVM descriptor)
 *   - FIELD: [field]
 *   - PARAMETER:   the enclosing [method] (+ [descriptor]) plus [parameterName],
 *     [parameterIndex], [parameterType]
 *   - CLASS:       none of the member fields
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AnnotationUsage(
    /** The declaration site this usage was found on. */
    val target: AnnotationUsageTarget,
    /** FQN of the declaring class. */
    val classFqn: String,
    /** Simple name of the declaring class. */
    val classSimpleName: String,
    /** Package of the declaring class. */
    val packageName: String,
    /** Source of the declaring class (PROJECT / LIBRARY / JDK). */
    val source: ClassSource,
    /** Method/constructor name (`<init>` for constructors). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val method: String? = null,
    /** Method: erased JVM descriptor. Constructor: derived `(type,…)` signature. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val descriptor: String? = null,
    /** Field name. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val field: String? = null,
    /** Parameter name. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val parameterName: String? = null,
    /** Parameter position (0-based) within its method/constructor. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val parameterIndex: Int? = null,
    /** Parameter type FQN (generic form when the bytecode carries a signature). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val parameterType: String? = null,
    /**
     * The matched annotation as it appears in the (meta-expanded) annotation list
     * — the entry whose [AnnotationInfo.type] equals the queried FQN — with its
     * typed [AnnotationInfo.parameters].
     */
    val annotation: AnnotationInfo,
)

/** Summary of the filter that was applied (echoed back for display). */
@Serializable
data class AnnotationUsagesFilterSummary(
    val includeLibraries: Boolean,
    val scope: AnnotationScope,
)

/**
 * Response for the `annotations usages` endpoint: a paginated slice of usages plus
 * a per-target breakdown over the full result so common fan-outs stay off the wire.
 */
@Serializable
data class AnnotationUsagesResponse(
    /** The annotation that was queried. */
    val annotationFqn: String,
    /** The page of usages (already sorted and sliced). */
    val usages: List<AnnotationUsage>,
    /** Total usages in the scoped result, before pagination. */
    val totalCount: Int,
    /** Current page (0-based). */
    val page: Int,
    /** Page size. */
    val pageSize: Int,
    /** Total pages. */
    val totalPages: Int,
    /** Count by target across the full scoped result (before pagination). */
    val countsByTarget: Map<String, Int>,
    /** The filter that was applied. */
    val appliedFilter: AnnotationUsagesFilterSummary,
)

package codelens.core.model

import kotlinx.serialization.Serializable

/**
 * Filter criteria for class searches.
 */
@Serializable
data class ClassFilter(
    /** Filter by name pattern (supports * wildcard) */
    val namePattern: String? = null,
    /** Filter by package pattern (supports * wildcard) */
    val packagePattern: String? = null,
    /** Filter by class source */
    val source: ClassSource? = null,
    /** Filter to classes with this annotation */
    val hasAnnotation: String? = null,
    /** Filter to classes extending this class */
    val extendsClass: String? = null,
    /** Filter to classes implementing this interface */
    val implementsInterface: String? = null,
    /** Include only interfaces */
    val onlyInterfaces: Boolean = false,
    /** Include only classes (not interfaces) */
    val onlyClasses: Boolean = false,
    /** Include libraries in results (default: false, only PROJECT) */
    val includeLibraries: Boolean = false,
) {
    /**
     * Returns an effective source filter.
     * If includeLibraries is false and no explicit source is set, defaults to PROJECT.
     */
    fun effectiveSourceFilter(): ClassSource? =
        when {
            source != null -> source
            !includeLibraries -> ClassSource.PROJECT
            else -> null
        }
}

/**
 * Filter criteria for method searches.
 */
@Serializable
data class MethodFilter(
    /** Filter by method name pattern (supports * wildcard) */
    val namePattern: String? = null,
    /** Filter by return type FQN */
    val returnType: String? = null,
    /** Filter to methods with this annotation */
    val hasAnnotation: String? = null,
    /** Filter by containing class FQN */
    val inClass: String? = null,
    /** Filter by containing package pattern */
    val inPackage: String? = null,
    /** Include libraries in results */
    val includeLibraries: Boolean = false,
)

/**
 * Pagination request.
 */
@Serializable
data class PageRequest(
    /** Page number (0-based) */
    val page: Int = 0,
    /** Number of items per page */
    val size: Int = 50,
    /** Sort field */
    val sortBy: String = "fqn",
    /** Sort direction */
    val sortDirection: SortDirection = SortDirection.ASC,
)

/**
 * Sort direction.
 */
@Serializable
enum class SortDirection {
    ASC,
    DESC,
}

/**
 * Paginated response wrapper.
 */
@Serializable
data class PagedResponse<T>(
    /** Items in this page */
    val items: List<T>,
    /** Total number of items matching the filter */
    val totalItems: Int,
    /** Current page number (0-based) */
    val page: Int,
    /** Number of items per page */
    val pageSize: Int,
    /** Total number of pages */
    val totalPages: Int,
) {
    companion object {
        fun <T> of(
            items: List<T>,
            allItems: List<T>,
            pageRequest: PageRequest,
        ): PagedResponse<T> {
            val totalItems = allItems.size
            val totalPages = if (totalItems == 0) 1 else (totalItems + pageRequest.size - 1) / pageRequest.size
            return PagedResponse(
                items = items,
                totalItems = totalItems,
                page = pageRequest.page,
                pageSize = pageRequest.size,
                totalPages = totalPages,
            )
        }
    }
}

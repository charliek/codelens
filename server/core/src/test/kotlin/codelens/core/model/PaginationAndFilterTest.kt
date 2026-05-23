package codelens.core.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for pure helper logic on `QueryModels.kt` types -- the bits that
 * actually contain branching logic worth covering.
 */
class PaginationAndFilterTest {
    @Test
    fun `ClassFilter effectiveSourceFilter honors explicit source over includeLibraries`() {
        val filter =
            ClassFilter(
                source = ClassSource.LIBRARY,
                includeLibraries = false,
            )
        assertEquals(ClassSource.LIBRARY, filter.effectiveSourceFilter())
    }

    @Test
    fun `ClassFilter effectiveSourceFilter defaults to PROJECT when libraries excluded`() {
        val filter = ClassFilter() // includeLibraries=false by default
        assertEquals(ClassSource.PROJECT, filter.effectiveSourceFilter())
    }

    @Test
    fun `ClassFilter effectiveSourceFilter returns null when libraries included and no source set`() {
        val filter = ClassFilter(includeLibraries = true)
        assertNull(filter.effectiveSourceFilter())
    }

    @Test
    fun `PagedResponse of computes totalPages correctly for empty input`() {
        val response = PagedResponse.of(items = emptyList<String>(), allItems = emptyList<String>(), pageRequest = PageRequest())
        assertEquals(0, response.totalItems)
        assertEquals(1, response.totalPages, "Empty result should still report 1 page")
        assertEquals(0, response.items.size)
    }

    @Test
    fun `PagedResponse of computes totalPages for an even page boundary`() {
        val all = (1..100).map { "item-$it" }
        val page = all.take(25)
        val response = PagedResponse.of(items = page, allItems = all, pageRequest = PageRequest(page = 0, size = 25))
        assertEquals(100, response.totalItems)
        assertEquals(4, response.totalPages)
        assertEquals(0, response.page)
        assertEquals(25, response.pageSize)
    }

    @Test
    fun `PagedResponse of rounds totalPages up for an uneven page boundary`() {
        val all = (1..101).map { "item-$it" }
        val page = all.take(25)
        val response = PagedResponse.of(items = page, allItems = all, pageRequest = PageRequest(page = 0, size = 25))
        assertEquals(101, response.totalItems)
        assertEquals(5, response.totalPages, "101 items at size 25 should be 5 pages")
    }

    @Test
    fun `PagedResponse of preserves the page request page and size`() {
        val all = (1..100).map { "item-$it" }
        val response = PagedResponse.of(items = emptyList<String>(), allItems = all, pageRequest = PageRequest(page = 3, size = 10))
        assertEquals(3, response.page)
        assertEquals(10, response.pageSize)
    }
}

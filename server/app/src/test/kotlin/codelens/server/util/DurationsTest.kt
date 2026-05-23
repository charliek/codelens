package codelens.server.util

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

class DurationsTest {
    @Test
    fun `seconds only`() {
        assertEquals("0s", formatDuration(Duration.ZERO))
        assertEquals("1s", formatDuration(Duration.ofSeconds(1)))
        assertEquals("59s", formatDuration(Duration.ofSeconds(59)))
    }

    @Test
    fun `minutes and seconds`() {
        assertEquals("1m 0s", formatDuration(Duration.ofMinutes(1)))
        assertEquals("1m 30s", formatDuration(Duration.ofSeconds(90)))
        assertEquals("59m 59s", formatDuration(Duration.ofSeconds(59 * 60L + 59)))
    }

    @Test
    fun `hours minutes and seconds`() {
        assertEquals("1h 0m 0s", formatDuration(Duration.ofHours(1)))
        assertEquals("2h 13m 4s", formatDuration(Duration.ofSeconds(2 * 3600L + 13 * 60 + 4)))
    }

    @Test
    fun `large durations exceeding a day still report in hours`() {
        assertEquals("25h 0m 0s", formatDuration(Duration.ofHours(25)))
    }
}

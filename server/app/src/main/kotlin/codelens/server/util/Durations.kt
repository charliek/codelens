package codelens.server.util

import java.time.Duration

/**
 * Format a [Duration] as a human-readable short string (e.g. "2h 13m 4s",
 * "5m 0s", or "37s"). Used for `/admin/info` uptime/idle reporting.
 */
fun formatDuration(duration: Duration): String {
    val hours = duration.toHours()
    val minutes = duration.toMinutesPart()
    val seconds = duration.toSecondsPart()

    return when {
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

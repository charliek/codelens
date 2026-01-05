package codelens.server.monitoring

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe tracker for server activity.
 *
 * Used to track when the server was last accessed and calculate idle time.
 */
class ActivityTracker {
    private val lastActivity = AtomicReference(Instant.now())
    private val startedAt = Instant.now()

    /**
     * Records activity at the current time.
     * Call this on every request to reset the idle timer.
     */
    fun touch() {
        lastActivity.set(Instant.now())
    }

    /**
     * @return The timestamp of the last recorded activity
     */
    fun getLastActivity(): Instant = lastActivity.get()

    /**
     * @return The timestamp when the tracker was created (server start time)
     */
    fun getStartedAt(): Instant = startedAt

    /**
     * @return Duration since the last activity
     */
    fun getIdleDuration(): Duration = Duration.between(lastActivity.get(), Instant.now())

    /**
     * @return Duration since the server started
     */
    fun getUptime(): Duration = Duration.between(startedAt, Instant.now())
}

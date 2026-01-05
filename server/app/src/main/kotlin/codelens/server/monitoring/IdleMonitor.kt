package codelens.server.monitoring

import java.time.Duration
import kotlin.concurrent.thread

/**
 * Monitors server idle time and triggers shutdown when threshold is exceeded.
 */
class IdleMonitor(
    private val tracker: ActivityTracker,
    private val timeout: Duration,
    private val checkIntervalMs: Long = 60_000,
    private val onIdle: () -> Unit
) {
    @Volatile
    private var running = true

    /**
     * Starts the idle monitoring in a background daemon thread.
     */
    fun start() {
        thread(name = "idle-monitor", isDaemon = true) {
            while (running) {
                Thread.sleep(checkIntervalMs)
                if (tracker.getIdleDuration() > timeout) {
                    onIdle()
                    break
                }
            }
        }
    }

    /**
     * Stops the idle monitoring.
     */
    fun stop() {
        running = false
    }
}

/**
 * Convenience function to start idle monitoring.
 *
 * @param tracker The activity tracker to monitor
 * @param timeout Duration of inactivity before triggering shutdown
 * @param onIdle Callback to invoke when idle timeout is reached
 */
fun startIdleMonitor(tracker: ActivityTracker, timeout: Duration, onIdle: () -> Unit) {
    IdleMonitor(tracker, timeout, onIdle = onIdle).start()
}

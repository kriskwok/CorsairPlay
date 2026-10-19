package com.shilapi.xcertplay.orchestration

/**
 * A time limit for the iPhone's AirPlay session after a wired `carplay-start-session`.
 *
 * The wired control loop has no limit of its own, so an iPhone that accepts the start request but
 * never opens the session can leave CarPlay on "connecting" until the cable is replugged. When the
 * limit passes, [onTimeout] ends the attempt so the normal retry starts a new one.
 */
internal class WiredStartWatchdog(
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onTimeout: () -> Unit,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
    private var armed = false
    private var finished = false
    private var cancelTimer: (() -> Unit)? = null

    /** The first `carplay-start-session` starts the limit; a repeated one keeps it. */
    @Synchronized fun startSessionSent() {
        if (armed || finished) return
        armed = true
        cancelTimer = schedule(timeoutMillis, ::expire)
    }

    @Synchronized fun sessionActive() {
        finished = true
        cancel()
    }

    @Synchronized fun terminate() {
        finished = true
        cancel()
    }

    private fun expire() {
        synchronized(this) {
            if (finished) return
            finished = true
            cancelTimer = null
        }
        onTimeout()
    }

    private fun cancel() {
        cancelTimer?.invoke()
        cancelTimer = null
    }

    companion object {
        /** The same limit as the wireless first connection. */
        const val TIMEOUT_MILLIS = 30_000L
    }
}

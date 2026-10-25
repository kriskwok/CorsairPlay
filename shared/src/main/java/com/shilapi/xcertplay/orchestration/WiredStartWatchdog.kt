package com.shilapi.xcertplay.orchestration

/**
 * Time limits for the wired bring-up and the iPhone's AirPlay session after `carplay-start-session`.
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
    private var waitingForStartRequest = false
    private var sessionDeadlineArmed = false
    private var finished = false
    private var cancelTimer: (() -> Unit)? = null

    /**
     * A saved session can stall before the control loop starts, for example while reopening USB or
     * lockdown. Arm the watchdog there so reconnecting from Settings cannot wait forever.
     */
    @Synchronized fun bringUpStarted() {
        if (armed || finished) return
        armed = true
        waitingForStartRequest = true
        arm(BRING_UP_TIMEOUT_MILLIS)
    }

    /** Reaching the control loop is progress, so give the next stage its own deadline. */
    @Synchronized fun controlLoopStarted() {
        if (finished) return
        if (!armed || waitingForStartRequest) {
            armed = true
            waitingForStartRequest = true
            arm(BRING_UP_TIMEOUT_MILLIS)
        }
    }

    /** The first `carplay-start-session` starts the limit; a repeated one keeps it. */
    @Synchronized fun startSessionSent() {
        if (finished || sessionDeadlineArmed) return
        waitingForStartRequest = false
        sessionDeadlineArmed = true
        armed = true
        arm(timeoutMillis)
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

    private fun arm(timeout: Long) {
        cancel()
        cancelTimer = schedule(timeout, ::expire)
    }

    companion object {
        /**
         * How long the iPhone gets to open its AirPlay session after `carplay-start-session`.
         *
         * Measured on the head unit, a session that is going to open does so about 7.5 s after the
         * start request; one that misses does not open at all. The limit used to be the wireless
         * connection's 30 s, which left the screen on "connecting" for half a minute before the
         * retry that always worked. Twice the measured time still leaves room for a slow iPhone.
         */
        const val TIMEOUT_MILLIS = 15_000L

        /** How long the USB bring-up gets before the control loop is up; that part takes ~1 s. */
        const val BRING_UP_TIMEOUT_MILLIS = 20_000L
    }
}

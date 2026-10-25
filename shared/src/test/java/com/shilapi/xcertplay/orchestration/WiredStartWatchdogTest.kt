package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WiredStartWatchdogTest {
    private var delay: Long? = null
    private var timer: (() -> Unit)? = null
    private var cancelled = 0
    private var schedules = 0
    private var timeouts = 0

    private fun watchdog() = WiredStartWatchdog(
        schedule = { delayMillis, action ->
            schedules++
            delay = delayMillis
            timer = action
            val cancel: () -> Unit = { cancelled++ }
            cancel
        },
        onTimeout = { timeouts++ },
    )

    @Test fun nothingIsTimedBeforeTheStartRequest() {
        watchdog()
        assertNull(timer)
        assertEquals(0, timeouts)
    }

    @Test fun anIphoneThatNeverOpensTheSessionEndsTheAttemptOnce() {
        val watch = watchdog()
        watch.startSessionSent()
        assertEquals(WiredStartWatchdog.TIMEOUT_MILLIS, delay)
        timer!!()
        timer!!()
        assertEquals(1, timeouts)
    }

    @Test fun aRepeatedStartRequestKeepsTheFirstDeadline() {
        val watch = watchdog()
        watch.startSessionSent()
        val first = timer
        watch.startSessionSent()
        assertEquals(first, timer)
        assertEquals(0, cancelled)
    }

    @Test fun anActiveSessionCancelsTheLimit() {
        val watch = watchdog()
        watch.startSessionSent()
        watch.sessionActive()
        assertEquals(1, cancelled)
        timer!!()
        assertEquals(0, timeouts)
        watch.startSessionSent()
        assertEquals(1, cancelled)
    }

    @Test fun aBringUpThatNeverReachesTheControlLoopTimesOut() {
        val watch = watchdog()
        watch.bringUpStarted()
        assertEquals(WiredStartWatchdog.BRING_UP_TIMEOUT_MILLIS, delay)
        timer!!()
        assertEquals(1, timeouts)
    }

    @Test fun reachingTheControlLoopAndStartRequestEachRenewTheDeadline() {
        val watch = watchdog()
        watch.bringUpStarted()
        assertEquals(1, schedules)
        watch.controlLoopStarted()
        assertEquals(2, schedules)
        assertEquals(1, cancelled)
        watch.startSessionSent()
        assertEquals(3, schedules)
        assertEquals(2, cancelled)
        assertEquals(WiredStartWatchdog.TIMEOUT_MILLIS, delay)
        timer!!()
        assertEquals(1, timeouts)
    }

    @Test fun teardownCancelsTheLimit() {
        val watch = watchdog()
        watch.startSessionSent()
        watch.terminate()
        timer!!()
        assertEquals(0, timeouts)
    }
}

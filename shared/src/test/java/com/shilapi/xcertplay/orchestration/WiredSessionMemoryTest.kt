package com.shilapi.xcertplay.orchestration

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class WiredSessionMemoryTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before fun clearBefore() = WiredSessionMemory.clear(context)
    @After fun clearAfter() = WiredSessionMemory.clear(context)

    @Test fun theMarkerMatchesOnlyTheSameEnumeration() {
        assertFalse(WiredSessionMemory.startedOn(context, "/dev/bus/usb/001/005"))
        WiredSessionMemory.started(context, "/dev/bus/usb/001/005")
        assertTrue(WiredSessionMemory.startedOn(context, "/dev/bus/usb/001/005"))
        assertFalse(WiredSessionMemory.startedOn(context, "/dev/bus/usb/001/006"))
    }

    @Test fun anAppUpdateKeepsTheMarkerAcrossProcessMemory() {
        val device = "/dev/bus/usb/001/007"
        WiredSessionMemory.started(context, device)
        ReflectionHelpers.setField(WiredSessionMemory, "startedOn", null)
        assertTrue(WiredSessionMemory.startedOn(context, device))
    }

    @Test fun onlyTheNodeOfASessionDiPlayClosedItselfWaitsForTheUsbReset() {
        val closed = "/dev/bus/usb/001/009"
        WiredSessionMemory.started(context, closed)
        assertFalse(WiredSessionMemory.closedByDiPlayOn(context, closed))
        WiredSessionMemory.hostClosed(context, closed)
        assertTrue(WiredSessionMemory.closedByDiPlayOn(context, closed))
        // A session on another node means the iPhone reset USB: the marker must not follow it.
        WiredSessionMemory.started(context, "/dev/bus/usb/001/010")
        assertFalse(WiredSessionMemory.closedByDiPlayOn(context, closed))
        assertFalse(WiredSessionMemory.closedByDiPlayOn(context, "/dev/bus/usb/001/010"))
    }

    @Test fun anOldMarkerExpiresAfterIosHasResetTheUsbSession() {
        val device = "/dev/bus/usb/001/008"
        context.getSharedPreferences("diplay_wired_session", Context.MODE_PRIVATE)
            .edit()
            .putString("device", device)
            .putLong("started_at", System.currentTimeMillis() - 6 * 60_000L)
            .commit()
        ReflectionHelpers.setField(WiredSessionMemory, "startedOn", null)
        assertFalse(WiredSessionMemory.startedOn(context, device))
    }
}

package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.hud.BydCarPlayCall
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WheelMediaKeyCaptureTest {
    private lateinit var service: WheelKeyService
    private lateinit var audio: AudioManager
    private val sent = mutableListOf<Int>()
    private var session: Any? = "phone"

    @Before fun setUp() {
        BydCarPlayCall.end()
        service = Robolectric.buildService(WheelKeyService::class.java).create().get()
        service.getSharedPreferences("diplay_wheel_map_zoom", Context.MODE_PRIVATE).edit().clear().commit()
        service.getSharedPreferences("diplay_wheel_media_keys", Context.MODE_PRIVATE).edit().clear().commit()
        service.getSharedPreferences("diplay_navigation_wheel", Context.MODE_PRIVATE).edit().clear().commit()
        service.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
        service.session = { session }
        service.sendCarPlayMediaKey = { sent += it; true }
        service.isCarPlayPlaying = { session != null }
        audio = service.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_NORMAL
    }

    @After fun tearDown() {
        service.onDestroy()
        BydCarPlayCall.end()
        CarPlayBackgroundSession.clear()
    }

    @Test fun previousAndNextReachCarPlayWhileConnected() {
        assertTrue(press(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertTrue(press(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertTrue(press(CarPlayMediaButton.KEYCODE_LINCOLN_SWC_SEEK_LEFT))
        assertTrue(press(CarPlayMediaButton.KEYCODE_LINCOLN_SWC_SEEK_RIGHT))
        assertTrue(press(CarPlayMediaButton.KEYCODE_LINCOLN_CONSOLE_PREVIOUS))
        assertTrue(press(CarPlayMediaButton.KEYCODE_LINCOLN_CONSOLE_NEXT))
        assertEquals(
            listOf(
                CarPlayMediaButton.PREVIOUS,
                CarPlayMediaButton.NEXT,
                CarPlayMediaButton.PREVIOUS,
                CarPlayMediaButton.NEXT,
                CarPlayMediaButton.PREVIOUS,
                CarPlayMediaButton.NEXT,
            ),
            sent,
        )
    }

    @Test fun onePhysicalPressSendsOneCarPlayTap() {
        assertTrue(send(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_DOWN))
        assertTrue(send(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_DOWN, repeat = 1))
        assertTrue(send(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_UP))
        assertEquals(listOf(CarPlayMediaButton.NEXT), sent)
    }

    @Test fun withoutACarPlaySessionTheStockAppKeepsTheKey() {
        session = null
        assertFalse(press(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertTrue(sent.isEmpty())
    }

    @Test fun duringACallTheCarKeepsTheKey() {
        audio.mode = AudioManager.MODE_IN_CALL
        assertFalse(press(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertTrue(sent.isEmpty())
    }

    @Test fun theSettingCanReturnTheKeysToTheCar() {
        WheelMediaKeySettings.setEnabled(service, false)
        assertFalse(press(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertTrue(sent.isEmpty())
    }

    @Test fun pausedCarPlayLeavesTheKeysToTheHeadUnit() {
        service.isCarPlayPlaying = { false }
        assertFalse(press(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertTrue(sent.isEmpty())
    }

    private fun press(code: Int): Boolean {
        val kept = send(code, KeyEvent.ACTION_DOWN)
        send(code, KeyEvent.ACTION_DOWN, repeat = 1)
        send(code, KeyEvent.ACTION_UP)
        return kept
    }

    private fun send(code: Int, action: Int, repeat: Int = 0): Boolean = service.javaClass
        .getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply { isAccessible = true }
        .invoke(service, KeyEvent(1_000, 1_000, action, code, repeat, 0, 7, 0)) as Boolean
}

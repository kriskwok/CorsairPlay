package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.airplay.CarPlaySize
import com.shilapi.xcertplay.lincoln.LincolnClusterSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** The switches this fork ships enabled on a fresh install, before the driver changes anything. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LincolnInstallDefaultsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "MEK-MX8Q")
        for (name in listOf("xcertplay_airplay", "diplay", "diplay_lincoln_cluster")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun clearerUsbNavigationVoiceIsOnByDefault() {
        assertTrue(AirPlayPersistence.loadSpeechWithout8And16Khz(context))
    }

    @Test fun displayDefaultsMatchTheSavedLincolnSetup() {
        assertEquals(CarPlayNightMode.AMBIENT, AirPlayPersistence.loadCarPlayNightMode(context))
        assertEquals(AmbientLightThreshold(120), AirPlayPersistence.loadAmbientLightThreshold(context))
        assertEquals(CarPlaySize.LARGE, CarPlaySize.fromWidthMillimeters(
            AirPlayPersistence.loadWidthPhysicalMm(context)))
        assertEquals(60, AirPlayPersistence.loadFps(context))
    }

    @Test fun navigationDuckingIsOnByDefault() {
        assertTrue(AirPlayPersistence.loadNavigationDuckingMedia(context))
    }

    @Test fun musicClockTrimIsOffByDefaultToPreventResamplerNoise() {
        assertFalse(AirPlayPersistence.loadMusicClockTrim(context))
    }

    @Test fun anOldSavedClockTrimIsDisabledOnceDuringUpgrade() {
        val preferences = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        preferences.edit().putBoolean("music_clock_trim", true).commit()

        assertFalse(AirPlayPersistence.loadMusicClockTrim(context))
        assertFalse(preferences.getBoolean("music_clock_trim", true))
        assertTrue(preferences.getBoolean("music_clock_trim_safe_default_v1", false))

        // The migration happens once; a deliberate choice afterwards must stick.
        AirPlayPersistence.saveMusicClockTrim(context, true)
        assertTrue(AirPlayPersistence.loadMusicClockTrim(context))
    }

    @Test fun allThreeClusterForwardingSwitchesAreOnForThisHeadUnit() {
        assertTrue(LincolnClusterSettings.navigation(context))
        assertTrue(LincolnClusterSettings.music(context))
        assertTrue(LincolnClusterSettings.remaining(context))
    }

    @Test fun aSavedChoiceStillWins() {
        LincolnClusterSettings.setNavigation(context, false)
        AirPlayPersistence.saveSpeechWithout8And16Khz(context, false)
        AirPlayPersistence.saveNavigationDuckingMedia(context, false)
        assertEquals(false, LincolnClusterSettings.navigation(context))
        assertEquals(false, AirPlayPersistence.loadSpeechWithout8And16Khz(context))
        assertEquals(false, AirPlayPersistence.loadNavigationDuckingMedia(context))
    }
}

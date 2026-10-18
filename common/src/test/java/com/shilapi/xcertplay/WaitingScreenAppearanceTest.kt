package com.shilapi.xcertplay

import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
@LooperMode(LooperMode.Mode.PAUSED)
class WaitingScreenAppearanceTest {
    private lateinit var activity: CarPlayHostActivity

    @Before fun setUp() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
    }

    @After fun tearDown() {
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        ReflectionHelpers.getField<ExecutorService>(activity, "teardownExecutor").shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(activity, "airPlayCommandExecutor").shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    @Test fun darkAppThemeKeepsTheConnectionScreenDarkWhileCarPlayIsInDayMode() {
        assertEquals(WaitingScreenColors.of(true).background, waitingBackground(appNight = true, carPlayNight = false))
    }

    @Test fun lightAppThemeKeepsTheConnectionScreenLightWhileCarPlayIsInNightMode() {
        assertEquals(WaitingScreenColors.of(false).background, waitingBackground(appNight = false, carPlayNight = true))
    }

    @Test fun roundedCarPlaySurroundFollowsTheVehicleThemeNotTheAppTheme() {
        ReflectionHelpers.setField(activity, "appNight", false)
        ReflectionHelpers.setField(activity, "carThemeNight", true)
        val root = CarPlayHostActivity::class.java.getDeclaredMethod("buildContentView")
            .apply { isAccessible = true }.invoke(activity) as ViewGroup
        assertEquals(
            WaitingScreenColors.of(true).background,
            (root.background as? ColorDrawable)?.color,
        )

        // App appearance changes do not alter the vehicle's system theme around CarPlay.
        ReflectionHelpers.setField(activity, "appNight", true)
        ReflectionHelpers.getField<() -> Unit>(activity, "paintWaitingScreen").invoke()
        assertEquals(
            WaitingScreenColors.of(true).background,
            (root.background as? ColorDrawable)?.color,
        )

        ReflectionHelpers.setField(activity, "carThemeNight", false)
        CarPlayHostActivity::class.java.getDeclaredMethod("applyCarPlaySurroundAppearance")
            .apply { isAccessible = true }.invoke(activity)
        assertEquals(
            WaitingScreenColors.of(false).background,
            (root.background as? ColorDrawable)?.color,
        )
    }

    @Test fun onlyFullscreenWithBothSystemBarsHiddenUsesBlackCorners() {
        ReflectionHelpers.setField(activity, "hideTopBar", true)
        ReflectionHelpers.setField(activity, "hideBottomBar", false)
        assertFalse(CarPlayHostActivity::class.java.getDeclaredMethod("isCarPlayFullscreen")
            .apply { isAccessible = true }.invoke(activity) as Boolean)

        ReflectionHelpers.setField(activity, "hideBottomBar", true)
        assertTrue(CarPlayHostActivity::class.java.getDeclaredMethod("isCarPlayFullscreen")
            .apply { isAccessible = true }.invoke(activity) as Boolean)
    }

    private fun waitingBackground(appNight: Boolean, carPlayNight: Boolean): Int {
        ReflectionHelpers.setField(activity, "appNight", appNight)
        ReflectionHelpers.setField(activity, "darkMode", carPlayNight)
        val root = CarPlayHostActivity::class.java.getDeclaredMethod("buildContentView")
            .apply { isAccessible = true }.invoke(activity) as ViewGroup
        val expected = setOf(WaitingScreenColors.of(true).background, WaitingScreenColors.of(false).background)
        return descendants(root).drop(1).mapNotNull { (it.background as? ColorDrawable)?.color }.first { it in expected }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}

package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 27, 33])
class CarPlayCornerRadiusTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun defaultIsRoundedAndSavedValuesAreClampedToTheSliderSteps() {
        preferences().edit().clear().commit()
        assertEquals(0, AirPlayPersistence.loadCarPlayCornerRadiusDp(context))

        AirPlayPersistence.saveCarPlayCornerRadiusDp(context, 27)
        assertEquals(27, AirPlayPersistence.loadCarPlayCornerRadiusDp(context))

        AirPlayPersistence.saveCarPlayCornerRadiusDp(context, 39)
        assertEquals(39, AirPlayPersistence.loadCarPlayCornerRadiusDp(context))

        AirPlayPersistence.saveCarPlayCornerRadiusDp(context, 100)
        assertEquals(AirPlayPersistence.MAX_CARPLAY_CORNER_RADIUS_DP,
            AirPlayPersistence.loadCarPlayCornerRadiusDp(context))

        AirPlayPersistence.saveCarPlayCornerRadiusDp(context, 0)
        assertEquals(0, AirPlayPersistence.loadCarPlayCornerRadiusDp(context))
    }

    @Test fun maskUsesTheHostSurroundColourAndDrawsOnAndroid8() {
        val mask = CarPlayCornerMaskView(context)
        mask.setSurroundColor(Color.rgb(12, 34, 56))
        mask.setCornerRadiusDp(24)
        mask.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(480, android.view.View.MeasureSpec.EXACTLY),
        )
        mask.layout(0, 0, 800, 480)
        mask.draw(Canvas(Bitmap.createBitmap(800, 480, Bitmap.Config.ARGB_8888)))
    }

    @Test fun fullscreenCornersAreBlackAndOtherAreasFollowTheVehicleTheme() {
        assertEquals(Color.BLACK, carPlayCornerSurroundColor(fullscreen = true, vehicleThemeNight = true))
        assertEquals(Color.BLACK, carPlayCornerSurroundColor(fullscreen = true, vehicleThemeNight = false))
        assertEquals(
            WaitingScreenColors.of(true).background,
            carPlayCornerSurroundColor(fullscreen = false, vehicleThemeNight = true),
        )
        assertEquals(
            WaitingScreenColors.of(false).background,
            carPlayCornerSurroundColor(fullscreen = false, vehicleThemeNight = false),
        )
    }

    private fun preferences() =
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
}

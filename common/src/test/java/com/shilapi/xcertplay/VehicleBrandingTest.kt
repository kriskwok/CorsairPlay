package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VehicleBrandingTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clearBranding()
    }

    @After
    fun tearDown() {
        clearBranding()
    }

    @Test
    fun defaultsToLincoln() {
        assertEquals(VehicleBrandPreset.LINCOLN, VehicleBranding.preset(context))
        assertEquals("Lincoln", VehicleBranding.displayName(context))
        assertEquals(R.raw.ic_car_home, VehicleBranding.packagedHomeIconResource(context))
    }

    @Test
    fun fordUsesItsOwnLogoAndHomeIcon() {
        VehicleBranding.setPreset(context, VehicleBrandPreset.FORD)

        assertEquals(VehicleBrandPreset.FORD, VehicleBranding.preset(context))
        assertEquals("Ford", VehicleBranding.displayName(context))
        assertEquals(R.raw.ic_car_home_ford, VehicleBranding.packagedHomeIconResource(context))
        assertNotNull(VehicleBranding.logo(context))
    }

    @Test
    fun presetChangesKeepTheSavedCustomIdentity() {
        AirPlayPersistence.saveOemLabel(context, "My Lincoln")
        context.resources.openRawResource(R.drawable.ic_ford).use {
            AirPlayPersistence.saveCustomAirPlayIcon(context, it.readBytes())
        }
        VehicleBranding.setPreset(context, VehicleBrandPreset.CUSTOM)

        VehicleBranding.setPreset(context, VehicleBrandPreset.FORD)
        assertEquals("Ford", VehicleBranding.displayName(context))
        assertTrue(AirPlayPersistence.loadCustomAirPlayIconFile(context)!!.isFile)
        assertEquals("My Lincoln", AirPlayPersistence.loadOemLabel(context))

        VehicleBranding.setPreset(context, VehicleBrandPreset.CUSTOM)
        assertEquals("My Lincoln", VehicleBranding.displayName(context))
        assertNotNull(VehicleBranding.logo(context))
    }

    @Test
    fun existingCustomizationMigratesToCustomPreset() {
        AirPlayPersistence.saveOemLabel(context, "My car")

        assertEquals(VehicleBrandPreset.CUSTOM, VehicleBranding.preset(context))
        assertEquals("My car", VehicleBranding.displayName(context))
    }

    @Test
    fun legacySettingsMessagesUseTheAppNameButChoicesStayLiteral() {
        VehicleBranding.setPreset(context, VehicleBrandPreset.FORD)

        assertEquals(
            "CorsairPlay steering wheel keys",
            VehicleBranding.localize(R.string.wheel_key_service_label, "Lincoln steering wheel keys"),
        )
        assertEquals(
            "CorsairPlay 方向盘按键",
            VehicleBranding.localize(R.string.wheel_key_service_label, "林肯 方向盘按键"),
        )
        assertEquals(
            "CorsairPlay cluster",
            VehicleBranding.localize(R.string.settings_lincoln_cluster, "Lincoln/Ford cluster"),
        )
        assertEquals(
            "Lincoln",
            VehicleBranding.localize(R.string.car_button_brand_lincoln, "Lincoln"),
        )
        assertEquals(
            "Downloads/Lincoln",
            VehicleBranding.localize(
                R.string.reports_save_to_downloads_diplay,
                "Downloads/Lincoln",
            ),
        )
    }

    private fun clearBranding() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
            .edit().clear().commit()
        AirPlayPersistence.clearCustomAirPlayIcon(context)
    }
}

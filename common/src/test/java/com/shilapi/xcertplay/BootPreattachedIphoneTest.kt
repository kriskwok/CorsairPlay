package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowUsbManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BootPreattachedIphoneTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var device: UsbDevice

    @Before fun reset() {
        ShadowUsbManager.reset()
        AirPlayPersistence.saveAutoStartOnBoot(app, false)
        device = usbDevice(IphoneUsbMatcher.APPLE_VENDOR_ID, 0x12a8)
    }

    @After fun removeDevice() {
        shadowOf(app.getSystemService(UsbManager::class.java)).removeUsbDevice(device)
        ShadowUsbManager.reset()
    }

    @Test fun aPreattachedIphoneLaunchesCarPlayWithoutTheBootUi() {
        append(device)
        var launch: Intent? = null
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) {
                launch = intent
            }
        }

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(CarPlayHostActivity::class.java.name, launch?.component?.className)
        assertEquals(PreattachedIphone.ACTION, launch?.action)
        assertTrue(launch?.flags?.and(Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test fun aNonAppleDeviceDoesNotLaunchCarPlayAtBoot() {
        append(usbDevice(0x1234, 0x5678))
        var launched = false
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) {
                launched = true
            }
        }

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertFalse(launched)
    }

    private fun append(value: UsbDevice) {
        shadowOf(app.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(value, false)
    }

    private fun usbDevice(vendor: Int, product: Int): UsbDevice =
        ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
            ReflectionHelpers.setField(this, "mName", "/dev/bus/usb/001/002")
            ReflectionHelpers.setField(this, "mVendorId", vendor)
            ReflectionHelpers.setField(this, "mProductId", product)
        }
}

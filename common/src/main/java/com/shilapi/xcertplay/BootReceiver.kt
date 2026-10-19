package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log
import com.shilapi.xcertplay.telecom.PhoneAudioRoute
import com.shilapi.xcertplay.transport.IphoneUsbMatcher

/** Starts the CarPlay host after boot for a pre-attached iPhone or the saved startup option. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Starts the watcher for other apps' calls when that setting is on, even without opening DiPlay.
        PhoneAudioRoute.attach(context)
        val launchEnabled = AirPlayPersistence.loadAutoStartOnBoot(context)
        StartupDiagnosticSnapshot.received(context, launchEnabled)
        val preattachedIphone = PreattachedIphone.isAttached(context)
        if (!launchEnabled && !preattachedIphone) return

        val launch = Intent(
            context,
            if (preattachedIphone) CarPlayHostActivity::class.java else DiPlayActivity::class.java,
        ).apply {
            if (preattachedIphone) action = PreattachedIphone.ACTION
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        try {
            context.startActivity(launch)
            StartupDiagnosticSnapshot.launchResult(context)
        } catch (error: RuntimeException) {
            StartupDiagnosticSnapshot.launchResult(context, error)
            Log.w(TAG, "Boot auto-start could not launch DiPlayActivity", error)
        }
    }

    private companion object {
        const val TAG = "xcertplay-boot"
    }
}

/**
 * Android does not replay an attach broadcast when the cable was already connected before boot.
 */
internal object PreattachedIphone {
    const val ACTION = "com.corsairplay.action.PREATTACHED_IPHONE"

    fun isAttached(context: Context): Boolean {
        val usb = context.getSystemService(UsbManager::class.java) ?: return false
        return usb.deviceList.values.any { it.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID }
    }
}

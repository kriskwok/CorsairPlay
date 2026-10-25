package com.shilapi.xcertplay.transport

import android.util.Log
import com.shilapi.xcertplay.shared.BuildConfig

/**
 * The wired bring-up's step-by-step trace: USB descriptors, interface claims, USBMUX framing and
 * NCM packet traffic.
 *
 * Every line here describes how the pipe was built, not what went wrong with it, and a single
 * connect produces hundreds of them (a released build logged 447 `xcertplay-usb` lines in one
 * session, most of them one per USBMUX frame). That is what a developer needs while bringing up a
 * new head unit and what a driver's logcat does not need at all, so the traces are debug-only.
 *
 * Failures are not routed through here: a claim that fails, a reset that errors, a session that
 * times out still logs at warning or error level in release.
 */
internal object UsbTrace {
    const val TAG = "xcertplay-usb"

    fun log(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }
}

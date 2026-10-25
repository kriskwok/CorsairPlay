package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * Resets a wired iPhone on the USB bus, so it re-enumerates by itself.
 *
 * A wired CarPlay session that the host ends leaves the iPhone in CarPlay mode on the same USB
 * node, and that mode takes no new session until the node is re-enumerated. Left to itself the
 * iPhone gets there only after its own timeout — a minute to several on this head unit — which is
 * why reconnecting after DiPlay rebuilt its own stack used to need the cable pulled. A bus reset
 * through the connection's usbfs descriptor does what pulling the cable does: the iPhone drops off
 * the bus and comes back with a fresh node, and the next session starts within seconds.
 *
 * The reset is asked for once everything of DiPlay's has let go of the device: the driver refuses
 * it while another descriptor of ours still holds the device, which is why the caller runs this at
 * the very end of its teardown.
 */
object UsbBusReset {
    /** What the reset did, with enough detail for the connection log to say why it did not. */
    data class Outcome(val ok: Boolean, val detail: String)

    /** How many times to ask for the device, and how long to wait between the asks. */
    private const val OPEN_ATTEMPTS = 3
    private const val OPEN_RETRY_MILLIS = 150L

    fun reset(usbManager: UsbManager, device: UsbDevice): Outcome {
        var detail = "not attempted"
        repeat(OPEN_ATTEMPTS) { attempt ->
            val connection = runCatching { usbManager.openDevice(device) }.getOrNull()
            if (connection == null) {
                detail = "openDevice refused (attempt ${attempt + 1}/$OPEN_ATTEMPTS)"
                if (attempt < OPEN_ATTEMPTS - 1) runCatching { Thread.sleep(OPEN_RETRY_MILLIS) }
                return@repeat
            }
            try {
                val descriptor = runCatching { connection.fileDescriptor }.getOrDefault(-1)
                if (descriptor < 0) return Outcome(false, "usbfs descriptor $descriptor")
                val result = try {
                    UsbBusResetNative.reset(descriptor)
                } catch (error: Throwable) {
                    // The library missing or the symbol not resolvable reads here, not as an errno.
                    return Outcome(
                        false,
                        "native reset unavailable: ${error.javaClass.simpleName}: ${error.message}",
                    )
                }
                return if (result == 0) {
                    Outcome(true, "usbfs reset")
                } else {
                    // A negative errno names the reason: EPERM is a policy refusal, EBUSY means
                    // something still holds the device, ENODEV that it left the bus already.
                    Outcome(false, "USBDEVFS_RESET errno ${-result}")
                }
            } finally {
                runCatching { connection.close() }
            }
        }
        return Outcome(false, detail)
    }
}

private object UsbBusResetNative {
    init {
        System.loadLibrary("xcertplay_usb_reset")
    }

    /** 0 on success, a negative errno otherwise. */
    external fun reset(fileDescriptor: Int): Int
}

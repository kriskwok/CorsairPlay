#include <errno.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>

/*
 * Resets a USB device through the usbfs descriptor an open UsbDeviceConnection holds. The kernel
 * drops the device back to the addressed state and re-enumerates it, which is what makes the
 * iPhone offer a fresh CarPlay configuration again: the same effect as pulling the cable.
 *
 * Returns 0, or a negative errno.
 */
JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_UsbBusResetNative_reset(JNIEnv *env, jclass type, jint fd) {
    (void) env;
    (void) type;
    if (fd < 0) return -EINVAL;
    int result;
    do {
        result = ioctl(fd, USBDEVFS_RESET, 0);
    } while (result < 0 && errno == EINTR);
    return result < 0 ? -errno : 0;
}

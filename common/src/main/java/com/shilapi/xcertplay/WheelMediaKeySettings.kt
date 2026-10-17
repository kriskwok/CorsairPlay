package com.shilapi.xcertplay

import android.content.Context

/**
 * Send the wheel and centre-console previous/next keys to CarPlay while connected.
 *
 * This uses the accessibility service because the stock media app can claim those keys before the
 * CarPlay window receives them. Outside a CarPlay session, or during a call, the keys stay with the
 * head unit.
 */
object WheelMediaKeySettings {
    private const val PREFS = "diplay_wheel_media_keys"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        WheelKeyService.settingsChanged()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

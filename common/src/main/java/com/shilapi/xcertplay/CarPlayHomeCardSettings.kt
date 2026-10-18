package com.shilapi.xcertplay

import android.content.Context

/** Opt-in for mirroring CarPlay media metadata to the head unit's home-screen card. */
internal object CarPlayHomeCardSettings {
    private const val PREFS = "diplay_home_card"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

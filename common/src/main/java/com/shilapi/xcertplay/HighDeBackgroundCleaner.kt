package com.shilapi.xcertplay

import android.app.ActivityManager
import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Reclaims AutoNavi's cached processes after it leaves the foreground.
 *
 * Android's background-process API won't touch a foreground activity or its foreground
 * navigation service, so an active route keeps running.
 */
internal object HighDeBackgroundCleaner {
    const val TARGET_PACKAGE = "com.autonavi.amapauto"
    private const val PREFS = "diplay"
    private const val KEY_ENABLED = "clean_autonavi_background"
    private const val POLL_SECONDS = 60L

    private val lock = Any()

    @Volatile
    private var executor: ScheduledExecutorService? = null

    fun enabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) cleanNow(context)
    }

    fun start(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (executor != null) return
            executor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "corsairplay-autonavi-cleaner").apply { isDaemon = true }
            }.also { worker ->
                worker.scheduleWithFixedDelay(
                    { if (enabled(appContext)) cleanNow(appContext) },
                    0L,
                    POLL_SECONDS,
                    TimeUnit.SECONDS,
                )
            }
        }
    }

    fun cleanNow(context: Context) {
        runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.killBackgroundProcesses(TARGET_PACKAGE)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

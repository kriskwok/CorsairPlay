package com.shilapi.xcertplay

import android.app.ActivityManager
import android.content.Context
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
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
    internal const val PRESSURE_THRESHOLD_BYTES = 640L * 1024L * 1024L
    private const val PRESSURE_SHELL_TIMEOUT_MILLIS = 4_000

    private val lock = Any()

    @Volatile
    private var executor: ScheduledExecutorService? = null

    fun enabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            val appContext = context.applicationContext
            Thread({ cleanNow(appContext) }, "corsairplay-autonavi-clean-on-enable").apply {
                isDaemon = true
                start()
            }
        }
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
        val memory = memoryInfo(context)
        if (memory != null && shouldPressureClean(memory.availMem) && pressureCleanSucceeded(context)) {
            return
        }
        normalClean(context)
    }

    private fun normalClean(context: Context) {
        runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.killBackgroundProcesses(TARGET_PACKAGE)
        }
    }

    /**
     * This firmware ignores the advisory background kill for AutoNavi even at oom_adj 200.
     * The local shell can force-stop it, but only when its activity is not the visible window.
     */
    private fun pressureCleanSucceeded(context: Context): Boolean = runCatching {
        LocalAdb(AdbKeys.load(context)).use { adb ->
            if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) return@use false
            val output = adb.shell(pressureCleanupCommand(), PRESSURE_SHELL_TIMEOUT_MILLIS)
                ?: return@use false
            output.lineSequence().any { it.trim() in setOf("forced", "visible", "absent") }
        }
    }.getOrDefault(false)

    private fun memoryInfo(context: Context): ActivityManager.MemoryInfo? =
        context.getSystemService(ActivityManager::class.java)?.let { manager ->
            ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        }

    internal fun pressureCleanupCommand(): String = """
        pids=${'$'}(pidof $TARGET_PACKAGE)
        if [ -z "${'$'}pids" ]; then echo absent; exit 0; fi
        focus=${'$'}(dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | grep '$TARGET_PACKAGE')
        if [ -n "${'$'}focus" ]; then echo visible; exit 0; fi
        am force-stop $TARGET_PACKAGE
        echo forced
    """.trimIndent()

    internal fun shouldPressureClean(availableBytes: Long): Boolean =
        availableBytes <= PRESSURE_THRESHOLD_BYTES

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

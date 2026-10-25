package com.shilapi.xcertplay.orchestration

import android.content.Context

/**
 * The USB enumeration (device node) of the iPhone on which DiPlay last started a wired CarPlay session.
 *
 * After DiPlay ends that session itself, as Save and reconnect does, the iPhone stays in CarPlay mode on the
 * same node. A new session there passes iAP2 and StartSession, but the iPhone opens no AirPlay connection
 * until it resets USB 30–150 s later; a fresh CarPlay mode request instead connected within seconds (#335).
 * The node changes whenever the iPhone re-enumerates, so a match means it is still in that session's mode.
 * A node DiPlay itself closed ([hostClosed]) is not opened again at all: the next attempt waits for the
 * reset, because a session there would re-arm the mode and hold the reset off for good.
 */
internal object WiredSessionMemory {
    private const val PREFS = "diplay_wired_session"
    private const val KEY_DEVICE = "device"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_CLOSED_BY_DIPLAY = "closed_by_diplay"

    /**
     * An app update replaces the process while the iPhone can remain in the previous wired session.
     * Keep the marker long enough to cover that restart, but expire it once iOS has had time to reset
     * the USB session by itself.
     */
    private const val MAX_AGE_MILLIS = 5 * 60_000L

    @Volatile private var startedOn: String? = null

    fun started(context: Context, deviceName: String) {
        remember(context, deviceName, closedByDiPlay = false)
    }

    /**
     * DiPlay closed that node's live session itself (a restart, as a layout change now avoids), so
     * the iPhone is still in CarPlay mode there and takes no new session until it resets USB.
     * Opening that stale enumeration instead takes StartSession, which re-arms the mode and holds
     * the reset off, so the next attempt must wait for a new node rather than reuse it.
     */
    fun hostClosed(context: Context, deviceName: String) {
        remember(context, deviceName, closedByDiPlay = true)
    }

    /** Whether DiPlay itself closed the session on this node, and the node is recent enough to tell. */
    fun closedByDiPlayOn(context: Context, deviceName: String): Boolean {
        if (!startedOn(context, deviceName)) return false
        return prefs(context).getBoolean(KEY_CLOSED_BY_DIPLAY, false)
    }

    private fun remember(context: Context, deviceName: String, closedByDiPlay: Boolean) {
        startedOn = deviceName
        prefs(context).edit()
            .putString(KEY_DEVICE, deviceName)
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .putBoolean(KEY_CLOSED_BY_DIPLAY, closedByDiPlay)
            .commit()
    }

    fun startedOn(context: Context, deviceName: String): Boolean {
        if (startedOn == deviceName) return true
        val preferences = prefs(context)
        val saved = preferences.getString(KEY_DEVICE, null) ?: return false
        val age = System.currentTimeMillis() - preferences.getLong(KEY_STARTED_AT, 0L)
        if (age !in 0..MAX_AGE_MILLIS) {
            clear(context)
            return false
        }
        startedOn = saved
        return saved == deviceName
    }

    internal fun clear(context: Context) {
        startedOn = null
        prefs(context).edit().clear().commit()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

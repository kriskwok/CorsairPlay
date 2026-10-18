package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import com.shilapi.xcertplay.glance.CarPlayGlance

/**
 * Supplies the car launcher's CarPlay card with the current song and lyric line.
 *
 * The launcher receives a two-line card from this explicit package broadcast instead of polling
 * Android's media sessions. The payload is sent only when one of its three values changes.
 */
internal object CarPlayHomeCard {
    const val ACTION_STATE = "com.corsairplay.action.CARPLAY_CARD_STATE"
    const val ACTION_QUERY = "com.corsairplay.action.CARPLAY_CARD_QUERY"
    const val ACTION_TOGGLE = "com.corsairplay.action.CARPLAY_CARD_TOGGLE"
    const val ACTION_NEXT = "com.corsairplay.action.CARPLAY_CARD_NEXT"
    const val EXTRA_TITLE = "title"
    const val EXTRA_LYRIC = "lyric"
    const val EXTRA_PLAYING = "playing"

    private const val LAUNCHER_PACKAGE = "com.baidu.xiaoduos.launcher"
    private var context: Context? = null
    private var lastKey: String? = null
    private val listener: (CarPlayGlance.Snapshot) -> Unit = { publish(it) }

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        this.context = app
        CarPlayGlance.removeListener(listener)
        CarPlayGlance.addListener(listener)
        publish(CarPlayGlance.snapshot(), force = true)
    }

    /** Apply an on/off change immediately, including restoring the default card when disabled. */
    fun refresh(context: Context) {
        synchronized(this) { this.context = context.applicationContext }
        publish(CarPlayGlance.snapshot(), force = true)
    }

    /** Answer a launcher query even when the card was rebound after the last state change. */
    fun respond(context: Context) {
        synchronized(this) { this.context = context.applicationContext }
        publish(CarPlayGlance.snapshot(), force = true)
    }

    internal data class Content(val title: String, val lyric: String, val playing: Boolean)

    internal fun content(snapshot: CarPlayGlance.Snapshot, enabled: Boolean = true): Content {
        if (!enabled) return Content("", "", false)
        val rawTitle = normalized(snapshot.songTitle)
        val songName = normalized(snapshot.songArtist) ?: rawTitle
        val lyric = rawTitle?.takeIf { it != songName }.orEmpty()
        return Content(
            title = songName.orEmpty(),
            lyric = lyric,
            playing = snapshot.playing && songName != null,
        )
    }

    private fun publish(snapshot: CarPlayGlance.Snapshot, force: Boolean = false) {
        val app = synchronized(this) { context } ?: return
        val content = content(snapshot, CarPlayHomeCardSettings.enabled(app))
        val key = "${content.title}|${content.lyric}|${content.playing}"
        synchronized(this) {
            if (!force && key == lastKey) return
            lastKey = key
        }
        app.sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(LAUNCHER_PACKAGE)
                .putExtra(EXTRA_TITLE, content.title)
                .putExtra(EXTRA_LYRIC, content.lyric)
                .putExtra(EXTRA_PLAYING, content.playing),
        )
    }

    private fun normalized(value: String?): String? =
        value?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }
}

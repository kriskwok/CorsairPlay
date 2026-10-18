package com.shilapi.xcertplay.glance

import com.shilapi.xcertplay.hud.BydHudRouteState
import com.shilapi.xcertplay.hud.ClusterSongState
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What CarPlay is doing, at a glance, for widgets and other screens outside CarPlay: the next
 * maneuver from iAP2 route guidance (0x5201/0x5202) and the song from NowPlayingUpdate (0x5001).
 * Fed by every CarPlay session, whatever the BYD output settings.
 */
object CarPlayGlance {
    /** One state of the glance; [maneuverType] is Apple's RouteGuidanceManeuverType, null without a route. */
    data class Snapshot(
        val connected: Boolean = false,
        val maneuverType: Int? = null,
        /**
         * A route is still running even though [maneuverType] may be momentarily null (the iPhone's
         * empty current-maneuver list, or the stale window). Outputs that must not flicker — the
         * Lincoln/Ford cluster — keep the last instruction while this is true and only clear when a
         * route genuinely ends.
         */
        val routeActive: Boolean = false,
        /**
         * Guidance ended for a stated reason (arrived, the driver ended it, or the session dropped),
         * rather than the route merely going quiet for a while. Lets a cluster clear a finished card
         * without blinking when the iPhone simply has nothing new to send.
         */
        val routeEnded: Boolean = false,
        val drivingSide: Int = 0,
        val distanceMeters: Int = 0,
        val road: String = "",
        val arrivalEpochSeconds: Long? = null,
        val remainingSeconds: Long? = null,
        val remainingMeters: Long? = null,
        val song: String? = null,
        /**
         * The raw iAP2 title field, for an output that shows only the current line (the
         * Lincoln/Ford cluster). [song] stays the combined "title — artist" the widget shows;
         * music apps put the per-line lyric in the title field and the song name in the artist.
         */
        val songTitle: String? = null,
        /** The separate iAP2 artist field. QQ Music and similar apps put the song name here. */
        val songArtist: String? = null,
        val playing: Boolean = false,
    )

    private val route = BydHudRouteState()
    private val song = ClusterSongState()
    private var connected = false
    private var last = Snapshot()

    // More than one consumer follows the glance (the home-screen widget and, on a Lincoln/Ford
    // head unit, the cluster output). A single listener slot let the later attach silently
    // replace the earlier one, so forwarding stopped as soon as CarPlay opened the widget.
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()

    /**
     * Called with each new snapshot, on the thread that changed it. Setting this replaces every
     * listener; prefer [addListener]/[removeListener] when another consumer may also be attached.
     */
    var listener: ((Snapshot) -> Unit)?
        get() = listeners.firstOrNull()
        set(value) {
            listeners.clear()
            if (value != null) listeners.add(value)
        }

    fun addListener(l: (Snapshot) -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: (Snapshot) -> Unit) {
        listeners.remove(l)
    }

    private fun notifyListeners(snapshot: Snapshot) {
        for (l in listeners) runCatching { l(snapshot) }
    }

    /** Refresh time-dependent guidance even when no new metadata frame has arrived. */
    fun snapshot(): Snapshot {
        val (changed, current) = synchronized(this) { publishLocked() to last }
        changed?.let { notifyListeners(it) }
        return current
    }

    fun onFrame(frame: Iap2Frame) {
        val changed = synchronized(this) {
            when (frame.messageId) {
                BydHudRouteState.ROUTE_GUIDANCE_UPDATE, BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE ->
                    runCatching { route.accept(frame.messageId, frame.payload) }
                ClusterSongState.NOW_PLAYING_UPDATE -> runCatching { song.accept(frame) }
                else -> return
            }
            publishLocked()
        }
        changed?.let { notifyListeners(it) }
    }

    fun setConnected(next: Boolean) {
        val changed = synchronized(this) {
            if (connected == next) return
            connected = next
            if (!next) {
                route.clear()
                song.clear()
            }
            publishLocked()
        }
        changed?.let { notifyListeners(it) }
    }

    private fun publishLocked(): Snapshot? {
        val maneuver = route.currentApple()
        val current = song.current()
        val next = Snapshot(
            connected = connected,
            maneuverType = maneuver?.type,
            routeActive = route.routeActive(),
            // A dropped session is reported through `connected`, not as a guidance end.
            routeEnded = connected && route.routeEnded(),
            drivingSide = maneuver?.drivingSide ?: 0,
            distanceMeters = maneuver?.distanceMeters ?: 0,
            road = maneuver?.road.orEmpty(),
            arrivalEpochSeconds = maneuver?.arrivalEpochSeconds,
            remainingSeconds = maneuver?.remainingSeconds,
            remainingMeters = maneuver?.remainingMeters,
            song = current?.text,
            songTitle = current?.line,
            songArtist = current?.artist,
            playing = current?.playing ?: false,
        )
        if (next == last) return null
        last = next
        return next
    }
}

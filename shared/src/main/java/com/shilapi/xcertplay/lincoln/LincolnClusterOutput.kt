package com.shilapi.xcertplay.lincoln

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.shilapi.xcertplay.glance.CarPlayGlance

/**
 * Forwards CarPlay guidance and now-playing to a Lincoln/Ford instrument-cluster app.
 *
 * Navigation speaks the head unit's own Baidu protocol (see [LincolnClusterProtocol]): a start/end
 * announcement, turn-by-turn broadcasts with every alias the stock app fills, and the
 * [LincolnHudSocketServer] HUD socket. Now playing keeps the single card broadcast the head unit
 * already accepts.
 *
 * The HUD socket is offered for the whole CarPlay session rather than only while a route runs: the
 * cluster's own navigation client (the Baidu NaviSDK remote on the head unit) connects to it on its
 * own retry schedule, and a client that connects only after the cluster's navigation view has been
 * opened draws an empty "0 m" card until its handshake completes.
 *
 * This follows [CarPlayGlance], the same snapshot the home-screen widget uses, so it does not read
 * the widget or depend on resource ids the way the standalone bridge did. It is off by default on
 * other hardware and only broadcasts when [LincolnClusterSettings] has it enabled and a connection
 * is up.
 */
object LincolnClusterOutput {
    private const val TAG = "DiPlay-Lincoln-Cluster"

    @Volatile private var context: Context? = null
    @Volatile private var listener: ((CarPlayGlance.Snapshot) -> Unit)? = null

    // Guards the "owned" flags so a stale snapshot cannot clear state another write just set.
    private val lock = Any()
    /** The cluster was told guidance started, so it has to be told when it ends. */
    @Volatile private var navOwned = false
    private var musicOwned = false
    // What we last told the cluster, so a missing route actively clears a stale "arrived" card
    // left by an earlier session instead of only stopping new writes.
    private var lastNavKey: String? = null
    // The instruction last written to the cluster, so the same one can be repeated. The cluster
    // retires a nav card it has not heard about for a few seconds, which read as the card blinking
    // out and back on the way to the next turn; the working standalone bridge re-sent every second,
    // and so does this.
    private var lastNavIndex: Int? = null
    private var lastNavDistance: Int? = null
    private var lastNavRoad: String = ""
    // The junction identity the last packet carried. The cluster's own adapter restarts its progress
    // bar whenever these coordinates change, so they are only renewed on purpose.
    private var lastNavToken: LincolnManeuverToken? = null
    private val maneuverTracker = LincolnManeuverTracker()
    private var lastNavSentNs = 0L
    private var lastMusicKey: String? = null
    private var lastRemainKey: String? = null
    private var remainOwned = false
    private var navSent = 0
    private var musicSent = 0
    private var remainSent = 0
    private var navStarts = 0
    private var navStops = 0
    /** Snapshots a delayed consumer was handed after a newer one had already been published. */
    private var supersededSnapshots = 0
    private var lastReason = "init"
    // The last state acted on, so the 1 s tick is cheap: an unchanged glance with unchanged
    // switches is skipped, while a real change (a route expiring, or a switch flipped without a
    // new frame) still runs the forwarding and clearing below.
    private var lastSnapshot: CarPlayGlance.Snapshot? = null
    private var lastNavEnabled: Boolean? = null
    private var lastMusicEnabled: Boolean? = null
    // The iPhone sends a transient NoRouteSet for a few tens of milliseconds when a route starts or
    // is rerouted. Acting on it cleared the card and drew it again, which is the blink that was
    // still visible after the 30 s staleness fix. An end has to stick before the cluster is cleared.
    private var endedSinceNs = 0L
    // When the current route's first instruction was seen. The iPhone publishes a 0 m placeholder
    // for the first frames of a route, before it has a GPS distance for the first leg; see
    // [START_DISTANCE_HOLD_NS].
    private var firstManeuverNs = 0L
    /** Injectable monotonic clock; the settle window is time-based, so tests advance it. */
    internal var nanoTime: () -> Long = System::nanoTime
    /** The HUD socket guidance is also published to; a test replaces it so no port is bound. */
    internal var hudSocket: LincolnHudSocket = LincolnHudSocketServer

    // Guidance has expiry deadlines (a stale route retires ~30 s after the last frame, and an
    // empty maneuver list hides after 3 s). Those only advance when snapshot() is called, and a
    // head unit without the home-screen widget placed never calls it, so a finished route's last
    // instruction — Apple Maps' "arrived" card — stayed on the cluster until the next connection.
    // Tick the glance ourselves so the same expiry the widget relies on also clears the cluster.
    private const val TICK_MILLIS = 1_000L
    // Repeat the current card at this cadence while it is unchanged. The cluster hides a nav card it
    // has not heard about within a few seconds; the firmware's own standalone bridge re-sent every
    // second, which is why its card was steady.
    private const val REPEAT_NAV_NS = 1_000_000_000L
    /**
     * An end must be reported this long before the cluster card is cleared. A route start or a
     * reroute sends a momentary NoRouteSet; acting on it at once blinked the card off and on.
     */
    private const val END_SETTLE_NS = 1_000_000_000L
    /**
     * How long the iPhone's 0 m route-start placeholder is held back before it is shown anyway.
     * The placeholder lasts a frame or two (measured at ~30 ms on the car) and the cluster's own
     * navigation client polls the socket about once a second, so publishing it opened the cluster's
     * navigation view on an empty "0 m" card. A maneuver that genuinely starts at 0 m still reaches
     * the cluster once this window is up, either from the next frame or from the tick.
     */
    private const val START_DISTANCE_HOLD_NS = 3_000_000_000L
    @Volatile private var ticker: HandlerThread? = null
    @Volatile private var tickHandler: Handler? = null
    /**
     * The cluster drops everything it was told when the car's audio source changes, which the
     * audio-off key does (`AudioSource have changed, clear TP filter String`). Forget what was sent
     * so the next tick puts the cards back instead of assuming they are still there.
     */
    private val audioOffListener: (Boolean) -> Unit = {
        synchronized(lock) {
            // The tick skips an unchanged snapshot, so force the next one to rewrite the cards.
            lastSnapshot = null
            lastMusicKey = null
            lastNavKey = null
            lastRemainKey = null
            lastNavSentNs = 0L
            // The guidance announcement goes with the rest, so it is made again on the next tick.
            navOwned = false
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (context == null) return
            onPublishedSnapshot(CarPlayGlance.snapshot())
            tickHandler?.postDelayed(this, TICK_MILLIS)
        }
    }

    /** Called once from the app; attaches to the glance stream and forwards from then on. */
    fun attach(appContext: Context) {
        val app = appContext.applicationContext
        context = app
        if (listener != null) return
        val next: (CarPlayGlance.Snapshot) -> Unit = { onPublishedSnapshot(it) }
        listener = next
        CarPlayGlance.addListener(next)
        com.shilapi.xcertplay.media.CarAudioOff.attach(app)
        com.shilapi.xcertplay.media.CarAudioOff.addListener(audioOffListener)
        startTicking()
        // A session may already be running when the setting is enabled.
        onPublishedSnapshot(CarPlayGlance.snapshot())
    }

    fun detach() {
        listener?.let { CarPlayGlance.removeListener(it) }
        listener = null
        com.shilapi.xcertplay.media.CarAudioOff.removeListener(audioOffListener)
        context = null
        stopTicking()
        hudSocket.stop()
        synchronized(lock) {
            navOwned = false
            musicOwned = false
            remainOwned = false
            lastNavKey = null
            lastNavIndex = null
            lastNavDistance = null
            lastNavRoad = ""
            lastNavToken = null
            maneuverTracker.reset("detached")
            lastNavSentNs = 0L
            lastMusicKey = null
            lastRemainKey = null
            lastSnapshot = null
            lastNavEnabled = null
            lastMusicEnabled = null
            endedSinceNs = 0L
            firstManeuverNs = 0L
            supersededSnapshots = 0
        }
    }

    fun statusLine(): String = synchronized(lock) {
        "navSent=$navSent start=$navStarts stop=$navStops owned=$navOwned" +
            " musicSent=$musicSent remainSent=$remainSent" +
            " superseded=$supersededSnapshots" +
            " hud(${hudSocket.diagnostics()})"
    }

    /**
     * Entry point for a glance notification or the ticker. The snapshot handed in can already be
     * stale: [CarPlayGlance] notifies listeners outside its own lock, so a slow consumer can be
     * called with a state a newer frame has replaced. Acting on it re-sent an older distance (the
     * cluster then computed a distance increase and restarted its junction), so the latest published
     * state is read under this output's lock instead. Dropping the stale call would leave the card
     * frozen whenever the newest notification is blocked elsewhere, so it is replaced, not skipped.
     */
    private fun onPublishedSnapshot(snapshot: CarPlayGlance.Snapshot) {
        val current = synchronized(lock) {
            val latest = CarPlayGlance.latestPublished()
            if (snapshot !== latest) {
                supersededSnapshots++
                Log.i(TAG, "coalesced superseded glance index=${snapshot.maneuverIndex} " +
                    "distance=${snapshot.distanceMeters} " +
                    "generation=${snapshot.routeProgressGeneration}")
            }
            latest
        }
        onSnapshot(current)
    }

    private fun startTicking() {
        if (tickHandler != null) return
        val thread = HandlerThread("diplay-lincoln-cluster").apply { start() }
        ticker = thread
        val handler = Handler(thread.looper)
        tickHandler = handler
        handler.postDelayed(tick, TICK_MILLIS)
    }

    private fun stopTicking() {
        tickHandler?.removeCallbacksAndMessages(null)
        tickHandler = null
        ticker?.quitSafely()
        ticker = null
    }

    /** A short reason the output is currently idle, for the settings screen. */
    fun reason(snapshot: CarPlayGlance.Snapshot): String = when {
        !snapshot.connected -> "DiPlay 未连接 CarPlay"
        snapshot.maneuverType == null && snapshot.song == null -> "CarPlay 已连接，暂无导航或歌曲信息"
        else -> "就绪"
    }

    private fun onSnapshot(snapshot: CarPlayGlance.Snapshot) {
        val app = context ?: return
        val navEnabled = LincolnClusterSettings.navigation(app)
        val musicEnabled = LincolnClusterSettings.music(app)
        // The socket is offered for the whole session, not from the first instruction: the cluster's
        // navigation client connects on its own retry schedule, and a connection that only happens
        // after the navigation view opened leaves the cluster on an empty card for a few seconds.
        if (navEnabled && snapshot.connected) hudSocket.start() else hudSocket.stop()
        if (snapshot == lastSnapshot && navEnabled == lastNavEnabled && musicEnabled == lastMusicEnabled) {
            // Nothing changed, but the cluster still retires a nav card it has not heard about within
            // a few seconds, so keep repeating the current one (the send itself is throttled). The
            // route total is re-checked here too: an end that only settles later is reported by an
            // unchanged snapshot, and it has to clear both the card and the remaining readout.
            if (navEnabled && snapshot.connected) {
                synchronized(lock) {
                    sendRemainLocked(app, snapshot)
                    maintainNavLocked(app, snapshot)
                }
            }
            return
        }
        lastSnapshot = snapshot
        lastNavEnabled = navEnabled
        lastMusicEnabled = musicEnabled
        val index = LincolnClusterProtocol.turnIndex(snapshot.maneuverType, snapshot.drivingSide)
        val distance = LincolnClusterProtocol.roundDistance(snapshot.distanceMeters)
        lastReason = when {
            !navEnabled && !musicEnabled -> "switches off"
            !snapshot.connected -> "not connected"
            snapshot.maneuverType == null && snapshot.routeEnded -> "connected, route ended"
            snapshot.maneuverType == null -> "connected, no new guidance"
            distance == null -> "distance out of range ${snapshot.distanceMeters}"
            !LincolnClusterProtocol.hasTurnIcon(index) -> "unmapped maneuver ${snapshot.maneuverType}"
            else -> "guidance index=$index ${distance}m '${snapshot.road}'"
        }
        Log.i(TAG, "glance connected=${snapshot.connected} maneuver=${snapshot.maneuverType} " +
            "index=$index distance=${snapshot.distanceMeters} road='${snapshot.road}' " +
            "song='${snapshot.song}' playing=${snapshot.playing} " +
            "remain=${snapshot.remainingMeters}/${snapshot.remainingSeconds} " +
            "routeActive=${snapshot.routeActive} routeEnded=${snapshot.routeEnded} " +
            "nav=$navEnabled music=$musicEnabled -> $lastReason")
        if (!navEnabled && !musicEnabled) {
            synchronized(lock) {
                endNavLocked(app)
                clearRemainLocked(app)
                clearMusicLocked(app)
            }
            return
        }
        synchronized(lock) {
            if (snapshot.routeEnded) {
                if (endedSinceNs == 0L) endedSinceNs = nanoTime()
            } else {
                endedSinceNs = 0L
            }
            if (navEnabled && snapshot.connected) {
                // The route total is handled first: a settled end clears it, and the navigation
                // path below resets the settle timestamp when it closes the navigation state.
                sendRemainLocked(app, snapshot)
                sendNavLocked(app, snapshot)
            } else {
                clearRemainLocked(app)
                endNavLocked(app)
            }
            if (musicEnabled && snapshot.connected) sendMusicLocked(app, snapshot) else clearMusicLocked(app)
        }
    }

    private fun sendNavLocked(app: Context, snapshot: CarPlayGlance.Snapshot) {
        val index = LincolnClusterProtocol.turnIndex(snapshot.maneuverType, snapshot.drivingSide)
        val distance = LincolnClusterProtocol.roundDistance(snapshot.distanceMeters)
        if (snapshot.routeEnded) {
            // A settled end invalidates the junction identity. The brief NoRouteSet inside its grace
            // window keeps it, because a reroute sends the same shape of frame and the adapter would
            // otherwise restart the bar in the middle of a leg.
            if (!snapshot.routeEndPending) maneuverTracker.reset("route-ended")
            maintainNavLocked(app, snapshot)
            return
        }
        if (snapshot.routeProgressPending) {
            // The iPhone is recalculating, so the distance it last reported belongs to the old
            // baseline. Keep the last successful packet and wait for guidance to resume with a new
            // distance instead of establishing the new junction from stale metres.
            maintainNavLocked(app, snapshot)
            return
        }
        if (snapshot.maneuverType == null) {
            // No current instruction can mean three things: the iPhone's brief empty-maneuver frame
            // (every few seconds, and while rerouting), a route whose next turn is simply not
            // changing (the iAP2 route update goes quiet while parked at a light), or a real end.
            // Only a real end — Arrived, NoRouteSet, or a session drop — clears the card. Anything
            // else keeps the last instruction and re-sends it on the repeat cadence, because the
            // cluster hides a card it has not heard about within a few seconds. Clearing on
            // `routeActive` (a 30 s staleness timer) made the card vanish and come back while the
            // navigation was still running.
            maintainNavLocked(app, snapshot)
            return
        }
        if (distance == null) {
            endNavLocked(app)
            return
        }
        if (firstManeuverNs == 0L) firstManeuverNs = nanoTime()
        if (startPlaceholderHeld(distance)) {
            // The route just started and the iPhone has no distance for its first leg yet. Holding
            // this back is what keeps the cluster from opening on the placeholder card; the first
            // real distance follows within a frame or two.
            Log.i(TAG, "holding the 0m route-start placeholder '${snapshot.road}'")
            return
        }
        // Guidance is running: announce it once, then keep the card up. The announcement is what
        // lets the cluster (and the launcher's navigation card) switch to navigation at all.
        beginNavLocked(app)
        if (!navOwned) return
        if (!LincolnClusterProtocol.hasTurnIcon(index)) {
            // An unmapped maneuver must not draw a wrong arrow; hold the card that is already there,
            // the same rule as an iPhone frame whose current-maneuver list is momentarily empty.
            maintainNavLocked(app, snapshot)
            return
        }
        val road = LincolnClusterProtocol.streetName(snapshot.road)
        val token = maneuverTracker.update(
            snapshot.maneuverIndex,
            LincolnClusterProtocol.turnName(index),
            snapshot.road,
            snapshot.distanceMeters,
            snapshot.routeProgressGeneration,
            snapshot.distanceRevision,
            nanoTime(),
        )
        val metres = maneuverTracker.distanceMeters
        val key = "$index|$metres|$road|$token|${snapshot.remainingMeters}|${snapshot.remainingSeconds}"
        val changed = key != lastNavKey
        // Repeat the current card at least once a second even when nothing changed: the cluster hides
        // a nav card it has not heard about within a few seconds, so a long leg or a red light made it
        // blink out and back. The firmware's own bridge re-sent every second.
        val due = nanoTime() - lastNavSentNs >= REPEAT_NAV_NS
        if (changed || due) {
            if (navTurnByTurn(app, index, metres, road, token)) {
                lastNavKey = key
                lastNavIndex = index
                lastNavDistance = metres
                lastNavRoad = road
                lastNavToken = token
                lastNavSentNs = nanoTime()
                navSent++
                if (changed) {
                    Log.i(TAG, "nav $index ${metres}m '${road}' raw=${snapshot.distanceMeters} " +
                        "reason=${maneuverTracker.resetReason} (#$navSent)")
                }
            }
        }
    }

    /**
     * Re-send the card already on the cluster while the route is still running but the iPhone's
     * current-maneuver list is briefly empty. The cluster hides a card it has not heard about within
     * a few seconds, so without this the held card would still time out and blink.
     */
    private fun maintainNavLocked(app: Context, snapshot: CarPlayGlance.Snapshot) {
        // A stated end still has to stick: starting or rerouting a route sends NoRouteSet for a few
        // tens of milliseconds, and clearing on that produced a visible blink at every route start.
        if (snapshot.routeEnded) {
            if (endedSinceNs == 0L) endedSinceNs = nanoTime()
        } else {
            endedSinceNs = 0L
        }
        if (lastNavIndex == null) {
            // Nothing on the cluster yet. A start placeholder that was held back may have run out
            // its window while the iPhone sent nothing new, so draw it from here; the frame path is
            // not guaranteed to run again for a route that simply stands still.
            if (canDrawLocked(snapshot)) {
                sendNavLocked(app, snapshot)
                return
            }
            // Only a settled end takes the state down.
            if (snapshot.routeEnded && endSettled()) endNavLocked(app)
            return
        }
        // Re-announce if something dropped the guidance state under us (an audio-source change).
        beginNavLocked(app)
        if (!navOwned) return
        if (snapshot.routeEnded) {
            if (!endSettled()) {
                repeatLastNavLocked(app)
                return
            }
            endNavLocked(app)
            return
        }
        // Quiet guidance, or the iPhone's empty current-maneuver frame: hold the card and keep it
        // alive on the cluster's own timeout.
        repeatLastNavLocked(app)
    }

    private fun endSettled(): Boolean {
        val since = endedSinceNs
        return since != 0L && nanoTime() - since >= END_SETTLE_NS
    }

    /** Whether the snapshot holds an instruction the cluster can be drawn with right now. */
    private fun canDrawLocked(snapshot: CarPlayGlance.Snapshot): Boolean {
        if (snapshot.maneuverType == null) return false
        val index = LincolnClusterProtocol.turnIndex(snapshot.maneuverType, snapshot.drivingSide)
        if (!LincolnClusterProtocol.hasTurnIcon(index)) return false
        val distance = LincolnClusterProtocol.roundDistance(snapshot.distanceMeters) ?: return false
        return !startPlaceholderHeld(distance)
    }

    /**
     * True while the route's first instruction is the iPhone's 0 m placeholder and nothing has been
     * drawn yet, so an empty "0 m" card cannot open the cluster's navigation view. Bounded by
     * [START_DISTANCE_HOLD_NS] so a maneuver that genuinely starts at 0 m still shows up.
     */
    private fun startPlaceholderHeld(distance: Int): Boolean {
        if (distance != 0 || lastNavIndex != null) return false
        val first = firstManeuverNs
        return first != 0L && nanoTime() - first < START_DISTANCE_HOLD_NS
    }

    private fun repeatLastNavLocked(app: Context) {
        val index = lastNavIndex ?: return
        val distance = lastNavDistance ?: return
        val token = lastNavToken ?: return
        if (nanoTime() - lastNavSentNs < REPEAT_NAV_NS) return
        if (navTurnByTurn(app, index, distance, lastNavRoad, token)) {
            lastNavSentNs = nanoTime()
            navSent++
        }
    }

    /** Tell the cluster that guidance started, once per run. */
    private fun beginNavLocked(app: Context) {
        if (navOwned) return
        val opened = broadcast(app, Intent(LincolnClusterProtocol.ACTION_NAV_ACTIVE_OPEN))
        val standard = broadcast(app, standardSend(LincolnClusterProtocol.VALUE_KEY_TYPE_NAV_START))
        val announced = broadcast(app, Intent(LincolnClusterProtocol.ACTION_START_NAVIGATION), target = null)
        // The earlier channel its own navigation adapter reads; it is what carries the junction
        // identity the adapter turns into the progress bar.
        val state = broadcast(app, Intent(LincolnClusterProtocol.ACTION_NAV_STATE)
            .putExtra(LincolnClusterProtocol.EXTRA_STATE, LincolnClusterProtocol.STATE_ON))
        if (!opened && !standard && !announced && !state) return
        navOwned = true
        navStarts++
        Log.i(TAG, "nav start open=$opened standard=$standard announced=$announced state=$state (#$navStarts)")
    }

    /** Tell the cluster guidance ended, then release the navigation state. */
    private fun endNavLocked(app: Context) {
        // The instruction state always goes, even when the route ended before anything was
        // announced: a start placeholder held back for the first frames of the route must not
        // leak into the route that follows.
        lastNavKey = null
        lastNavIndex = null
        lastNavDistance = null
        lastNavRoad = ""
        lastNavToken = null
        maneuverTracker.reset("navigation-cleared")
        lastNavSentNs = 0L
        endedSinceNs = 0L
        firstManeuverNs = 0L
        // Send the end whenever the cluster was told navigation started, even if a later snapshot
        // already dropped the flag, so a finished route cannot leave "arrived" on the cluster.
        if (!navOwned) return
        navOwned = false
        navStops++
        val closed = broadcast(app, Intent(LincolnClusterProtocol.ACTION_NAV_ACTIVE_CLOSE))
        val standard = broadcast(app, standardSend(LincolnClusterProtocol.VALUE_KEY_TYPE_NAV_STOP))
        val announced = broadcast(app, Intent(LincolnClusterProtocol.ACTION_STOP_NAVIGATION), target = null)
        val state = broadcast(app, Intent(LincolnClusterProtocol.ACTION_NAV_STATE)
            .putExtra(LincolnClusterProtocol.EXTRA_STATE, LincolnClusterProtocol.STATE_OFF))
        hudSocket.end()
        Log.i(TAG, "nav stop close=$closed standard=$standard announced=$announced state=$state (#$navStops)")
    }

    private fun navTurnByTurn(
        app: Context,
        index: Int,
        metres: Int,
        street: String,
        token: LincolnManeuverToken,
    ): Boolean {
        val intent = Intent(LincolnClusterProtocol.ACTION_TURN_BY_TURN)
        LincolnClusterProtocol.turnByTurnExtras(index, metres, street).forEach { (key, value) ->
            when (value) {
                is Int -> intent.putExtra(key, value)
                is String -> intent.putExtra(key, value)
                else -> intent.putExtra(key, value.toString())
            }
        }
        val sent = broadcast(app, intent)
        // The earlier channel this car's adapter reads: the same maneuver plus the junction identity
        // the adapter needs before it can draw progress. Only the verified default adapter is known
        // to use the coordinate slots as identity rather than as a position.
        val info = Intent(LincolnClusterProtocol.ACTION_NAV_INFO)
            .putExtra(LincolnClusterProtocol.EXTRA_INFO_NAME, LincolnClusterProtocol.turnName(index))
            .putExtra(LincolnClusterProtocol.EXTRA_INFO_DISTANCE, metres)
            .putExtra(LincolnClusterProtocol.EXTRA_INFO_ROAD, street)
            .putExtra(LincolnClusterProtocol.EXTRA_INFO_LANE_COUNT, 0)
            .apply {
                if (LincolnClusterSettings.clusterPackage(app) ==
                    LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE
                ) {
                    putExtra(LincolnClusterProtocol.EXTRA_INFO_LATITUDE, token.latitude)
                    putExtra(LincolnClusterProtocol.EXTRA_INFO_LONGITUDE, token.longitude)
                }
            }
        val informed = broadcast(app, info)
        if (sent) hudSocket.update(index, metres, street, token.latitude, token.longitude)
        return sent || informed
    }

    private fun standardSend(keyType: String): Intent {
        val intent = Intent(LincolnClusterProtocol.ACTION_STANDARD_BROADCAST_SEND)
        intent.putExtra(LincolnClusterProtocol.EXTRA_KEY_TYPE, keyType)
        intent.putExtra(LincolnClusterProtocol.EXTRA_MODULE_TYPE, LincolnClusterProtocol.VALUE_MODULE_NAVI)
        return intent
    }

    /**
     * Total remaining distance/time on the cluster's own REMAIN channel. The cluster takes
     * `remainDistance` in metres and `remainTime` in seconds and formats both itself.
     */
    private fun sendRemainLocked(app: Context, snapshot: CarPlayGlance.Snapshot) {
        if (!LincolnClusterSettings.remaining(app)) {
            clearRemainLocked(app)
            return
        }
        val meters = snapshot.remainingMeters?.toInt()?.takeIf { it in 0..LincolnClusterProtocol.MAX_REMAIN_DISTANCE_METERS }
        val seconds = snapshot.remainingSeconds?.toInt()?.takeIf { it >= 0 }
        if (meters == null && seconds == null) {
            // Same rule as the instruction card: a quiet update is not a finished route.
            if (!snapshot.connected || (snapshot.routeEnded && endSettled())) clearRemainLocked(app)
            return
        }
        val key = "$meters|$seconds"
        if (key == lastRemainKey) return
        val intent = Intent(LincolnClusterProtocol.ACTION_NAV_REMAIN)
        meters?.let { intent.putExtra(LincolnClusterProtocol.EXTRA_REMAIN_DISTANCE, it) }
        seconds?.let { intent.putExtra(LincolnClusterProtocol.EXTRA_REMAIN_TIME, it) }
        if (broadcast(app, intent)) {
            lastRemainKey = key
            remainOwned = true
            remainSent++
            Log.i(TAG, "remain ${meters}m ${seconds}s (#$remainSent)")
        }
    }

    private fun sendMusicLocked(app: Context, snapshot: CarPlayGlance.Snapshot) {
        // The cluster draws one text row. Music apps put the current lyric in the iAP2 title field
        // and the song name in the artist field, so send the raw title alone: the widget's combined
        // "title — artist" would otherwise carry the song name along on the lyric line.
        val title = LincolnClusterProtocol.sanitizeText(snapshot.songTitle ?: snapshot.song)
        if (title == null) {
            clearMusicLocked(app)
            return
        }
        val key = "$title|${snapshot.playing}"
        if (key == lastMusicKey) return
        if (sendMusic(app, title, snapshot.playing)) {
            lastMusicKey = key
            musicOwned = true
            musicSent++
            Log.i(TAG, "music '$title' playing=${snapshot.playing} (#$musicSent)")
        }
    }

    private fun sendMusic(app: Context, title: String, playing: Boolean): Boolean =
        broadcast(app, Intent(LincolnClusterProtocol.ACTION_MEDIA_INFO)
            .putExtra("id", "diplay-widget")
            .putExtra(LincolnClusterProtocol.EXTRA_TITLE, title)
            .putExtra(LincolnClusterProtocol.EXTRA_ARTIST, "")
            .putExtra(LincolnClusterProtocol.EXTRA_ALBUM, "")
            .putExtra(LincolnClusterProtocol.EXTRA_SOURCE_NAME, LincolnClusterProtocol.SOURCE_NAME)
            .putExtra(LincolnClusterProtocol.EXTRA_SOURCE_TYPE, LincolnClusterProtocol.SOURCE_TYPE)
            .putExtra(LincolnClusterProtocol.EXTRA_PLAYING, playing))

    private fun clearRemainLocked(app: Context) {
        if (!remainOwned) return
        // Distance 0 and time 0 clear the readout; the cluster keeps the last value otherwise.
        if (broadcast(app, Intent(LincolnClusterProtocol.ACTION_NAV_REMAIN)
                .putExtra(LincolnClusterProtocol.EXTRA_REMAIN_DISTANCE, 0)
                .putExtra(LincolnClusterProtocol.EXTRA_REMAIN_TIME, 0))) {
            remainOwned = false
            lastRemainKey = null
            Log.i(TAG, "remain cleared")
        }
    }

    private fun clearMusicLocked(app: Context) {
        if (!musicOwned) return
        if (sendMusic(app, "", false)) {
            musicOwned = false
            lastMusicKey = null
            Log.i(TAG, "music cleared")
        }
    }

    private fun broadcast(
        app: Context,
        intent: Intent,
        target: String? = LincolnClusterSettings.clusterPackage(app),
    ): Boolean = runCatching {
        if (target != null) intent.setPackage(target)
        // The cluster app is kept stopped until the car uses it, so it still has to be reached.
        intent.addFlags(LincolnClusterProtocol.FLAG_INCLUDE_STOPPED_PACKAGES)
        app.sendBroadcast(intent)
        true
    }.getOrElse {
        Log.w(TAG, "broadcast failed ${intent.action} ${it.javaClass.simpleName}")
        false
    }
}

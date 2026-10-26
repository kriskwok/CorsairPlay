package com.shilapi.xcertplay.lincoln

import com.shilapi.xcertplay.hud.BydAppleManeuver
import com.shilapi.xcertplay.hud.BydClusterFrame
import com.shilapi.xcertplay.hud.ClusterDistancePrecision
import java.text.BreakIterator
import java.util.Locale

/**
 * Lincoln/Ford instrument-cluster forwarding, matching the build that is confirmed working on the
 * MEK-MX8Q / CX483 head unit (`DiPlay-0.2.12-增加仪表.apk`, package `cn.manstep.phonemirrorBox`).
 *
 * That build does not invent a channel of its own: it speaks the head unit's own Baidu navigation
 * protocol, the same one the stock `com.baidu.naviauto` app, the 小度车载OS framework and the
 * launcher use. The cluster therefore accepts CarPlay guidance as if the built-in navigation were
 * running. Three families carry it:
 *
 * - [ACTION_TURN_BY_TURN] carries the next maneuver, with every alias key the tested build fills so
 *   a receiver that reads a different name still finds a value.
 * - [ACTION_NAV_ACTIVE_OPEN]/[ACTION_NAV_ACTIVE_CLOSE] plus [ACTION_STANDARD_BROADCAST_SEND]
 *   (`KEY_TYPE` 10101/10100, `MODULE_TYPE` `NAVI`) tell the cluster that guidance started or ended,
 *   and [ACTION_START_NAVIGATION]/[ACTION_STOP_NAVIGATION] announce the same to every app.
 * - The Baidu NaviSDK HUD socket (see [LincolnHudSocketServer]) serves a client that connects to us
 *   instead of listening for broadcasts.
 *
 * Everything here is pure so the mapping can be unit-tested without a head unit.
 */
object LincolnClusterProtocol {
    /** The cluster app that draws the dashboard card on the tested Lincoln/Ford firmware. */
    const val DEFAULT_CLUSTER_PACKAGE = "com.baidu.xiaoduos.instrumentcluster"

    // --- Guidance state, the stock Baidu app's own channel ---------------------------------------

    /**
     * Sent to the cluster when guidance starts and ends. The cluster switches its navigation view on
     * these, so without them a turn-by-turn broadcast alone leaves the view as it was.
     */
    const val ACTION_NAV_ACTIVE_OPEN = "ACTION_PHONE_NAV_ACTIVE_OPEN"
    const val ACTION_NAV_ACTIVE_CLOSE = "ACTION_PHONE_NAV_ACTIVE_CLOSE"

    /** The `KEY_TYPE`/`MODULE_TYPE` status broadcast the framework and the launcher follow. */
    const val ACTION_STANDARD_BROADCAST_SEND = "com.baidu.naviauto.STANDARD_BROADCAST_SEND"
    const val EXTRA_KEY_TYPE = "KEY_TYPE"
    const val EXTRA_MODULE_TYPE = "MODULE_TYPE"
    const val VALUE_MODULE_NAVI = "NAVI"

    /** `KEY_TYPE` values of the stock navigation app, as observed on the head unit. */
    const val VALUE_KEY_TYPE_NAV_START = "10101"
    const val VALUE_KEY_TYPE_NAV_STOP = "10100"

    /** Announced without a package filter: the framework, the launcher and the nav app follow them. */
    const val ACTION_START_NAVIGATION = "com.baidu.naviauto.start_navigation"
    const val ACTION_STOP_NAVIGATION = "com.baidu.naviauto.stop_navigation"

    // --- Junction progress, the earlier channel this car's own adapter reads ---------------------

    /**
     * The channel the cluster's own navigation adapter reads. Unlike [ACTION_TURN_BY_TURN] it takes
     * the maneuver as a name plus [EXTRA_INFO_DISTANCE] and two doubles that the adapter uses as the
     * junction's identity: when they change, the adapter records the new distance as that junction's
     * starting distance and draws its progress bar as `distance / start * 256`. That is why the
     * distances sent under one identity must never increase — see [LincolnManeuverTracker].
     */
    const val ACTION_NAV_INFO = "com.baidu.car.navi.INFO"
    const val ACTION_NAV_STATE = "com.baidu.car.navi.STATE"
    const val EXTRA_STATE = "state"
    const val STATE_ON = "TRUE"
    const val STATE_OFF = "FALSE"
    const val EXTRA_INFO_NAME = "name"
    const val EXTRA_INFO_DISTANCE = "distance"
    const val EXTRA_INFO_ROAD = "nextRoadName"
    const val EXTRA_INFO_LANE_COUNT = "laneCount"
    const val EXTRA_INFO_LATITUDE = "latitude"
    const val EXTRA_INFO_LONGITUDE = "longitude"

    // --- Turn by turn -----------------------------------------------------------------------------

    const val ACTION_TURN_BY_TURN = "com.baidu.naviauto.action.send"

    const val EXTRA_TURN_TYPE = "KEY_TURNTYPE"
    const val EXTRA_TURN_TYPE_ALIAS = "turnType"
    const val EXTRA_MANEUVER_ID = "maneuverId"
    const val EXTRA_RES_ID = "resid"
    const val EXTRA_MANEUVER_NAME = "maneuverName"
    const val EXTRA_ICON_NAME = "icon_name"
    const val EXTRA_DISTANCE = "distance"
    const val EXTRA_REMAIN_DIST = "remainDist"
    const val EXTRA_REMAIN_DISTANCE = "remainDistance"
    const val EXTRA_REMAIN_DIST_UNDERSCORE = "remain_dist"
    const val EXTRA_DISTANCE_TO_NEXT = "DistanceToNextManeuver"
    const val EXTRA_MANEUVER_TIPS = "maneuverTips"
    const val EXTRA_STREET_NAME = "StreetName"
    const val EXTRA_STREET_NAME_2 = "StreetName2"
    const val EXTRA_CURRENT_STREET_NAME = "CurentStreetName"
    const val EXTRA_CURRENT_STREET_NAME_2 = "CurentStreetName2"
    const val EXTRA_ROAD_NAME = "road_name"

    /** Sends reach an app the car keeps stopped until it is used. */
    const val FLAG_INCLUDE_STOPPED_PACKAGES = 32

    /** The navigation app's turn index for "no arrow"; the tested build sends no maneuver then. */
    const val NO_TURN_INDEX = -1

    /** The tested build truncates the street name to this many characters. */
    const val STREET_NAME_MAX = 21

    /** Every alias the tested build fills for one maneuver, ready to be put into the intent. */
    fun turnByTurnExtras(index: Int, metres: Int, street: String): Map<String, Any> = linkedMapOf(
        EXTRA_TURN_TYPE to index,
        EXTRA_TURN_TYPE_ALIAS to index,
        EXTRA_MANEUVER_ID to index,
        EXTRA_RES_ID to index,
        EXTRA_MANEUVER_NAME to iconFileName(index),
        EXTRA_ICON_NAME to iconFileName(index),
        EXTRA_DISTANCE to metres.toString(),
        EXTRA_REMAIN_DIST to metres,
        EXTRA_REMAIN_DISTANCE to metres,
        EXTRA_REMAIN_DIST_UNDERSCORE to metres,
        EXTRA_DISTANCE_TO_NEXT to metres,
        EXTRA_MANEUVER_TIPS to "$metres 米",
        EXTRA_STREET_NAME to street,
        EXTRA_STREET_NAME_2 to "",
        EXTRA_CURRENT_STREET_NAME to street,
        EXTRA_CURRENT_STREET_NAME_2 to "",
        EXTRA_ROAD_NAME to street,
    )

    /**
     * The AMap cluster icon -> the Baidu turn index the cluster app draws. Taken from the tested
     * build, whose mapping also merges both U-turn sides into `turn_back` and routes sharp turns to
     * the nearest arrow.
     */
    fun turnIndex(amapIcon: Int): Int = when (amapIcon) {
        2 -> 7 // left
        3, 7 -> 3 // right, sharp right
        4 -> 8 // slight left
        5 -> 2 // slight right
        6 -> 7 // sharp left
        8, 19 -> 0 // U-turn, either side
        9 -> 1 // straight
        11, 17 -> 9 // roundabout, either traffic side
        12, 18 -> 10 // roundabout exit, either traffic side
        15 -> 24 // destination
        else -> NO_TURN_INDEX
    }

    /** Apple's maneuver type and driving side -> the Baidu turn index, or [NO_TURN_INDEX]. */
    fun turnIndex(maneuverType: Int?, drivingSide: Int): Int {
        val type = maneuverType ?: return NO_TURN_INDEX
        val frame = BydClusterFrame.from(
            BydAppleManeuver(distanceMeters = 0, type = type, drivingSide = drivingSide),
        )
        return turnIndex(frame.icon)
    }

    /** The cluster's arrow name without an extension, as the HUD socket spells it. */
    fun turnName(index: Int): String = when (index) {
        0 -> "turn_back"
        1 -> "turn_front"
        2 -> "turn_right_front"
        3 -> "turn_right"
        7 -> "turn_left"
        8 -> "turn_left_front"
        9 -> "turn_ring"
        10 -> "turn_ring_out"
        24 -> "turn_dest"
        else -> ""
    }

    /** The picture name the tested build puts on the turn-by-turn broadcast. */
    fun iconFileName(index: Int): String {
        val name = turnName(index)
        return if (name.isEmpty()) "" else "$name.png"
    }

    /** Icons the app draws itself, so a caller can tell "no arrow" from "not mapped". */
    fun hasTurnIcon(index: Int): Boolean = index != NO_TURN_INDEX

    /** Five-metre low-range formatting by default; raw mode only validates the accepted range. */
    fun roundDistance(
        meters: Int,
        precision: ClusterDistancePrecision = ClusterDistancePrecision.configured,
    ): Int? {
        if (meters < 0 || meters > MAX_DISTANCE_METERS) return null
        return when {
            meters < 1_000 -> precision.apply(meters, 5)
            meters < 10_000 -> precision.apply(meters, 100)
            else -> precision.apply(meters, 1_000)
        }
    }

    /** The street name as the tested build sends it: printable, blank dropped, 21 characters. */
    fun streetName(text: String?): String = sanitizeText(text, STREET_NAME_MAX).orEmpty()

    /**
     * The cluster drops non-printable characters and keeps at most [maxChars] UTF-16 units.
     *
     * The budget is counted in units rather than UTF-8 bytes, so an ordinary Chinese character
     * costs one and a supplementary one costs two. Truncation never leaves half a character
     * behind: a cut between the two units of a surrogate pair, or inside an emoji or combining
     * sequence, would hand the cluster a character it cannot draw, so a sequence that crosses the
     * budget is dropped whole. No ellipsis is appended; it would take space the cluster does not
     * have.
     */
    fun sanitizeText(text: String?, maxChars: Int = MAX_TEXT_CHARS): String? {
        if (text == null) return null
        val printable = buildString(text.length) {
            var index = 0
            while (index < text.length) {
                val point = Character.codePointAt(text, index)
                // The same C0/DEL filtering as before, and malformed surrogates are dropped too.
                if (point >= 32 && point !in 0xD800..0xDFFF) appendCodePoint(point)
                index += Character.charCount(point)
            }
        }
        if (printable.isBlank()) return null
        if (printable.length <= maxChars) return printable
        return printable.truncateAtCharacterBoundary(maxChars)
    }

    /** The longest prefix of this text that fits [maxChars] units without splitting a character. */
    private fun String.truncateAtCharacterBoundary(maxChars: Int): String? {
        if (maxChars <= 0) return null
        val characters = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(this@truncateAtCharacterBoundary) }
        var end = if (characters.isBoundary(maxChars)) maxChars else characters.preceding(maxChars)
        // Some Android character iterators still split newer emoji sequences, so retreat over the
        // whole sequence instead of trusting the host's Unicode boundary rules.
        while (end > 0 && !isCharacterBoundary(this, end)) end = characters.preceding(end)
        return substring(0, end.coerceAtLeast(0)).takeIf { it.isNotBlank() }
    }

    /**
     * Whether a cut at [end] falls between two characters rather than inside one. The surrogate
     * check is the one that always applies; the rest covers sequences whose parts are separate
     * code points and which the cluster would otherwise draw as a lone modifier or broken symbol.
     */
    private fun isCharacterBoundary(text: String, end: Int): Boolean {
        if (end <= 0 || end >= text.length) return true
        if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) return false
        val before = Character.codePointBefore(text, end)
        val after = Character.codePointAt(text, end)
        if (before == ZERO_WIDTH_JOINER || after == ZERO_WIDTH_JOINER ||
            after == ZERO_WIDTH_NON_JOINER) return false
        if (Character.getType(after) in COMBINING_TYPES ||
            after in 0xFE00..0xFE0F || after in 0xE0100..0xE01EF ||
            after in EMOJI_MODIFIERS || after in TAG_CHARACTERS) return false
        // Regional indicators pair up into flags; never keep only the first half of a flag.
        if (before in REGIONAL_INDICATORS && after in REGIONAL_INDICATORS) {
            var count = 0
            var index = end
            while (index > 0) {
                val point = Character.codePointBefore(text, index)
                if (point !in REGIONAL_INDICATORS) break
                count += 1
                index -= Character.charCount(point)
            }
            if (count % 2 != 0) return false
        }
        return true
    }

    // --- Now playing: unchanged, the music card already works on this head unit ------------------

    const val ACTION_MEDIA_INFO = "com.baidu.car.radio.notify.PLAY_INFO"
    const val EXTRA_TITLE = "title"
    const val EXTRA_ARTIST = "artist"
    const val EXTRA_ALBUM = "album_name"
    const val EXTRA_SOURCE_NAME = "source_name"
    const val EXTRA_SOURCE_TYPE = "source_type"
    const val EXTRA_PLAYING = "is_playing"

    const val SOURCE_NAME = "CarPlay"
    const val SOURCE_TYPE = 1

    /** The tested build truncates the one-line music card to this many UTF-16 units. */
    const val MAX_TEXT_CHARS = 32

    /** Character types and ranges that must stay attached to the character they modify. */
    private val COMBINING_TYPES = intArrayOf(
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
    )
    private val EMOJI_MODIFIERS = 0x1F3FB..0x1F3FF
    private val TAG_CHARACTERS = 0xE0020..0xE007F
    private val REGIONAL_INDICATORS = 0x1F1E6..0x1F1FF
    private const val ZERO_WIDTH_JOINER = 0x200D
    private const val ZERO_WIDTH_NON_JOINER = 0x200C

    /** Beyond this the cluster is not asked to show a distance (matches the field build). */
    const val MAX_DISTANCE_METERS = 2_000_000

    /**
     * Total remaining distance/time on the earlier `com.baidu.car.navi.REMAIN` channel. The working
     * build has no channel for the route total, so this one is kept as it is: the cluster's
     * remaining readout was measured accurate on this car while these were sent, and the new
     * navigation channel below carries no route total of its own. It stays behind its own switch.
     */
    const val ACTION_NAV_REMAIN = "com.baidu.car.navi.REMAIN"
    const val EXTRA_REMAIN_TIME = "remainTime"
    const val MAX_REMAIN_DISTANCE_METERS = 10_000_000
}

/**
 * Opaque identity for the cluster's own navigation adapter, NOT a geographic position. Its INFO
 * adapter passes latitude/longitude only to the equality check that decides when a junction starts;
 * neither coordinate reaches the vehicle service. The iAP2 model has maneuver ids, not maneuver
 * coordinates, and the vehicle's moving GPS position must never be substituted: every fix would
 * restart the junction's progress bar.
 */
internal data class LincolnManeuverToken(val latitude: Double, val longitude: Double) {
    companion object {
        fun fresh(): LincolnManeuverToken {
            val uuid = java.util.UUID.randomUUID()
            // Nonzero, finite doubles in [1, 2), with enough identity bits to avoid reusing the
            // adapter's cached token even if DiPlay restarts while the cluster stays alive.
            fun component(bits: Long) = Double.fromBits(0x3ff0000000000000L or (bits and 0x000fffffffffffffL))
            return LincolnManeuverToken(
                component(uuid.mostSignificantBits),
                component(uuid.leastSignificantBits),
            )
        }
    }
}

/**
 * Keeps every distance transmitted under one identity non-increasing, even though the adapter never
 * acknowledges what it received.
 *
 * The adapter records a junction's starting distance from the first packet it sees for that identity
 * and computes `distance / start * 256` on its own. It unboundedly divides whatever it is sent: a
 * receiver that missed the earlier, larger packets — or that restarted in the middle of a leg —
 * would establish a smaller baseline and overflow the bar. Sending a non-increasing sequence under
 * each identity means any contiguous suffix of it still starts at its own largest value, so the
 * computed progress stays inside 0..256 whatever the adapter managed to receive.
 *
 * A genuinely increasing distance (a detour, a reroute the iPhone did not announce) is therefore
 * only accepted after confirmation, and confirmation starts a new identity. Only frames carrying a
 * new phone distance count towards it: snapshot ticks, lyrics and our own keepalive repeats do not.
 */
internal class LincolnManeuverTracker {
    private var identity: String? = null
    private var minimumRawMeters = 0
    private var initialRawMeters = 0
    private var lastRevision: Long? = null
    private var smallRiseSinceNs: Long? = null
    private var smallRiseSamples = 0
    private var largeRiseSamples = 0
    private var largeRiseSinceNs: Long? = null
    private var token: LincolnManeuverToken? = null
    /** The distance to transmit for this identity: never higher than an earlier one. */
    var distanceMeters: Int = 0
        private set
    val pendingRiseSamples: Int get() = maxOf(smallRiseSamples, largeRiseSamples)
    var resetReason: String = "first-guidance"
        private set

    /** Filter raw metres before the display rounding the cluster also applies. */
    fun update(
        maneuverIndex: Int,
        turn: String,
        road: String,
        rawMeters: Int,
        routeGeneration: Long,
        distanceRevision: Long,
        nowNs: Long,
        stabilize: Boolean = true,
    ): LincolnManeuverToken {
        require(rawMeters in 0..LincolnClusterProtocol.MAX_DISTANCE_METERS)
        val nextIdentity = if (maneuverIndex >= 0) {
            // An indexed maneuver survives road-name completion and instruction refinements.
            "$routeGeneration:index:$maneuverIndex"
        } else {
            // Older callers without an index: the instruction is only a heuristic identity, and it
            // must not be confused with an indexed one.
            "$routeGeneration:instruction:$turn|$road"
        }
        val freshDistance = distanceRevision != lastRevision
        lastRevision = distanceRevision
        val formatted = checkNotNull(LincolnClusterProtocol.roundDistance(rawMeters))
        if (nextIdentity != identity) {
            if (token != null) resetReason = "maneuver-or-route-changed"
            identity = nextIdentity
            start(rawMeters)
        } else if (!stabilize) {
            if (formatted > distanceMeters) {
                resetReason = "distance-increased"
                start(rawMeters)
            } else {
                distanceMeters = formatted
            }
            // The receiving app can change during a session; keep the raw baseline aligned so
            // returning to the factory adapter cannot hold a custom receiver's value forever.
            minimumRawMeters = rawMeters
            clearRise()
        } else if (rawMeters <= minimumRawMeters) {
            minimumRawMeters = rawMeters
            distanceMeters = minOf(distanceMeters, formatted)
            clearRise()
        } else if (initialRawMeters == 0 && freshDistance) {
            // The first distance of a leg can be a placeholder zero; do not pin the whole leg to it.
            resetReason = "zero-baseline-recovered"
            start(rawMeters)
        } else if (freshDistance) {
            val riseMeters = rawMeters - minimumRawMeters
            when {
                riseMeters < MIN_PERSISTENT_RISE_METERS -> {
                    // One-to-four-metre noise must not prime either later confirmation path.
                    clearRise()
                }
                riseMeters < LARGE_RISE_METERS -> {
                    // Crossing between the two bands starts a new confirmation.
                    clearLargeRise()
                    if (smallRiseSinceNs == null) smallRiseSinceNs = nowNs
                    smallRiseSamples++
                    if (smallRiseSamples >= MIN_PERSISTENT_RISE_SAMPLES &&
                        nowNs - checkNotNull(smallRiseSinceNs) >= MAX_SMALL_RISE_HOLD_NS
                    ) {
                        resetReason = "persistent-distance-increase"
                        start(rawMeters)
                    }
                }
                else -> {
                    clearSmallRise()
                    if (largeRiseSinceNs == null) largeRiseSinceNs = nowNs
                    largeRiseSamples++
                    if (largeRiseSamples >= MIN_LARGE_RISE_SAMPLES &&
                        nowNs - checkNotNull(largeRiseSinceNs) >= LARGE_RISE_CONFIRM_NS
                    ) {
                        resetReason = "confirmed-distance-increase"
                        start(rawMeters)
                    }
                }
            }
        }
        return checkNotNull(token)
    }

    private fun start(rawMeters: Int) {
        token = LincolnManeuverToken.fresh()
        minimumRawMeters = rawMeters
        initialRawMeters = rawMeters
        distanceMeters = checkNotNull(LincolnClusterProtocol.roundDistance(rawMeters))
        clearRise()
    }

    private fun clearRise() {
        clearSmallRise()
        clearLargeRise()
    }

    private fun clearSmallRise() {
        smallRiseSinceNs = null
        smallRiseSamples = 0
    }

    private fun clearLargeRise() {
        largeRiseSamples = 0
        largeRiseSinceNs = null
    }

    fun reset(reason: String = "reset") {
        resetReason = reason
        identity = null
        minimumRawMeters = 0
        initialRawMeters = 0
        distanceMeters = 0
        lastRevision = null
        clearRise()
        token = null
    }

    private companion object {
        const val LARGE_RISE_METERS = 50
        const val MIN_PERSISTENT_RISE_METERS = 5
        const val MIN_PERSISTENT_RISE_SAMPLES = 3
        const val MIN_LARGE_RISE_SAMPLES = 2
        const val LARGE_RISE_CONFIRM_NS = 1_000_000_000L
        const val MAX_SMALL_RISE_HOLD_NS = 5_000_000_000L
    }
}

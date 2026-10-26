package com.shilapi.xcertplay.hud

internal data class BydHudGuidance(
    val distanceMeters: Int,
    /** Native HUD arrow (field 28). */
    val maneuver: Int,
    /** Gaode maneuver code: selects the HUD icon (field 8). */
    val gaode: Int = 0,
    val road: String = "",
    val arrivalEpochSeconds: Long? = null,
)

internal data class BydAppleManeuver(
    val distanceMeters: Int,
    val type: Int,
    val drivingSide: Int,
    val road: String = "",
    val remainingSeconds: Long? = null,
    val remainingMeters: Long? = null,
    val arrivalEpochSeconds: Long? = null,
    /** Stable iAP2 maneuver id; lets an external display reset per-turn progress exactly once. */
    val index: Int = -1,
)

internal enum class BydHudRouteChange {
    NONE,
    GUIDANCE,
    CLEAR,
}

/** Decodes the iAP2 route-guidance subset needed by the BYD windshield HUD and cluster. */
internal class BydHudRouteState(
    private val nanoTime: () -> Long = System::nanoTime,
    private val staleRouteNs: Long = STALE_ROUTE_NS,
    private val emptyListHideNs: Long = EMPTY_LIST_HIDE_NS,
    /**
     * The dashboard overlay keeps the last instruction across a wireless session drop: the iPhone
     * often sends NoRouteSet (0) while the tunnel is tearing down, which is not a real arrival.
     * Arrived (2) still ends the route. While the session stays up, NoRouteSet is the driver
     * ending guidance, and the card goes after [noRouteHideNs] unless guidance comes back; a
     * card left showing the last turn after a cancelled route was #511.
     */
    private val keepAcrossNoRoute: Boolean = false,
    private val noRouteHideNs: Long = NO_ROUTE_HIDE_NS,
    /**
     * Opt-in for glance consumers: hide the widget at once, but retain maneuver identity and the
     * cached junction through a brief NoRouteSet. Other HUD parsers keep their immediate-clear
     * policy, so this defaults to off.
     */
    private val progressEndGraceNs: Long = 0,
) {
    private data class Maneuver(val type: Int, val drivingSide: Int, val afterRoad: String)

    private val maneuvers = mutableMapOf<Int, Maneuver>()
    private var routeActive = false
    private var activeIndex = -1
    private var distanceMeters = 0
    private var currentRoad = ""
    private var arrivalEpochSeconds: Long? = null
    private var remainingSeconds: Long? = null
    private var remainingMeters: Long? = null
    private var emptyListSinceNs: Long? = null
    private var lastRouteUpdateNs: Long? = null
    private var noRouteSinceNs: Long? = null
    private var holdingAcrossDrop = false
    /**
     * True once guidance ended for a reason the iPhone stated (Arrived/NoRouteSet) or the session
     * dropped, as opposed to the route merely going quiet. Outputs that must not blink use this to
     * tell "the driver finished" from "no new frame for a while".
     */
    private var endedExplicitly = false
    private var progressResumeObserved = false
    private var progressEndSinceNs: Long? = null
    private var progressEndResumeObserved = false
    /** NoRouteSet is inside its [progressEndGraceNs] window; identity and cache are retained. */
    val progressEndPending: Boolean get() = progressEndSinceNs != null
    /** Counts accepted phone distance frames, not snapshots, songs or repeat timers. */
    var distanceRevision: Long = 0
        private set
    /** Local generation of route boundaries; kept across [clear] so a reused index still differs. */
    var progressGeneration: Long = 0
        private set
    /** A recalculation invalidated the baseline; wait for resumed guidance with a fresh distance. */
    var progressPending: Boolean = false
        private set

    fun accept(messageId: Int, payload: ByteArray): BydHudRouteChange {
        if (!validTlvs(payload)) return BydHudRouteChange.NONE
        expireProgressEnd()
        return when (messageId) {
            ROUTE_GUIDANCE_UPDATE -> parseRouteUpdate(payload)
            ROUTE_GUIDANCE_MANEUVER_UPDATE -> parseManeuverUpdate(payload)
            else -> BydHudRouteChange.NONE
        }
    }

    fun current(): BydHudGuidance? {
        val maneuver = activeManeuver() ?: return null
        val gaode = BydManeuverCodes.gaode(maneuver.type, maneuver.drivingSide)
        return BydHudGuidance(
            distanceMeters = distanceMeters,
            maneuver = BydManeuverCodes.hudArrow(gaode),
            gaode = gaode,
            road = roadFor(maneuver),
            arrivalEpochSeconds = arrivalEpochSeconds,
        )
    }

    /**
     * Whether a route is still running with recent guidance, even while [current]/[currentApple]
     * momentarily return null because the iPhone sent an empty current-maneuver list — which it does
     * every few seconds, and through a reroute. Outputs that must not flicker during those brief gaps
     * (the Lincoln/Ford cluster) use this to hold the last instruction instead of blanking it.
     *
     * This is deliberately stricter than [current]'s maneuver list but looser than its 3 s empty-list
     * hide: a route that has gone completely silent past [staleRouteNs], and one ended by
     * Arrived/NoRouteSet (which [clear]s), are both false, so a genuinely finished or dead route still
     * clears the output.
     */
    fun routeActive(): Boolean {
        if (!routeActive) return false
        val updated = lastRouteUpdateNs ?: return false
        if (nanoTime() - updated >= staleRouteNs) return false
        val noRouteSince = noRouteSinceNs
        if (noRouteSince != null && nanoTime() - noRouteSince >= noRouteHideNs) return false
        return true
    }

    /** Guidance ended explicitly (Arrived, NoRouteSet, or a session drop), not merely went quiet. */
    fun routeEnded(): Boolean = endedExplicitly

    /** Next maneuver as Apple sent it, for outputs with a richer icon set than the HUD. */
    fun currentApple(): BydAppleManeuver? {
        val maneuver = activeManeuver() ?: return null
        return BydAppleManeuver(
            distanceMeters = distanceMeters,
            type = maneuver.type,
            drivingSide = maneuver.drivingSide,
            road = roadFor(maneuver),
            remainingSeconds = remainingSeconds,
            remainingMeters = remainingMeters,
            arrivalEpochSeconds = arrivalEpochSeconds,
            index = activeIndex,
        )
    }

    fun clear(): Boolean {
        val wasActive = routeActive
        // Preserve a real end or a disconnect in the next snapshot as well: an output may already
        // hold newer guidance when the end callback arrives and has to discard that stale callback.
        // Reopening with the same maneuver index still then needs a fresh progress baseline.
        if (routeActive || progressPending || progressEndPending) progressGeneration++
        progressEndSinceNs = null
        progressEndResumeObserved = false
        progressResumeObserved = false
        endedExplicitly = true
        routeActive = false
        activeIndex = -1
        distanceMeters = 0
        currentRoad = ""
        arrivalEpochSeconds = null
        remainingSeconds = null
        remainingMeters = null
        emptyListSinceNs = null
        lastRouteUpdateNs = null
        noRouteSinceNs = null
        holdingAcrossDrop = false
        progressPending = false
        maneuvers.clear()
        return wasActive
    }

    /**
     * The wireless session dropped with guidance on screen. NoRouteSet before the drop, and from the
     * session that replaces it, is the handoff's, so the card stays until new guidance or its stale
     * window.
     */
    fun holdAcrossDrop() {
        noRouteSinceNs = null
        holdingAcrossDrop = true
    }

    private fun activeManeuver(): Maneuver? {
        expireProgressEnd()
        if (!routeActive || activeIndex < 0) return null
        val updated = lastRouteUpdateNs ?: return null
        if (nanoTime() - updated >= staleRouteNs) return null
        val emptySince = emptyListSinceNs
        if (emptySince != null && nanoTime() - emptySince >= emptyListHideNs) return null
        val noRouteSince = noRouteSinceNs
        if (noRouteSince != null && nanoTime() - noRouteSince >= noRouteHideNs) return null
        return maneuvers[activeIndex]
    }

    // The road the driver turns onto is what the next instruction is about; fall back to the current one.
    private fun roadFor(maneuver: Maneuver): String = maneuver.afterRoad.ifEmpty { currentRoad }

    private fun parseRouteUpdate(data: ByteArray): BydHudRouteChange {
        var state: Int? = null
        forEachTlv(data) { type, value, valueLength ->
            if (type == 0x01 && valueLength >= 1) state = data[value].toInt() and 0xff
        }
        // A teardown NoRouteSet is not fresh guidance. Do not let repeated teardown frames
        // extend the retained instruction's lifetime or replace its road/arrival metadata.
        if (keepAcrossNoRoute) {
            if (state == 0) {
                // Repeats keep the first time, so they cannot postpone the hide.
                if (!holdingAcrossDrop && noRouteSinceNs == null) noRouteSinceNs = nanoTime()
                endedExplicitly = true
                return BydHudRouteChange.NONE
            }
            noRouteSinceNs = null
            holdingAcrossDrop = false
        }
        // An end frame's old distance and text must not overwrite the retained maneuver. With the
        // grace window on, a brief NoRouteSet only hides the widget: the junction identity and the
        // cached road stay for a moment, because a reroute sends the same shape of frame.
        if (state == 0 || state == 2) {
            if (state == 0 && progressEndGraceNs > 0 &&
                (routeActive || progressEndPending) && activeIndex >= 0
            ) {
                if (progressEndSinceNs == null) progressEndSinceNs = nanoTime()
                val wasActive = routeActive
                routeActive = false
                endedExplicitly = true
                progressEndResumeObserved = false
                progressResumeObserved = false
                return if (wasActive) BydHudRouteChange.CLEAR else BydHudRouteChange.NONE
            }
            return if (clear()) BydHudRouteChange.CLEAR else BydHudRouteChange.NONE
        }
        lastRouteUpdateNs = nanoTime()
        var distance: Int? = null
        var firstManeuver: Int? = null
        var listPresent = false
        forEachTlv(data) { type, value, valueLength ->
            when {
                type == 0x03 -> currentRoad = utf8(data, value, valueLength)
                type == 0x05 && valueLength >= 8 -> arrivalEpochSeconds = u64(data, value).takeIf { it > 0 }
                type == 0x06 && valueLength >= 8 -> remainingSeconds = u64(data, value).takeIf { it >= 0 }
                type == 0x07 && valueLength >= 4 -> remainingMeters = u32(data, value)
                type == 0x0a && valueLength >= 4 -> {
                    distance = u32(data, value).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
                type == 0x0d -> {
                    listPresent = true
                    if (valueLength >= 2) firstManeuver = u16(data, value)
                }
            }
        }

        // iAP2 state 3=Loading and 5=Rerouting are explicit recalculation signals even when the app
        // reuses the junction id and the new distance is smaller. Handle them before the empty-list
        // return: adjacent loading/rerouting frames form one pending cycle, not one per frame.
        if (state != null) {
            when (state) {
                1, 6 -> {
                    if (progressPending) progressResumeObserved = true
                    if (progressEndPending) progressEndResumeObserved = true
                }
                3, 5 -> {
                    if (!progressPending) progressGeneration++
                    progressPending = true
                    progressResumeObserved = false
                    progressEndResumeObserved = false
                }
                // Locating (4) and unknown ordinary states neither establish nor revoke a resume
                // that was already observed. A fresh distance is still required below.
            }
        }
        // The iPhone briefly sends an empty current list every few seconds and while rerouting. Keep the last
        // maneuver (and the cached 0x5202 details, which are never resent) and hide it only if the list stays
        // empty; the bridges' 1 s tick clears the outputs once current() turns null.
        if (listPresent && firstManeuver == null) {
            if (emptyListSinceNs == null) emptyListSinceNs = nanoTime()
            return BydHudRouteChange.NONE
        }
        if (firstManeuver != null) emptyListSinceNs = null
        if (progressEndPending) {
            // Status, text and music alone must not resurrect a cancelled route from stale metres.
            // A resumed current list, or an active status, plus a fresh distance completes the grace.
            if (firstManeuver != null) activeIndex = firstManeuver!!
            if (state == null && firstManeuver != null && !progressPending) {
                progressEndResumeObserved = true
            }
            if (!progressEndResumeObserved || distance == null || activeIndex < 0 || emptyListSinceNs != null) {
                return BydHudRouteChange.NONE
            }
            progressEndSinceNs = null
            progressEndResumeObserved = false
            routeActive = true
            endedExplicitly = false
        }
        if (state != null) {
            routeActive = true
            endedExplicitly = false
        }
        if (firstManeuver != null) {
            activeIndex = firstManeuver!!
            routeActive = true
            endedExplicitly = false
        }
        if (distance != null) {
            distanceMeters = distance!!.coerceAtLeast(0)
            if (routeActive && activeIndex >= 0) distanceRevision++
        }
        // RouteSet (1) / ProceedToRoute (6) and the distance can arrive in separate frames. A
        // status-only update, cached maneuver details or an empty list must not establish the new
        // baseline from the previous route's distance, so a distance frame is required to finish.
        if (progressPending && progressResumeObserved &&
            distance != null && activeIndex >= 0 && emptyListSinceNs == null
        ) {
            progressPending = false
            progressResumeObserved = false
        }
        return if (current() != null) BydHudRouteChange.GUIDANCE else BydHudRouteChange.NONE
    }

    private fun expireProgressEnd() {
        val since = progressEndSinceNs ?: return
        if (nanoTime() - since >= progressEndGraceNs) clear()
    }

    private fun parseManeuverUpdate(data: ByteArray): BydHudRouteChange {
        var index: Int? = null
        var type: Int? = null
        var drivingSide = 0
        var afterRoad = ""
        forEachTlv(data) { id, value, valueLength ->
            when {
                id == 0x01 && valueLength >= 2 -> index = u16(data, value)
                id == 0x03 && valueLength >= 1 -> type = data[value].toInt() and 0xff
                id == 0x04 -> afterRoad = utf8(data, value, valueLength)
                id == 0x08 && valueLength >= 1 -> drivingSide = data[value].toInt() and 0xff
            }
        }
        if (index != null && type != null) maneuvers[index!!] = Maneuver(type!!, drivingSide, afterRoad)
        return if (current() != null) BydHudRouteChange.GUIDANCE else BydHudRouteChange.NONE
    }

    private inline fun forEachTlv(data: ByteArray, block: (Int, Int, Int) -> Unit) {
        var offset = 0
        while (offset < data.size) {
            val length = u16(data, offset)
            block(u16(data, offset + 2), offset + TLV_HEADER_BYTES, length - TLV_HEADER_BYTES)
            offset += length
        }
    }

    private fun validTlvs(data: ByteArray): Boolean {
        var offset = 0
        while (offset < data.size) {
            if (offset + TLV_HEADER_BYTES > data.size) return false
            val length = u16(data, offset)
            if (length < TLV_HEADER_BYTES || length > data.size - offset) return false
            offset += length
        }
        return offset == data.size
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun u32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xff) shl 24) or
            ((data[offset + 1].toLong() and 0xff) shl 16) or
            ((data[offset + 2].toLong() and 0xff) shl 8) or
            (data[offset + 3].toLong() and 0xff)

    private fun u64(data: ByteArray, offset: Int): Long = (u32(data, offset) shl 32) or u32(data, offset + 4)

    // iAP2 utf8 parameters are NUL-terminated.
    private fun utf8(data: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && data[end] != 0.toByte()) end++
        return String(data, offset, end - offset, Charsets.UTF_8).trim()
    }

    companion object {
        const val ROUTE_GUIDANCE_UPDATE = 0x5201
        const val ROUTE_GUIDANCE_MANEUVER_UPDATE = 0x5202
        private const val TLV_HEADER_BYTES = 4
        private const val STALE_ROUTE_NS = 30_000_000_000L
        private const val EMPTY_LIST_HIDE_NS = 3_000_000_000L
        private const val NO_ROUTE_HIDE_NS = 5_000_000_000L
    }
}

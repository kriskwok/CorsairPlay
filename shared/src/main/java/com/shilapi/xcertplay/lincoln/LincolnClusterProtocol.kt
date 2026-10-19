package com.shilapi.xcertplay.lincoln

import com.shilapi.xcertplay.hud.ClusterDistancePrecision

/**
 * Lincoln/Ford instrument-cluster forwarding, reverse-engineered from the field build
 * `0.2.20-lincoln-preview19` (`com.lincolnpay.android81`).
 *
 * The cluster app draws its own dashboard card and receives plain broadcasts; DiPlay only has to
 * translate CarPlay's iAP2 guidance (0x5201/0x5202) and now-playing (0x5001) into their extras.
 * Everything here is pure so the mapping can be unit-tested without a head unit.
 */
object LincolnClusterProtocol {
    /** The cluster app that draws the dashboard card on the tested Lincoln/Ford firmware. */
    const val DEFAULT_CLUSTER_PACKAGE = "com.baidu.xiaoduos.instrumentcluster"

    // Broadcasts the cluster listens for.
    const val ACTION_NAV_STATE = "com.baidu.car.navi.STATE"
    const val ACTION_NAV_INFO = "com.baidu.car.navi.INFO"
    /**
     * The cluster's own channel for total remaining distance/time. Confirmed on a MEK-MX8Q
     * (Android 8.1) head unit: the cluster reads `remainDistance` (metres) and `remainTime`
     * (seconds). The field build never sent this, which is why its remaining readout was missing.
     */
    const val ACTION_NAV_REMAIN = "com.baidu.car.navi.REMAIN"
    const val ACTION_MEDIA_INFO = "com.baidu.car.radio.notify.PLAY_INFO"

    // Extras the tested build sends.
    const val EXTRA_STATE = "state"
    const val EXTRA_NAME = "name"
    const val EXTRA_DISTANCE = "distance"
    const val EXTRA_ROAD = "nextRoadName"
    const val EXTRA_REMAIN_DISTANCE = "remainDistance"
    const val EXTRA_REMAIN_TIME = "remainTime"
    const val EXTRA_TITLE = "title"
    const val EXTRA_ARTIST = "artist"
    const val EXTRA_ALBUM = "album_name"
    const val EXTRA_SOURCE_NAME = "source_name"
    const val EXTRA_SOURCE_TYPE = "source_type"
    const val EXTRA_PLAYING = "is_playing"

    const val STATE_ON = "TRUE"
    const val STATE_OFF = "FALSE"

    const val SOURCE_NAME = "CarPlay"
    const val SOURCE_TYPE = 1

    /** The field build truncates cluster text to this many characters. */
    const val MAX_TEXT_CHARS = 32

    /** Beyond this the cluster is not asked to show a distance (matches the field build). */
    const val MAX_DISTANCE_METERS = 2_000_000

    /**
     * Apple's RouteGuidanceManeuverType -> the cluster's own turn code. The grouping follows the
     * field build, which maps a U-turn to a single `turn_back` regardless of driving side.
     */
    fun turnCode(maneuverType: Int?, drivingSide: Int): String? = when (maneuverType) {
        null -> null
        1, 20 -> "turn_left"
        2, 21 -> "turn_right"
        47 -> "turn_left_back"
        48 -> "turn_right_back"
        13, 22, 49, 52 -> "turn_left_front"
        14, 23, 50, 53 -> "turn_right_front"
        4, 18, 19, 26 -> "turn_back"
        6, 7, in 28..46 -> "turn_ring"
        10, 12, 24, 25, 27 -> "turn_dest"
        3, 5, 8, 9, 11, 15, 16, 17, 51 -> "turn_front"
        else -> "turn_front"
    }

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

    /** The cluster drops non-printable characters and keeps at most [MAX_TEXT_CHARS]. */
    fun sanitizeText(text: String?): String? {
        if (text == null) return null
        val printable = text.filter { it.code in 32..126 || it.code > 126 }
        if (printable.isBlank()) return null
        return printable.take(MAX_TEXT_CHARS)
    }

    /** Beyond this the cluster drops the remaining readout. */
    const val MAX_REMAIN_DISTANCE_METERS = 10_000_000
}

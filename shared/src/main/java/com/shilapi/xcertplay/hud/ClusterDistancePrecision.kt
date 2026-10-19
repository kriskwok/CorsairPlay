package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.shared.BuildConfig

/**
 * How next-turn distance is formatted before it is sent to an instrument cluster.
 *
 * FIVE_METER keeps the existing upper-range quantisation but halves the low-range step from 10 m
 * to 5 m. RAW forwards Apple's metre value and only applies the caller's bounds/sign protection.
 */
enum class ClusterDistancePrecision(val buildValue: String) {
    FIVE_METER("five-meter"),
    RAW("raw");

    fun apply(meters: Int, step: Int): Int =
        if (this == RAW) meters else ((meters + step / 2) / step) * step

    companion object {
        val configured: ClusterDistancePrecision
            get() = entries.firstOrNull { it.buildValue == BuildConfig.DISTANCE_PRECISION }
                ?: FIVE_METER
    }
}

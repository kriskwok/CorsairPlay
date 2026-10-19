package com.shilapi.xcertplay.lincoln

import com.shilapi.xcertplay.hud.ClusterDistancePrecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LincolnClusterProtocolTest {
    @Test
    fun mapsAppleManeuversToClusterTurnCodes() {
        assertEquals("turn_left", LincolnClusterProtocol.turnCode(1, 0))
        assertEquals("turn_right", LincolnClusterProtocol.turnCode(2, 0))
        assertEquals("turn_left_back", LincolnClusterProtocol.turnCode(47, 0))
        assertEquals("turn_right_back", LincolnClusterProtocol.turnCode(48, 0))
        assertEquals("turn_left_front", LincolnClusterProtocol.turnCode(13, 0))
        assertEquals("turn_right_front", LincolnClusterProtocol.turnCode(23, 0))
        assertEquals("turn_back", LincolnClusterProtocol.turnCode(4, 1))
        assertEquals("turn_ring", LincolnClusterProtocol.turnCode(6, 0))
        assertEquals("turn_dest", LincolnClusterProtocol.turnCode(10, 0))
        assertEquals("turn_front", LincolnClusterProtocol.turnCode(3, 0))
        assertNull(LincolnClusterProtocol.turnCode(null, 0))
    }

    @Test
    fun fiveMetrePrecisionKeepsTheLowRangeFine() {
        assertEquals(5, LincolnClusterProtocol.roundDistance(7, ClusterDistancePrecision.FIVE_METER))
        assertEquals(350, LincolnClusterProtocol.roundDistance(348, ClusterDistancePrecision.FIVE_METER))
        assertEquals(450, LincolnClusterProtocol.roundDistance(450, ClusterDistancePrecision.FIVE_METER))
        assertEquals(455, LincolnClusterProtocol.roundDistance(455, ClusterDistancePrecision.FIVE_METER))
        assertEquals(700, LincolnClusterProtocol.roundDistance(700, ClusterDistancePrecision.FIVE_METER))
        assertEquals(1_500, LincolnClusterProtocol.roundDistance(1_500, ClusterDistancePrecision.FIVE_METER))
        assertEquals(12_000, LincolnClusterProtocol.roundDistance(11_600, ClusterDistancePrecision.FIVE_METER))
        assertNull(LincolnClusterProtocol.roundDistance(-1, ClusterDistancePrecision.FIVE_METER))
        assertNull(LincolnClusterProtocol.roundDistance(2_000_001, ClusterDistancePrecision.FIVE_METER))
    }

    @Test
    fun rawPrecisionOnlyRejectsInvalidRange() {
        assertEquals(7, LincolnClusterProtocol.roundDistance(7, ClusterDistancePrecision.RAW))
        assertEquals(348, LincolnClusterProtocol.roundDistance(348, ClusterDistancePrecision.RAW))
        assertEquals(455, LincolnClusterProtocol.roundDistance(455, ClusterDistancePrecision.RAW))
        assertEquals(11_600, LincolnClusterProtocol.roundDistance(11_600, ClusterDistancePrecision.RAW))
        assertNull(LincolnClusterProtocol.roundDistance(-1, ClusterDistancePrecision.RAW))
        assertNull(LincolnClusterProtocol.roundDistance(2_000_001, ClusterDistancePrecision.RAW))
    }

    @Test
    fun sanitizesClusterText() {
        assertEquals("Main Street", LincolnClusterProtocol.sanitizeText("Main Street"))
        assertNull(LincolnClusterProtocol.sanitizeText("   "))
        assertNull(LincolnClusterProtocol.sanitizeText(null))
        val long = "x".repeat(50)
        assertEquals(LincolnClusterProtocol.MAX_TEXT_CHARS, LincolnClusterProtocol.sanitizeText(long)!!.length)
    }
}

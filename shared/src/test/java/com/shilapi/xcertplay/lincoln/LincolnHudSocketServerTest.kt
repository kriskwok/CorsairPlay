package com.shilapi.xcertplay.lincoln

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33])
class LincolnHudSocketServerTest {
    @Test
    fun formatsThePanelDistanceTheWayTheHudExpects() {
        assertEquals(0, LincolnHudSocketServer.hudDistance(-1))
        assertEquals(0, LincolnHudSocketServer.hudDistance(0))
        assertEquals(350, LincolnHudSocketServer.hudDistance(350))
        assertEquals(999, LincolnHudSocketServer.hudDistance(999))
        assertEquals(1_000, LincolnHudSocketServer.hudDistance(1_000))
        assertEquals(2_500, LincolnHudSocketServer.hudDistance(2_500))
        assertEquals(12_300, LincolnHudSocketServer.hudDistance(12_300))
        assertEquals(99_900, LincolnHudSocketServer.hudDistance(99_999))
        assertEquals(100_000, LincolnHudSocketServer.hudDistance(100_000))
        assertEquals(123_000, LincolnHudSocketServer.hudDistance(123_456))
    }

    @Test
    fun speaksTheManeuverTipTheHudShows() {
        assertEquals("350米后进入Test Road", LincolnHudSocketServer.maneuverTips(350, "Test Road"))
        assertEquals("350米后", LincolnHudSocketServer.maneuverTips(350, ""))
        assertEquals("沿Test Road 行驶", LincolnHudSocketServer.maneuverTips(0, "Test Road"))
    }

    @Test
    fun sendsTheManeuverFrameTheTestedBuildSends() {
        val data = LincolnHudSocketServer.maneuverData(index = 7, metres = 350, street = "Test Road")

        assertEquals("turn_left", data.getString("maneuverName"))
        assertFalse(data.getBoolean("straight"))
        assertEquals(350, data.getInt("distance"))
        assertEquals("Test Road", data.getString("nextRoadName"))
        assertEquals("Test Road", data.getString("currentRoadName"))
        assertEquals("350米后进入Test Road", data.getString("maneuverTips"))
        assertFalse(data.getBoolean("isHighWay"))
        assertEquals(0, data.getInt("laneCount"))
    }

    @Test
    fun aStraightManeuverIsMarkedStraightAndABlankRoadFallsBack() {
        val straight = LincolnHudSocketServer.maneuverData(index = 1, metres = 350, street = "")

        assertTrue(straight.getBoolean("straight"))
        assertEquals("turn_front", straight.getString("maneuverName"))
        assertEquals("当前道路", straight.getString("currentRoadName"))
        assertEquals("350米后进入当前道路", straight.getString("maneuverTips"))
    }

    @Test
    fun anUnknownIndexStillNamesTheStraightArrow() {
        val data = LincolnHudSocketServer.maneuverData(
            index = LincolnClusterProtocol.NO_TURN_INDEX,
            metres = 0,
            street = "",
        )

        assertEquals("turn_front", data.getString("maneuverName"))
        assertEquals(0, data.getInt("distance"))
    }

    @Test
    fun theEndFrameClearsTheHudCard() {
        val data = LincolnHudSocketServer.endManeuverData()

        assertTrue(data.getBoolean("isNaviEnd"))
        assertTrue(data.getBoolean("straight"))
        assertEquals(0, data.getInt("distance"))
        assertEquals("", data.getString("maneuverName"))
        assertEquals("", data.getString("nextRoadName"))
        assertEquals("", data.getString("currentRoadName"))
    }

    @Test
    fun theRoadFrameCarriesOnlyTheCurrentRoad() {
        assertEquals("Main Street", LincolnHudSocketServer.roadData("Main Street").getString("currentRoadName"))
        assertEquals("", LincolnHudSocketServer.roadData("").getString("currentRoadName"))
    }

    /**
     * The route end must not take the socket down: the car's navigation client stays connected, so
     * the next route in the same drive starts without a reconnect (and without the empty card that
     * reconnect left behind). Only a dropped session or a switched-off output releases the ports.
     */
    @Test
    fun theSocketKeepsServingAfterTheRouteEnds() {
        LincolnHudSocketServer.start()
        try {
            assertTrue(LincolnHudSocketServer.running)
            LincolnHudSocketServer.end()
            assertTrue(LincolnHudSocketServer.running)
            assertTrue(LincolnHudSocketServer.diagnostics().startsWith("running=true"))
        } finally {
            LincolnHudSocketServer.stop()
        }
        assertFalse(LincolnHudSocketServer.running)
        assertTrue(LincolnHudSocketServer.diagnostics().startsWith("running=false"))
    }
}

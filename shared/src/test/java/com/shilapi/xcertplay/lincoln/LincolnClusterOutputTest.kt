package com.shilapi.xcertplay.lincoln

import android.app.Application
import android.content.Intent
import com.shilapi.xcertplay.glance.CarPlayGlance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33])
class LincolnClusterOutputTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private var now = 1_000_000_000L
    private val hud = FakeHudSocket()

    @Before fun setUp() {
        LincolnClusterSettings.setNavigation(app, true)
        LincolnClusterSettings.setMusic(app, false)
        LincolnClusterSettings.setRemaining(app, false)
        LincolnClusterOutput.nanoTime = { now }
        LincolnClusterOutput.hudSocket = hud
        CarPlayGlance.setConnected(false)
        LincolnClusterOutput.attach(app)
        shadowOf(app).clearBroadcastIntents()
        hud.clear()
    }

    @After fun tearDown() {
        LincolnClusterOutput.detach()
        LincolnClusterOutput.hudSocket = LincolnHudSocketServer
        LincolnClusterSettings.setNavigation(app, false)
        CarPlayGlance.setConnected(false)
        shadowOf(app).clearBroadcastIntents()
    }

    @Test fun guidanceAnnouncesNavigationBeforeItDrawsTheTurn() {
        send(guidance())

        assertEquals(1, intents(LincolnClusterProtocol.ACTION_NAV_ACTIVE_OPEN).size)
        assertEquals(listOf(LincolnClusterProtocol.VALUE_KEY_TYPE_NAV_START), keyTypes())
        assertEquals(1, intents(LincolnClusterProtocol.ACTION_START_NAVIGATION).size)
        assertEquals(1, hud.starts)
        // After the announcement the order on the wire is turn-by-turn first: the cluster only draws
        // a card once its navigation view is open.
        assertTrue(intents(LincolnClusterProtocol.ACTION_TURN_BY_TURN).size >= 1)
    }

    @Test fun theTurnCarriesEveryAliasTheClusterMayRead() {
        send(guidance())

        val turn = turns().single()
        assertEquals(7, turn.getIntExtra(LincolnClusterProtocol.EXTRA_TURN_TYPE, -99))
        assertEquals(7, turn.getIntExtra(LincolnClusterProtocol.EXTRA_TURN_TYPE_ALIAS, -99))
        assertEquals(7, turn.getIntExtra(LincolnClusterProtocol.EXTRA_MANEUVER_ID, -99))
        assertEquals(7, turn.getIntExtra(LincolnClusterProtocol.EXTRA_RES_ID, -99))
        assertEquals("turn_left.png", turn.getStringExtra(LincolnClusterProtocol.EXTRA_MANEUVER_NAME))
        assertEquals("300", turn.getStringExtra(LincolnClusterProtocol.EXTRA_DISTANCE))
        assertEquals(300, turn.getIntExtra(LincolnClusterProtocol.EXTRA_REMAIN_DISTANCE, -1))
        assertEquals(300, turn.getIntExtra(LincolnClusterProtocol.EXTRA_REMAIN_DIST, -1))
        assertEquals(300, turn.getIntExtra(LincolnClusterProtocol.EXTRA_DISTANCE_TO_NEXT, -1))
        assertEquals("Test Road", turn.getStringExtra(LincolnClusterProtocol.EXTRA_STREET_NAME))
        assertEquals("Test Road", turn.getStringExtra(LincolnClusterProtocol.EXTRA_ROAD_NAME))
        assertEquals("350 米", LincolnClusterProtocol.turnByTurnExtras(7, 350, "Test Road")
            [LincolnClusterProtocol.EXTRA_MANEUVER_TIPS])
        // The cluster app is kept stopped until the car uses it, so the flag is part of the protocol.
        assertTrue(turn.flags and LincolnClusterProtocol.FLAG_INCLUDE_STOPPED_PACKAGES != 0)
    }

    @Test fun theTurnReachesTheClusterPackageAndTheAnnouncementAlsoGoesToEveryApp() {
        send(guidance())

        assertEquals(
            LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE,
            turns().single().`package`,
        )
        assertNull(intents(LincolnClusterProtocol.ACTION_START_NAVIGATION).single().`package`)
    }

    @Test fun theHudSocketGetsTheSameManeuver() {
        send(guidance())

        assertEquals(listOf(Triple(7, 300, "Test Road")), hud.updates)
    }

    @Test fun theHudSocketIsOfferedForTheWholeSessionAndReleasedOnDisconnect() {
        // The cluster's own navigation client connects to the socket on its own retry schedule, so
        // the socket has to be up before the first instruction: a client that only arrives once the
        // cluster's navigation view has opened draws an empty card until it is authenticated.
        send(quiet())

        assertEquals(1, hud.starts)
        assertTrue(hud.running)
        assertTrue("no guidance yet, so nothing may be drawn", turns().isEmpty())
        assertTrue(intents(LincolnClusterProtocol.ACTION_NAV_ACTIVE_OPEN).isEmpty())
        assertEquals(listOf("start"), hud.events)

        send(guidance())
        assertEquals(listOf("start", "update"), hud.events)

        send(CarPlayGlance.Snapshot(connected = false))
        assertFalse("a dropped session releases the car's ports again", hud.running)
    }

    @Test fun theZeroMetreRouteStartPlaceholderIsHeldUntilARealDistanceArrives() {
        // The iPhone publishes 0 m for the first frames of a route, before it has a distance for the
        // first leg. Grouping that with the announcement opened the cluster on an empty "0 m" card.
        send(guidance(distanceMeters = 0))

        assertTrue("the placeholder must not open the cluster's navigation view", opens().isEmpty())
        assertTrue(turns().isEmpty())
        assertTrue(hud.updates.isEmpty())

        now += 50_000_000L
        send(guidance(distanceMeters = 30))

        assertEquals(1, opens().size)
        assertEquals(listOf(Triple(7, 30, "Test Road")), hud.updates)
        assertEquals("30", turns().single().getStringExtra(LincolnClusterProtocol.EXTRA_DISTANCE))
    }

    @Test fun aRouteThatReallyStartsAtZeroMetresIsDrawnOnceTheHoldExpires() {
        send(guidance(distanceMeters = 0))
        assertTrue(turns().isEmpty())

        // The iPhone has nothing new to send for a route that stands still, so the tick has to draw
        // the held instruction instead of leaving the cluster blank for good.
        now += 4_000_000_000L
        send(guidance(distanceMeters = 0))

        assertEquals(1, opens().size)
        assertEquals("0", turns().single().getStringExtra(LincolnClusterProtocol.EXTRA_DISTANCE))
    }

    @Test fun theJunctionProgressChannelCarriesTheManeuverAndOneIdentityOnBothPaths() {
        send(guidance())

        val info = infos().single()
        assertEquals(LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE, info.`package`)
        assertEquals("turn_left", info.getStringExtra(LincolnClusterProtocol.EXTRA_INFO_NAME))
        assertEquals(300, info.getIntExtra(LincolnClusterProtocol.EXTRA_INFO_DISTANCE, -1))
        assertEquals("Test Road", info.getStringExtra(LincolnClusterProtocol.EXTRA_INFO_ROAD))
        assertEquals(listOf(LincolnClusterProtocol.STATE_ON), states())
        // The adapter only compares the pair, so the opaque identity has to be finite and nonzero;
        // a zero baseline is what leaves the bar stuck at its default.
        val latitude = info.getDoubleExtra(LincolnClusterProtocol.EXTRA_INFO_LATITUDE, 0.0)
        val longitude = info.getDoubleExtra(LincolnClusterProtocol.EXTRA_INFO_LONGITUDE, 0.0)
        assertTrue(latitude in 1.0..2.0)
        assertTrue(longitude in 1.0..2.0)
        // The socket path publishes the same identity, so the two inputs cannot restart each other.
        assertEquals(listOf(listOf(latitude, longitude)), hud.tokens)
    }

    @Test fun theDistanceUnderOneJunctionIdentityNeverIncreases() {
        send(guidance(distanceMeters = 600))
        now += 1_000_000_000L
        send(guidance(distanceMeters = 580, distanceRevision = 2))
        now += 1_000_000_000L
        // A step correction back up inside the same junction: the adapter would take it as a new,
        // larger start distance and redraw the bar, so the older, smaller value is held instead.
        send(guidance(distanceMeters = 582, distanceRevision = 3))

        assertEquals(listOf(600, 580, 580), infoDistances())
        assertEquals(1, identities().distinct().size)
    }

    @Test fun aNewManeuverStartsANewJunctionIdentity() {
        send(guidance(maneuverIndex = 3, distanceMeters = 600))
        val first = identities().single()
        now += 1_000_000_000L
        // The next turn keeps the same style of instruction but a different iAP2 id, and its leg is
        // longer than the one before: the adapter has to record a fresh start distance for it.
        send(guidance(maneuverType = 2, maneuverIndex = 4, distanceMeters = 900, distanceRevision = 2))

        assertEquals(900, infoDistances().last())
        assertTrue(identities().last() != first)
    }

    @Test fun aRecalculationHoldsTheLastPacketUntilItResumesWithANewDistance() {
        send(guidance(distanceMeters = 600))
        now += 1_000_000_000L
        // Rerouting: the same junction id arrives with a smaller distance, which belongs to the old
        // baseline. It must not be forwarded yet, or the adapter starts the bar from the wrong point.
        send(guidance(distanceMeters = 500, distanceRevision = 2, routeProgressPending = true))
        assertEquals(600, infoDistances().last())

        // Guidance resumed and the route generation moved on: the new distance is the new baseline.
        now += 1_000_000_000L
        send(
            guidance(
                distanceMeters = 500, distanceRevision = 3,
                routeProgressGeneration = 1,
            ),
        )
        assertEquals(500, infoDistances().last())
    }

    @Test fun aBriefRouteEndKeepsTheIdentityAndASettledOneClosesTheChannel() {
        send(guidance(distanceMeters = 600))
        val identity = identities().single()

        now += 500_000_000L
        send(CarPlayGlance.Snapshot(connected = true, routeEnded = true, routeEndPending = true))
        assertEquals("A brief NoRouteSet must not close guidance", listOf(LincolnClusterProtocol.STATE_ON), states())

        // Settled: the cluster is told navigation ended and the junction identity is dropped.
        now += 1_100_000_000L
        send(CarPlayGlance.Snapshot(connected = true, routeEnded = true))
        assertEquals(
            listOf(LincolnClusterProtocol.STATE_ON, LincolnClusterProtocol.STATE_OFF),
            states(),
        )
        assertEquals(1, identities().distinct().size)
        assertEquals(identity, identities().last())
    }

    @Test fun aStatedEndMustStickBeforeGuidanceIsStopped() {
        send(guidance())

        // Route start / reroute sends NoRouteSet for a moment. The card must stay.
        send(ended())
        assertFalse("A momentary end must not close the cluster navigation", closes().isNotEmpty())
        // A short NoRouteSet blip is still ignored.
        now += 500_000_000L
        send(ended())
        assertFalse(closes().isNotEmpty())

        // Once the end has held for one second, guidance is closed promptly.
        now += 500_000_000L
        send(ended())
        assertEquals(1, intents(LincolnClusterProtocol.ACTION_NAV_ACTIVE_CLOSE).size)
        assertEquals(listOf(LincolnClusterProtocol.VALUE_KEY_TYPE_NAV_STOP), keyTypes().drop(1))
        assertEquals(1, intents(LincolnClusterProtocol.ACTION_STOP_NAVIGATION).size)
        assertEquals(1, hud.ends)
    }

    @Test fun aQuietRouteKeepsTheCardAndKeepsRefreshingIt() {
        send(guidance())
        val turns = turns().size
        // Parked at a light for ten seconds: no new guidance at all, but the route did not end.
        repeat(10) {
            now += 1_000_000_000L
            send(quiet())
        }
        assertTrue("A quiet route must not close the cluster navigation", closes().isEmpty())
        assertTrue("The card must keep being refreshed", turns().size > turns)
    }

    @Test fun anUnmappedManeuverKeepsTheLastCardInsteadOfDrawingAWrongArrow() {
        send(guidance())
        val turns = turns().size

        // Apple's "unknown" maneuver has no arrow of its own.
        send(guidance(maneuverType = 0))

        assertEquals(turns, turns().size)
        assertEquals(7, turns().last().getIntExtra(LincolnClusterProtocol.EXTRA_TURN_TYPE, -99))
        assertTrue(closes().isEmpty())
    }

    @Test fun aSessionDropClosesGuidanceWithoutWaitingForTheSettleWindow() {
        send(guidance())

        send(CarPlayGlance.Snapshot(connected = false))

        assertEquals(1, intents(LincolnClusterProtocol.ACTION_NAV_ACTIVE_CLOSE).size)
    }

    @Test fun theRouteRemainingReachesTheClusterOnItsOwnChannel() {
        LincolnClusterSettings.setRemaining(app, true)

        send(guidance().copy(remainingMeters = 12_300, remainingSeconds = 3_600))

        val remain = intents(LincolnClusterProtocol.ACTION_NAV_REMAIN).single()
        assertEquals(LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE, remain.`package`)
        assertEquals(12_300, remain.getIntExtra(LincolnClusterProtocol.EXTRA_REMAIN_DISTANCE, -1))
        assertEquals(3_600, remain.getIntExtra(LincolnClusterProtocol.EXTRA_REMAIN_TIME, -1))
    }

    @Test fun aChangedRouteRemainingIsSentAgain() {
        LincolnClusterSettings.setRemaining(app, true)

        send(guidance().copy(remainingMeters = 12_300, remainingSeconds = 3_600))
        send(guidance().copy(remainingMeters = 9_400, remainingSeconds = 3_000))

        assertEquals(2, intents(LincolnClusterProtocol.ACTION_NAV_REMAIN).size)
        assertEquals(
            9_400,
            intents(LincolnClusterProtocol.ACTION_NAV_REMAIN).last()
                .getIntExtra(LincolnClusterProtocol.EXTRA_REMAIN_DISTANCE, -1),
        )
    }

    @Test fun theRouteTotalIsClearedWhenTheRouteEnds() {
        LincolnClusterSettings.setRemaining(app, true)
        send(guidance().copy(remainingMeters = 12_300, remainingSeconds = 3_600))

        send(ended())
        now += 500_000_000L
        send(ended())
        now += 500_000_000L
        send(ended())

        val remain = intents(LincolnClusterProtocol.ACTION_NAV_REMAIN)
        assertEquals(2, remain.size)
        assertEquals(0, remain.last().getIntExtra(LincolnClusterProtocol.EXTRA_REMAIN_DISTANCE, -1))
        assertEquals(0, remain.last().getIntExtra(LincolnClusterProtocol.EXTRA_REMAIN_TIME, -1))
    }

    @Test fun theRouteTotalStaysSilentWhenItsSwitchIsOff() {
        send(guidance().copy(remainingMeters = 12_300, remainingSeconds = 3_600))

        assertTrue(intents(LincolnClusterProtocol.ACTION_NAV_REMAIN).isEmpty())
    }

    private fun send(snapshot: CarPlayGlance.Snapshot) {
        LincolnClusterOutput.javaClass.getDeclaredMethod("onSnapshot", CarPlayGlance.Snapshot::class.java)
            .apply { isAccessible = true }
            .invoke(LincolnClusterOutput, snapshot)
    }

    private fun guidance(
        maneuverType: Int = 1,
        distanceMeters: Int = 300,
        maneuverIndex: Int = 3,
        distanceRevision: Long = 1,
        routeProgressGeneration: Long = 0,
        routeProgressPending: Boolean = false,
        routeEndPending: Boolean = false,
    ) = CarPlayGlance.Snapshot(
        connected = true, maneuverType = maneuverType, routeActive = true,
        distanceMeters = distanceMeters, road = "Test Road",
        maneuverIndex = maneuverIndex, distanceRevision = distanceRevision,
        routeProgressGeneration = routeProgressGeneration,
        routeProgressPending = routeProgressPending, routeEndPending = routeEndPending,
    )

    private fun quiet() = CarPlayGlance.Snapshot(connected = true)

    private fun ended() = CarPlayGlance.Snapshot(connected = true, routeEnded = true)

    private fun intents(action: String): List<Intent> =
        shadowOf(app).broadcastIntents.filter { it.action == action }

    private fun turns(): List<Intent> = intents(LincolnClusterProtocol.ACTION_TURN_BY_TURN)

    private fun closes(): List<Intent> = intents(LincolnClusterProtocol.ACTION_NAV_ACTIVE_CLOSE)

    private fun opens(): List<Intent> = intents(LincolnClusterProtocol.ACTION_NAV_ACTIVE_OPEN)

    private fun keyTypes(): List<String?> = intents(LincolnClusterProtocol.ACTION_STANDARD_BROADCAST_SEND)
        .map { it.getStringExtra(LincolnClusterProtocol.EXTRA_KEY_TYPE) }

    private fun infos(): List<Intent> = intents(LincolnClusterProtocol.ACTION_NAV_INFO)

    private fun states(): List<String?> = intents(LincolnClusterProtocol.ACTION_NAV_STATE)
        .map { it.getStringExtra(LincolnClusterProtocol.EXTRA_STATE) }

    private fun identities(): List<List<Double>> = infos().map { info ->
        listOf(
            info.getDoubleExtra(LincolnClusterProtocol.EXTRA_INFO_LATITUDE, 0.0),
            info.getDoubleExtra(LincolnClusterProtocol.EXTRA_INFO_LONGITUDE, 0.0),
        )
    }

    private fun infoDistances(): List<Int> = infos()
        .map { it.getIntExtra(LincolnClusterProtocol.EXTRA_INFO_DISTANCE, -1) }

    private class FakeHudSocket : LincolnHudSocket {
        val updates = mutableListOf<Triple<Int, Int, String>>()
        val tokens = mutableListOf<List<Double?>>()
        var starts = 0
        var ends = 0
        var running = false
        val events = mutableListOf<String>()

        override fun start() {
            if (running) return
            running = true
            starts++
            events += "start"
        }

        override fun update(index: Int, metres: Int, street: String, latitude: Double?, longitude: Double?) {
            updates += Triple(index, metres, street)
            tokens += listOf(latitude, longitude)
            events += "update"
        }

        override fun end() {
            if (!running) return
            ends++
            events += "end"
        }

        override fun stop() {
            if (!running) return
            running = false
            events += "stop"
        }

        override fun diagnostics(): String = "fake"

        fun clear() {
            updates.clear()
            tokens.clear()
            starts = 0
            ends = 0
            events.clear()
        }
    }
}

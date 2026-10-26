package com.shilapi.xcertplay.lincoln

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The junction identity and the non-increasing distance the cluster's own adapter needs before it
 * can draw progress. Ported from the build the cluster adapter was verified against; the receiving
 * model is the adapter's `distance / baseline * 256` with no clamp of its own.
 */
class LincolnManeuverTrackerTest {
    private class Drive {
        val tracker = LincolnManeuverTracker()
        var now = 0L
        var revision = 0L

        fun sample(
            raw: Int,
            index: Int = 7,
            generation: Long = 0,
            fresh: Boolean = true,
            seconds: Long = 1,
            stabilize: Boolean = true,
        ): Pair<LincolnManeuverToken, Int> {
            now += seconds * 1_000_000_000L
            if (fresh) revision++
            val token = tracker.update(index, "turn_right", "Road", raw, generation, revision, now, stabilize)
            return token to tracker.distanceMeters
        }
    }

    @Test fun correctionsAndRoundingBoundariesKeepOneIdentityAndMonotoneDistance() {
        for (raw in listOf(
            listOf(600, 580, 582, 575),
            listOf(2000, 1499, 1500, 1499, 1500, 1498, 1399),
            listOf(1200, 999, 1000, 998),
            listOf(101000, 99999, 100000, 99998),
        )) {
            val drive = Drive()
            val packets = raw.map { drive.sample(it) }
            assertEquals(1, packets.map { it.first }.distinct().size)
            assertTrue(packets.zipWithNext().all { (a, b) -> b.second <= a.second })
        }
        val drive = Drive()
        assertEquals(listOf(600, 580, 580, 575), listOf(600, 580, 582, 575).map { drive.sample(it).second })
    }

    @Test fun aLargeRiseNeedsTwoFreshFramesAndElapsedTime() {
        val drive = Drive()
        drive.sample(600)
        val old = drive.sample(300)
        assertEquals(old, drive.sample(360))
        repeat(6) { assertEquals(old, drive.sample(360, fresh = false)) }
        // Repeated snapshots do not count. A second actual phone distance confirms the rise,
        // including for a low-frequency source whose fresh frames are several seconds apart.
        val resumed = drive.sample(358)
        assertNotEquals(old.first, resumed.first)
        // This line's cluster display precision is 5 m below a kilometre, so 358 is sent as 360.
        assertEquals(360, resumed.second)
        assertEquals(resumed.first, drive.sample(350).first)

        val burst = Drive()
        val before = burst.sample(300)
        assertEquals(before, burst.sample(360, seconds = 0))
        assertEquals(before, burst.sample(359, seconds = 0))
        assertNotEquals(before.first, burst.sample(358).first)
    }

    @Test fun aSingleOutlierOrAnInterruptedRiseDoesNotReset() {
        val drive = Drive()
        val old = drive.sample(300)
        assertEquals(old, drive.sample(900))
        assertEquals(old.first, drive.sample(290).first)
        drive.sample(350)
        drive.sample(310) // Below the threshold breaks the large-rise confirmation.
        assertEquals(old.first, drive.sample(350).first)
        assertEquals(old.first, drive.sample(280).first)
    }

    @Test fun sustainedSmallRisesRecoverButKeepaliveAndLyricsCannotConfirmThem() {
        val drive = Drive()
        val old = drive.sample(20)
        assertEquals(old, drive.sample(25))
        repeat(10) { assertEquals(old, drive.sample(25, fresh = false)) }
        assertEquals(old, drive.sample(25))
        val next = drive.sample(25)
        assertNotEquals(old.first, next.first)
        assertEquals(25, next.second)
        assertEquals("persistent-distance-increase", drive.tracker.resetReason)

        val continuous = Drive()
        val baseline = continuous.sample(20)
        repeat(5) { assertEquals(baseline, continuous.sample(25)) }
        assertNotEquals(baseline.first, continuous.sample(25).first)

        val oneMetreJitter = Drive()
        val jitterBaseline = oneMetreJitter.sample(20)
        repeat(10) {
            assertEquals(jitterBaseline, oneMetreJitter.sample(20 + (it % 4) + 1))
        }
    }

    @Test fun sparseNewDistanceFramesStillRecoverARealIncrease() {
        val small = Drive()
        val smallOld = small.sample(20)
        assertEquals(smallOld, small.sample(25, seconds = 4))
        assertEquals(smallOld, small.sample(25, seconds = 4))
        val smallAccepted = small.sample(25, seconds = 4)
        assertNotEquals(smallOld.first, smallAccepted.first)
        assertEquals(25, smallAccepted.second)

        val large = Drive()
        val largeOld = large.sample(20)
        assertEquals(largeOld, large.sample(80, seconds = 4))
        val largeAccepted = large.sample(80, seconds = 4)
        assertNotEquals(largeOld.first, largeAccepted.first)
        assertEquals(80, largeAccepted.second)
    }

    @Test fun subFiveMetreNoiseCannotPrimeEitherConfirmationBand() {
        val large = Drive()
        val largeOld = large.sample(100)
        repeat(8) { assertEquals(largeOld, large.sample(101 + it % 4)) }
        assertEquals(largeOld, large.sample(150))
        val largeAccepted = large.sample(151)
        assertNotEquals(largeOld.first, largeAccepted.first)

        val small = Drive()
        val smallOld = small.sample(100)
        repeat(8) { assertEquals(smallOld, small.sample(101 + it % 4)) }
        assertEquals(smallOld, small.sample(105))
        assertEquals(smallOld, small.sample(106, seconds = 4))
        val smallAccepted = small.sample(107)
        assertNotEquals(smallOld.first, smallAccepted.first)
    }

    @Test fun crossingBetweenTheTwoBandsRestartsConfirmation() {
        val drive = Drive()
        val old = drive.sample(100)
        assertEquals(old, drive.sample(105))
        assertEquals(old, drive.sample(106, seconds = 5))
        assertEquals(old, drive.sample(150)) // First large-band sample; small evidence is dropped.
        assertEquals(old, drive.sample(149)) // First small-band sample; large evidence is dropped.
        assertEquals(old, drive.sample(150))
        val accepted = drive.sample(151)
        assertNotEquals(old.first, accepted.first)
    }

    @Test fun switchingBackFromACustomReceiverDoesNotPinItsLastDistance() {
        val drive = Drive()
        drive.sample(600)
        val custom = drive.sample(580, stabilize = false)
        repeat(5) { assertEquals(custom, drive.sample(585)) }
        val accepted = drive.sample(585)
        assertNotEquals(custom.first, accepted.first)
        assertEquals(585, accepted.second)
    }

    @Test fun theZeroPlaceholderRecoversAndArrivalNoiseIsDebounced() {
        val drive = Drive()
        val zero = drive.sample(0)
        val valid = drive.sample(10)
        assertNotEquals(zero.first, valid.first)
        assertEquals(10, valid.second)
        val arrived = drive.sample(0)
        assertEquals(arrived, drive.sample(1))
        assertEquals(arrived, drive.sample(0))
        assertEquals(arrived, drive.sample(200))
        assertNotEquals(arrived.first, drive.sample(199).first)
    }

    @Test fun aNewManeuverOrExplicitRerouteDoesNotWaitForConfirmation() {
        val drive = Drive()
        val first = drive.sample(600)
        drive.sample(580)
        drive.sample(900)
        val turn = drive.sample(100, index = 8)
        assertNotEquals(first.first, turn.first)
        val reroute = drive.sample(90, index = 8, generation = 1)
        assertNotEquals(turn.first, reroute.first)
        assertEquals(90, reroute.second)
    }

    /**
     * The adapter divides whatever it received by the first distance it saw under an identity, so
     * any contiguous subsequence of the transmitted stream has to stay inside 0..256 — that is the
     * property a receiver which missed packets, or restarted mid-leg, relies on.
     */
    @Test fun everyOrderedSubsequenceStaysInsideTheReceiverRange() {
        val drive = Drive()
        val raw = listOf(
            2000, 1499, 1500, 1500, 1490, 600, 580, 582, 575, 650, 650,
            600, 0, 1, 0, 50, 50, 0, 2_000_000, 2_000_000, 1_000_000,
        )
        val packets = raw.map { drive.sample(it) }
        for (start in packets.indices) {
            for (stride in 1..4) {
                var token: LincolnManeuverToken? = null
                var baseline = 0
                for (i in start until packets.size step stride) {
                    val (nextToken, distance) = packets[i]
                    if (nextToken != token) baseline = distance
                    token = nextToken
                    val progress = if (baseline == 0) 1 else (distance.toFloat() / baseline * 256f).toInt()
                    assertTrue("start=$start stride=$stride i=$i value=$progress", progress in 0..256)
                }
            }
        }
    }
}

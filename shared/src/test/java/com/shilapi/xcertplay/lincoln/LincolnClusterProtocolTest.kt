package com.shilapi.xcertplay.lincoln

import com.shilapi.xcertplay.hud.ClusterDistancePrecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LincolnClusterProtocolTest {
    @Test
    fun mapsTheClusterIconsToTheNavigationAppTurnIndexes() {
        assertEquals(7, LincolnClusterProtocol.turnIndex(2)) // left
        assertEquals(3, LincolnClusterProtocol.turnIndex(3)) // right
        assertEquals(3, LincolnClusterProtocol.turnIndex(7)) // sharp right
        assertEquals(8, LincolnClusterProtocol.turnIndex(4)) // slight left
        assertEquals(2, LincolnClusterProtocol.turnIndex(5)) // slight right
        assertEquals(7, LincolnClusterProtocol.turnIndex(6)) // sharp left
        assertEquals(0, LincolnClusterProtocol.turnIndex(8)) // U-turn, right-hand traffic
        assertEquals(0, LincolnClusterProtocol.turnIndex(19)) // U-turn, left-hand traffic
        assertEquals(1, LincolnClusterProtocol.turnIndex(9)) // straight
        assertEquals(9, LincolnClusterProtocol.turnIndex(11)) // roundabout
        assertEquals(9, LincolnClusterProtocol.turnIndex(17)) // roundabout, left-hand traffic
        assertEquals(10, LincolnClusterProtocol.turnIndex(12)) // roundabout exit
        assertEquals(10, LincolnClusterProtocol.turnIndex(18)) // roundabout exit, left-hand traffic
        assertEquals(24, LincolnClusterProtocol.turnIndex(15)) // destination
        assertEquals(LincolnClusterProtocol.NO_TURN_INDEX, LincolnClusterProtocol.turnIndex(0))
        assertEquals(LincolnClusterProtocol.NO_TURN_INDEX, LincolnClusterProtocol.turnIndex(22))
    }

    @Test
    fun mapsAppleManeuversThroughTheSharedClusterIconMapping() {
        assertEquals(7, LincolnClusterProtocol.turnIndex(1, 0))
        assertEquals(3, LincolnClusterProtocol.turnIndex(2, 0))
        assertEquals(8, LincolnClusterProtocol.turnIndex(13, 0))
        assertEquals(2, LincolnClusterProtocol.turnIndex(14, 0))
        assertEquals(7, LincolnClusterProtocol.turnIndex(47, 0))
        assertEquals(3, LincolnClusterProtocol.turnIndex(48, 0))
        assertEquals(0, LincolnClusterProtocol.turnIndex(4, 0))
        assertEquals(0, LincolnClusterProtocol.turnIndex(4, 1))
        assertEquals(0, LincolnClusterProtocol.turnIndex(19, 0))
        assertEquals(9, LincolnClusterProtocol.turnIndex(6, 0))
        assertEquals(9, LincolnClusterProtocol.turnIndex(6, 1))
        assertEquals(10, LincolnClusterProtocol.turnIndex(7, 0))
        assertEquals(10, LincolnClusterProtocol.turnIndex(7, 1))
        assertEquals(9, LincolnClusterProtocol.turnIndex(30, 0)) // roundabout exit 3
        assertEquals(24, LincolnClusterProtocol.turnIndex(10, 0))
        assertEquals(1, LincolnClusterProtocol.turnIndex(3, 0))
        assertEquals(1, LincolnClusterProtocol.turnIndex(11, 0))
        assertEquals(LincolnClusterProtocol.NO_TURN_INDEX, LincolnClusterProtocol.turnIndex(null, 0))
        // Apple's "unknown" type must not become a wrong arrow or a false straight.
        assertEquals(LincolnClusterProtocol.NO_TURN_INDEX, LincolnClusterProtocol.turnIndex(0, 0))
    }

    @Test
    fun namesTheArrowsTheWayTheHeadUnitSpellsThem() {
        assertEquals("turn_left", LincolnClusterProtocol.turnName(7))
        assertEquals("turn_left.png", LincolnClusterProtocol.iconFileName(7))
        assertEquals("turn_front", LincolnClusterProtocol.turnName(1))
        assertEquals("turn_front.png", LincolnClusterProtocol.iconFileName(1))
        assertEquals("", LincolnClusterProtocol.turnName(LincolnClusterProtocol.NO_TURN_INDEX))
        assertEquals("", LincolnClusterProtocol.iconFileName(LincolnClusterProtocol.NO_TURN_INDEX))
    }

    @Test
    fun fillsEveryTurnByTurnAliasTheTestedBuildSends() {
        val extras = LincolnClusterProtocol.turnByTurnExtras(index = 7, metres = 350, street = "Test Road")

        assertEquals(7, extras[LincolnClusterProtocol.EXTRA_TURN_TYPE])
        assertEquals(7, extras[LincolnClusterProtocol.EXTRA_TURN_TYPE_ALIAS])
        assertEquals(7, extras[LincolnClusterProtocol.EXTRA_MANEUVER_ID])
        assertEquals(7, extras[LincolnClusterProtocol.EXTRA_RES_ID])
        assertEquals("turn_left.png", extras[LincolnClusterProtocol.EXTRA_MANEUVER_NAME])
        assertEquals("turn_left.png", extras[LincolnClusterProtocol.EXTRA_ICON_NAME])
        // "distance" is a string on the tested build, the numeric aliases are ints.
        assertEquals("350", extras[LincolnClusterProtocol.EXTRA_DISTANCE])
        assertEquals(350, extras[LincolnClusterProtocol.EXTRA_REMAIN_DIST])
        assertEquals(350, extras[LincolnClusterProtocol.EXTRA_REMAIN_DISTANCE])
        assertEquals(350, extras[LincolnClusterProtocol.EXTRA_REMAIN_DIST_UNDERSCORE])
        assertEquals(350, extras[LincolnClusterProtocol.EXTRA_DISTANCE_TO_NEXT])
        assertEquals("350 米", extras[LincolnClusterProtocol.EXTRA_MANEUVER_TIPS])
        assertEquals("Test Road", extras[LincolnClusterProtocol.EXTRA_STREET_NAME])
        assertEquals("Test Road", extras[LincolnClusterProtocol.EXTRA_CURRENT_STREET_NAME])
        assertEquals("Test Road", extras[LincolnClusterProtocol.EXTRA_ROAD_NAME])
        assertEquals("", extras[LincolnClusterProtocol.EXTRA_STREET_NAME_2])
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

    @Test
    fun countsClusterTextInUnitsRatherThanUtf8Bytes() {
        val chinese = "导航路名".repeat(8)
        assertEquals(chinese, LincolnClusterProtocol.sanitizeText(chinese))
        assertEquals(chinese, LincolnClusterProtocol.sanitizeText(chinese + "继续行驶"))
        // The budget is 32 units, which is 96 bytes for the same text: a byte budget would send a
        // third of the road name the cluster can actually show.
        assertEquals(96, chinese.toByteArray(Charsets.UTF_8).size)
        assertEquals("x".repeat(LincolnClusterProtocol.MAX_TEXT_CHARS),
            LincolnClusterProtocol.sanitizeText("x".repeat(40)))
    }

    @Test
    fun keepsSupplementaryCharactersAndEmojiWholeAtTheLimit() {
        for (character in listOf("𠀀", "🚗")) {
            val fits = "中".repeat(LincolnClusterProtocol.MAX_TEXT_CHARS - 2) + character
            assertEquals(fits, LincolnClusterProtocol.sanitizeText(fits + "终点"))
            // One unit short of the pair: the character must go, not half of it.
            assertEquals("中".repeat(LincolnClusterProtocol.MAX_TEXT_CHARS - 1),
                LincolnClusterProtocol.sanitizeText("中".repeat(LincolnClusterProtocol.MAX_TEXT_CHARS - 1) + character))
        }
    }

    @Test
    fun neverSplitsCombiningCharactersOrEmojiSequences() {
        val clusters = listOf(
            "e\u0301",                                                             // e + combining acute
            "\u845B\uDB40\uDD00",                                                 // 葛 + selector supplement
            "👍\uD83C\uDFFD",                                                      // thumbs up + skin tone
            "\uD83C\uDDE8\uD83C\uDDF3",                                           // China flag: two regional indicators
            "\uD83D\uDC69\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66", // family, joined
            "#\uFE0F\u20E3",                                                       // keycap
            "\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08",                             // rainbow flag
            "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F",
        )
        val limit = LincolnClusterProtocol.MAX_TEXT_CHARS
        for (cluster in clusters) {
            val fits = "中".repeat(limit - cluster.length) + cluster
            assertEquals("A sequence inside the budget stays whole: $cluster",
                fits, LincolnClusterProtocol.sanitizeText(fits + "终点"))
            assertEquals("A sequence crossing the budget is dropped whole: $cluster",
                "中".repeat(limit - 1),
                LincolnClusterProtocol.sanitizeText("中".repeat(limit - 1) + cluster))
        }
    }

    @Test
    fun neverKeepsOnlyHalfOfAFlag() {
        // A cut that lands between the two regional indicators of one flag must give both up.
        val cutInside = "中".repeat(30)
        val flag = "\uD83C\uDDE8\uD83C\uDDF3"
        assertEquals(cutInside, LincolnClusterProtocol.sanitizeText(cutInside + flag + "x"))
        // A flag that fits whole stays whole: the pair check must not drop the second indicator.
        val cutAfter = "中".repeat(28)
        assertEquals(cutAfter + flag, LincolnClusterProtocol.sanitizeText(cutAfter + flag + "终点"))
    }

    @Test
    fun truncatesStreetNamesToTheLengthTheClusterAccepts() {
        assertEquals("Test Road", LincolnClusterProtocol.streetName("Test Road"))
        assertEquals("", LincolnClusterProtocol.streetName("  "))
        assertEquals("", LincolnClusterProtocol.streetName(null))
        assertEquals(LincolnClusterProtocol.STREET_NAME_MAX, LincolnClusterProtocol.streetName("y".repeat(40)).length)
        // The shorter street-name budget is grapheme-safe as well.
        val limit = LincolnClusterProtocol.STREET_NAME_MAX
        assertEquals("路".repeat(limit - 1), LincolnClusterProtocol.streetName("路".repeat(limit - 1) + "🚗"))
        assertEquals("路".repeat(limit - 2) + "🚗",
            LincolnClusterProtocol.streetName("路".repeat(limit - 2) + "🚗" + "终点"))
    }
}

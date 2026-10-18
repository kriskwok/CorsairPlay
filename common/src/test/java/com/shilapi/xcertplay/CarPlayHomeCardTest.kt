package com.shilapi.xcertplay

import com.shilapi.xcertplay.glance.CarPlayGlance
import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlayHomeCardTest {
    @Test fun qqMusicStyleLyricsBecomeTitleAndSubtitle() {
        assertEquals(
            CarPlayHomeCard.Content("月下煮茶", "风啊轻轻吹", true),
            CarPlayHomeCard.content(
                CarPlayGlance.Snapshot(
                    songTitle = "风啊轻轻吹",
                    songArtist = "月下煮茶",
                    playing = true,
                ),
            ),
        )
    }

    @Test fun anOrdinarySongTitleStaysOnOneLine() {
        assertEquals(
            CarPlayHomeCard.Content("晴天", "", true),
            CarPlayHomeCard.content(CarPlayGlance.Snapshot(songTitle = "晴天", playing = true)),
        )
    }

    @Test fun pausedPlaybackReturnsTheDefaultCarPlayCard() {
        assertEquals(
            false,
            CarPlayHomeCard.content(
                CarPlayGlance.Snapshot(
                    songTitle = "风啊轻轻吹",
                    songArtist = "月下煮茶",
                    playing = false,
                ),
            ).playing,
        )
    }

    @Test fun disabledOptInReturnsTheDefaultCarPlayCard() {
        assertEquals(
            CarPlayHomeCard.Content("", "", false),
            CarPlayHomeCard.content(
                CarPlayGlance.Snapshot(
                    songTitle = "风啊轻轻吹",
                    songArtist = "月下煮茶",
                    playing = true,
                ),
                enabled = false,
            ),
        )
    }
}

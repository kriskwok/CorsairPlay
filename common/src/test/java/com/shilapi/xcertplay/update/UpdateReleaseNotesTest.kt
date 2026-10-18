package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateReleaseNotesTest {
    @Test fun removesCommonMarkdownMarkersWithoutDroppingTheText() {
        val markdown = """
            ## Changes
            - Fixed **audio** noise
            * Added [history](https://example.com/history)
        """.trimIndent()

        assertEquals(
            "Changes\n• Fixed audio noise\n• Added history",
            UpdateReleaseNotes.plainText(markdown),
        )
    }

    @Test fun keepsPlainReleaseNotesReadable() {
        assertEquals(
            "1. 修复微信语音异常\n2. 优化仪表稳定性",
            UpdateReleaseNotes.plainText("1. 修复微信语音异常\r\n2. 优化仪表稳定性"),
        )
    }
}

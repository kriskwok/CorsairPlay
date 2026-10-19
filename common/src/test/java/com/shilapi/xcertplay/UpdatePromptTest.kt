package com.shilapi.xcertplay

import android.app.AlertDialog
import android.app.Application
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.update.UpdateAvailability
import com.shilapi.xcertplay.update.UpdateRelease
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w900dp-h600dp-land")
class UpdatePromptTest {
    private val application: Application = RuntimeEnvironment.getApplication()

    @After fun clear() = UpdateAvailability.clearAllForTest(application)

    @Test fun promptShowsDetailsOnceAndReturnsOnlyForANewerRelease() {
        UpdateAvailability.save(application, release("v0.2.27", "修复开机连接"))
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val first = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())

        assertTrue(first.isShowing)
        assertTrue(first.getButton(AlertDialog.BUTTON_POSITIVE).text ==
            activity.getString(R.string.update_prompt_now))
        assertTrue(first.getButton(AlertDialog.BUTTON_NEGATIVE).text ==
            activity.getString(R.string.close))
        assertTrue(first.findViewById<android.widget.TextView>(android.R.id.message)?.text?.contains("修复开机连接") == true)

        first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        controller.pause().resume()
        assertFalse(first.isShowing)

        UpdateAvailability.save(application, release("v0.2.28", "新增连接优化"))
        controller.pause().resume()
        val second = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        assertNotSame(first, second)
        assertTrue(second.isShowing)
        controller.pause().stop().destroy()
    }

    private fun release(tag: String, notes: String) = UpdateRelease(
        tagName = tag,
        apkName = "CorsairPlay-$tag.apk",
        apkUrl = "https://example.com/CorsairPlay-$tag.apk",
        checksumsUrl = "https://example.com/SHA256SUMS.txt",
        releaseNotes = notes,
    )
}

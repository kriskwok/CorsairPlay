package com.shilapi.xcertplay.update

import android.content.Context
import android.os.Environment
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Android 9 and older have no MediaStore Downloads, so the copy goes to the app's external files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateApkExportTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun copiesTheApkAndKeepsDownloadedVersions() {
        val directory = File(context.getExternalFilesDir(null), "update").apply { mkdirs() }
        File(directory, "DiPlay-0.2.14.apk").writeText("old")
        val apk = File(context.cacheDir, "DiPlay-0.2.15.apk").apply { writeText("new") }

        val saved = UpdateApkExport.copy(context, apk)!!
        val path = saved.path

        assertEquals(File(directory, "DiPlay-0.2.15.apk").absolutePath, path)
        assertEquals("new", File(path).readText())
        assertNull(saved.uri)
        assertTrue(File(directory, "DiPlay-0.2.14.apk").exists())
        assertTrue(apk.exists())
    }

    @Test fun historicalCopyUsesSystemDownloadsAndKeepsOtherVersions() {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Lincoln",
        ).apply { mkdirs() }
        val older = File(directory, "CorsairPlay-0.2.20.apk").apply { writeText("older") }
        val apk = File(context.cacheDir, "CorsairPlay-0.2.24.apk").apply { writeText("new") }

        val saved = UpdateApkExport.copyToSystemDownloads(context, apk)

        assertEquals(File(directory, apk.name).absolutePath, saved.path)
        assertEquals("new", File(saved.path).readText())
        assertTrue(older.exists())
    }
}

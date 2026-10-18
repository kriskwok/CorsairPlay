package com.shilapi.xcertplay.update

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Copies a verified APK where a file manager can reach it, for head units that cannot use the system installer. */
internal object UpdateApkExport {
    private const val APK_MIME = "application/vnd.android.package-archive"
    private val DOWNLOADS_DIRECTORY = "${Environment.DIRECTORY_DOWNLOADS}/Lincoln"

    /** [path] is shown to the driver. [uri] is set only for a MediaStore copy that file managers can open. */
    class Saved(val path: String, val uri: Uri?)

    /** Returns null when no public copy could be made. */
    fun copy(context: Context, apk: File): Saved? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return copyToDownloads(context.contentResolver, apk, replaceAll = false)
            } catch (_: Exception) {
                // Fall through: some head units ship without a working Downloads provider.
            }
        }
        return try {
            val directory = File(context.getExternalFilesDir(null) ?: return null, "update")
            Saved(copyToDirectory(directory, apk).absolutePath, null)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Saves a historical APK in the visible system Downloads folder without pruning other versions.
     * Android 9 and older need WRITE_EXTERNAL_STORAGE; Android 10+ uses MediaStore.
     */
    fun copyToSystemDownloads(context: Context, apk: File): Saved {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return copyToDownloads(context.contentResolver, apk, replaceAll = false)
        }
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Lincoln",
        )
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Downloads/Lincoln is unavailable")
        }
        val destination = File(directory, apk.name)
        try {
            apk.copyTo(destination, overwrite = true)
            if (!destination.isFile || destination.length() != apk.length()) {
                throw IOException("Downloads/Lincoln did not keep the APK")
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
        MediaScannerConnection.scanFile(
            context.applicationContext,
            arrayOf(destination.absolutePath),
            arrayOf(APK_MIME),
            null,
        )
        return Saved(destination.absolutePath, null)
    }

    private fun copyToDirectory(directory: File, apk: File): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Update storage is unavailable")
        // Keep downloaded versions: they are the driver's rollback copies.
        return apk.copyTo(File(directory, apk.name), overwrite = true)
    }

    private fun copyToDownloads(resolver: ContentResolver, apk: File, replaceAll: Boolean): Saved {
        // Only entries created by this app are visible to these queries, so other apps' files are safe.
        val selection = if (replaceAll) {
            "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.MIME_TYPE}=?"
        } else {
            "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.MIME_TYPE}=? AND " +
                "${MediaStore.Downloads.DISPLAY_NAME}=?"
        }
        val arguments = if (replaceAll) {
            arrayOf("$DOWNLOADS_DIRECTORY/", APK_MIME)
        } else {
            arrayOf("$DOWNLOADS_DIRECTORY/", APK_MIME, apk.name)
        }
        resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, selection, arguments)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, apk.name)
            put(MediaStore.Downloads.MIME_TYPE, APK_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, DOWNLOADS_DIRECTORY)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads could not create the update")
        try {
            val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("Update destination is unavailable")
            apk.inputStream().use { input -> output.use { input.copyTo(it) } }
            val published = resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
            if (published != 1) throw IOException("Downloads could not publish the update")
            // MediaStore renames on a name clash, so report the name it actually stored.
            val name = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: apk.name
            return Saved("$DOWNLOADS_DIRECTORY/$name", uri)
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
}

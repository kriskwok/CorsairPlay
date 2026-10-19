package com.shilapi.xcertplay.update

import org.json.JSONArray

/** The published release that a newer build can be downloaded from. */
internal data class UpdateRelease(
    val tagName: String,
    val apkName: String,
    val apkUrl: String,
    val checksumsUrl: String,
    val releaseNotes: String = "",
    val prerelease: Boolean = false,
)

internal object UpdateCatalog {
    internal const val CHECKSUMS_FILE = "SHA256SUMS.txt"

    /** Returns the highest stable release that carries an APK and its checksums, or null. */
    internal fun parse(json: String): UpdateRelease? =
        bestStable(parseAll(json))

    internal fun bestStable(releases: List<UpdateRelease>): UpdateRelease? =
        releases
            .filter { !it.prerelease && UpdateVersion.isStable(it.tagName) }
            .maxWithOrNull(
                compareBy<UpdateRelease>(
                    { UpdateVersion.parse(it.tagName)?.first ?: -1 },
                    { UpdateVersion.parse(it.tagName)?.second ?: -1 },
                    { UpdateVersion.parse(it.tagName)?.third ?: -1 },
                ),
            )

    /** Returns every published release that can be downloaded and verified, newest first. */
    internal fun parseAll(json: String): List<UpdateRelease> {
        val releases = JSONArray(json)
        val parsed = mutableListOf<UpdateRelease>()
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft")) continue
            val assets = release.optJSONArray("assets") ?: continue
            var apkName = ""
            var apkUrl = ""
            var checksumsUrl = ""
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (name.endsWith(".apk")) {
                    apkName = name
                    apkUrl = url
                }
                if (name.equals(CHECKSUMS_FILE, ignoreCase = true)) checksumsUrl = url
            }
            if (apkUrl.isEmpty() || checksumsUrl.isEmpty()) continue
            parsed += UpdateRelease(
                tagName = release.optString("tag_name"),
                apkName = apkName,
                apkUrl = apkUrl,
                checksumsUrl = checksumsUrl,
                releaseNotes = release.optString("body"),
                prerelease = release.optBoolean("prerelease"),
            )
        }
        return parsed
    }
}

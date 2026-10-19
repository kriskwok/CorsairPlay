package com.shilapi.xcertplay.update

import java.io.IOException
import java.net.URL
import java.net.URLConnection

internal object UpdateReleaseLookup {
    internal data class CatalogSource(val url: String, val accept: String)

    private val defaultSources = listOf(
        CatalogSource(UpdateClient.GITHUB_RELEASES_URL, "application/vnd.github+json"),
        CatalogSource(UpdateClient.MIRROR_RELEASES_URL, "application/json"),
    )

    fun latest(
        installedVersion: String,
        userAgent: String,
        openConnection: (URL) -> URLConnection = { it.openConnection() },
    ): UpdateRelease? {
        val release = UpdateCatalog.bestStable(fetchCatalog(userAgent, openConnection)) ?: return null
        return release.takeIf { UpdateVersion.isNewer(it.tagName, installedVersion) }
    }

    fun history(
        userAgent: String,
        openConnection: (URL) -> URLConnection = { it.openConnection() },
    ): List<UpdateRelease> = fetchCatalog(userAgent, openConnection)

    internal fun fetchCatalog(
        userAgent: String,
        openConnection: (URL) -> URLConnection = { it.openConnection() },
        sources: List<CatalogSource> = defaultSources,
    ): List<UpdateRelease> {
        require(sources.isNotEmpty()) { "At least one release source is required" }
        var firstFailure: Exception? = null
        val failures = mutableListOf<String>()
        for ((index, source) in sources.withIndex()) {
            try {
                val json = UpdateClient.fetchText(source.url, source.accept, userAgent, openConnection)
                val releases = UpdateCatalog.parseAll(json)
                if (releases.isNotEmpty() || index == sources.lastIndex) return releases
            } catch (failure: Exception) {
                if (firstFailure == null) firstFailure = failure
                failures += "${sourceName(source.url)}: ${failure.message ?: failure.javaClass.simpleName}"
            }
        }
        throw IOException(
            "All update sources failed: ${failures.joinToString("; ")}",
            firstFailure ?: IOException("No release source was available"),
        )
    }

    private fun sourceName(url: String): String = when {
        url == UpdateClient.GITHUB_RELEASES_URL -> "GitHub"
        url == UpdateClient.MIRROR_RELEASES_URL -> "mirror"
        else -> runCatching { URL(url).host }.getOrNull() ?: url
    }
}

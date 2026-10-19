package com.shilapi.xcertplay.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

internal object UpdateClient {
    internal const val GITHUB_RELEASES_URL =
        "https://api.github.com/repos/kriskwok/DiPlay/releases?per_page=30"
    internal const val MIRROR_RELEASES_URL =
        "https://node.czwyf.top:9443/corsairplay/releases.json"
    internal const val MIRROR_ROOT_URL =
        "https://node.czwyf.top:9443/corsairplay"
    private const val CONNECT_TIMEOUT_MILLIS = 10_000
    private const val READ_TIMEOUT_MILLIS = 30_000
    private const val MAXIMUM_TEXT_BYTES = 4 * 1024 * 1024
    private const val BUFFER_BYTES = 64 * 1024

    internal fun fetchText(
        url: String,
        accept: String?,
        userAgent: String,
        openConnection: (URL) -> URLConnection = { it.openConnection() },
    ): String {
        val connection = openConnection(URL(url)) as? HttpURLConnection
            ?: throw IOException("Request to $url did not open an HTTP connection")
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.useCaches = false
            accept?.let { connection.setRequestProperty("Accept", it) }
            connection.setRequestProperty("User-Agent", userAgent)
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { readUtf8Limited(url, it) }.orEmpty()
            if (statusCode !in 200..299) throw IOException("Request to $url failed: $statusCode")
            return body
        } finally {
            connection.disconnect()
        }
    }

    internal fun download(
        url: String,
        destination: File,
        onProgress: (Long, Long?) -> Unit,
    ) {
        download(listOf(url), destination, onProgress, { it.openConnection() })
    }

    internal fun download(
        urls: List<String>,
        destination: File,
        onProgress: (Long, Long?) -> Unit,
    ) {
        download(urls, destination, onProgress, { it.openConnection() })
    }

    internal fun download(
        urls: List<String>,
        destination: File,
        onProgress: (Long, Long?) -> Unit,
        openConnection: (URL) -> URLConnection,
    ) {
        var firstFailure: Exception? = null
        for (url in urls.distinct()) {
            try {
                downloadOne(url, destination, onProgress, openConnection)
                return
            } catch (failure: Exception) {
                if (firstFailure == null) firstFailure = failure
            }
        }
        throw firstFailure ?: IOException("No download source was configured")
    }

    internal fun apkUrls(release: UpdateRelease): List<String> =
        listOf(release.apkUrl, mirrorAssetUrl(release.tagName, release.apkName))

    internal fun checksumsUrls(release: UpdateRelease): List<String> =
        listOf(
            release.checksumsUrl,
            mirrorAssetUrl(release.tagName, UpdateCatalog.CHECKSUMS_FILE),
        )

    private fun mirrorAssetUrl(tagName: String, fileName: String): String =
        "$MIRROR_ROOT_URL/releases/${encodePath(tagName)}/${encodePath(fileName)}"

    private fun encodePath(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun downloadOne(
        url: String,
        destination: File,
        onProgress: (Long, Long?) -> Unit,
        openConnection: (URL) -> URLConnection,
    ) {
        destination.parentFile?.mkdirs()
        val connection = openConnection(URL(url)) as? HttpURLConnection
            ?: throw IOException("Download from $url did not open an HTTP connection")
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.useCaches = false
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) throw IOException("Download from $url failed: $statusCode")
            val total = connection.contentLengthLong.takeIf { it > 0 }
            var written = 0L
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
            }
            if (total != null && written != total) throw IOException("Incomplete download from $url")
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    private fun readUtf8Limited(url: String, stream: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAXIMUM_TEXT_BYTES) throw IOException("Response from $url is too large")
            output.write(buffer, 0, read)
        }
        return output.toString("UTF-8")
    }
}

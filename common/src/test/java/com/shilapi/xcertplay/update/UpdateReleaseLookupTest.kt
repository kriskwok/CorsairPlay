package com.shilapi.xcertplay.update

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.io.IOException
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpdateReleaseLookupTest {
    private lateinit var server: HttpServer
    private lateinit var root: String

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        root = "http://${server.address.hostString}:${server.address.port}"
        server.executor = Executors.newSingleThreadExecutor()
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun fallsBackToTheMirrorWhenGitHubFails() {
        server.createContext("/github") { exchange ->
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.createContext("/mirror") { exchange ->
            val bytes = releaseJson("v0.2.30").toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        val releases = UpdateReleaseLookup.fetchCatalog(
            userAgent = "CorsairPlay/test",
            sources = listOf(
                UpdateReleaseLookup.CatalogSource("$root/github", "application/vnd.github+json"),
                UpdateReleaseLookup.CatalogSource("$root/mirror", "application/json"),
            ),
        )

        assertEquals(listOf("v0.2.30"), releases.map { it.tagName })
        assertEquals("https://mirror.example/CorsairPlay-0.2.30.apk", releases.single().apkUrl)
    }

    @Test
    fun usesGitHubWithoutRequestingTheMirrorWhenItSucceeds() {
        var mirrorRequests = 0
        server.createContext("/github") { exchange ->
            val bytes = releaseJson("v0.2.31").toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/mirror") { exchange ->
            mirrorRequests++
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }

        val releases = UpdateReleaseLookup.fetchCatalog(
            userAgent = "CorsairPlay/test",
            sources = listOf(
                UpdateReleaseLookup.CatalogSource("$root/github", "application/vnd.github+json"),
                UpdateReleaseLookup.CatalogSource("$root/mirror", "application/json"),
            ),
        )

        assertEquals(listOf("v0.2.31"), releases.map { it.tagName })
        assertEquals(0, mirrorRequests)
    }

    @Test
    fun reportsBothSourcesWhenNeitherCanBeReached() {
        server.createContext("/github") { exchange ->
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.createContext("/mirror") { exchange ->
            exchange.sendResponseHeaders(502, -1)
            exchange.close()
        }

        try {
            UpdateReleaseLookup.fetchCatalog(
                userAgent = "CorsairPlay/test",
                sources = listOf(
                    UpdateReleaseLookup.CatalogSource("$root/github", "application/vnd.github+json"),
                    UpdateReleaseLookup.CatalogSource("$root/mirror", "application/json"),
                ),
            )
            throw AssertionError("expected an IOException")
        } catch (expected: IOException) {
            assertEquals(true, expected.message!!.contains("503"))
            assertEquals(true, expected.message!!.contains("502"))
        }
    }

    private fun releaseJson(tag: String) = """
        [
          {
            "tag_name": "$tag",
            "draft": false,
            "prerelease": false,
            "body": "Mirror notes",
            "assets": [
              {
                "name": "CorsairPlay-${tag.removePrefix("v")}.apk",
                "browser_download_url": "https://mirror.example/CorsairPlay-${tag.removePrefix("v")}.apk"
              },
              {
                "name": "SHA256SUMS.txt",
                "browser_download_url": "https://mirror.example/SHA256SUMS.txt"
              }
            ]
          }
        ]
    """.trimIndent()
}

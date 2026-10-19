package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpdateCatalogTest {
    private val releaseJson = """
        [
          {
            "tag_name": "v0.2.15",
            "draft": true,
            "assets": [
              {"name": "DiPlay-0.2.15.apk", "browser_download_url": "https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.15/DiPlay-0.2.15.apk"},
              {"name": "SHA256SUMS.txt", "browser_download_url": "https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.15/SHA256SUMS.txt"}
            ]
          },
          {
            "tag_name": "v0.2.14",
            "draft": false,
            "prerelease": true,
            "body": "### Fixed\n- Release notes are visible",
            "assets": [
              {"name": "DiPlay-0.2.14.apk", "browser_download_url": "https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.14/DiPlay-0.2.14.apk"},
              {"name": "SHA256SUMS.txt", "browser_download_url": "https://github.com/shihabal3amri/DiPlay/releases/download/v0.2.14/SHA256SUMS.txt"}
            ]
          }
        ]
    """.trimIndent()

    @Test
    fun skipsDraftsAndPrereleases() {
        assertNull(UpdateCatalog.parse(releaseJson))
    }

    @Test
    fun returnsTheHighestStableRelease() {
        val release = UpdateCatalog.parse(
            """
                [
                  {"tag_name": "v0.2.18-rc1", "draft": false, "prerelease": false, "assets": [
                    {"name": "CorsairPlay-0.2.18-rc1.apk", "browser_download_url": "https://example.com/v18rc.apk"},
                    {"name": "SHA256SUMS.txt", "browser_download_url": "https://example.com/v18rc.sum"}
                  ]},
                  {"tag_name": "v0.2.16", "draft": false, "prerelease": false, "body": "Stable changes", "assets": [
                    {"name": "CorsairPlay-0.2.16.apk", "browser_download_url": "https://example.com/v16.apk"},
                    {"name": "SHA256SUMS.txt", "browser_download_url": "https://example.com/v16.sum"}
                  ]}
                ]
            """.trimIndent(),
        )

        assertEquals("v0.2.16", release?.tagName)
        assertEquals("CorsairPlay-0.2.16.apk", release?.apkName)
        assertEquals(
            "https://example.com/v16.apk",
            release?.apkUrl,
        )
        assertEquals(
            "https://example.com/v16.sum",
            release?.checksumsUrl,
        )
        assertEquals("Stable changes", release?.releaseNotes)
    }

    @Test
    fun returnsNullWithoutAnApkAsset() {
        val json = """
            [
              {"tag_name": "v0.2.14", "draft": false, "assets": [
                {"name": "source.zip", "browser_download_url": "https://example.com/source.zip"}
              ]}
            ]
        """.trimIndent()
        assertNull(UpdateCatalog.parse(json))
    }

    @Test
    fun returnsNullWithoutChecksums() {
        val json = """
            [
              {"tag_name": "v0.2.14", "draft": false, "assets": [
                {"name": "DiPlay-0.2.14.apk", "browser_download_url": "https://example.com/DiPlay-0.2.14.apk"}
              ]}
            ]
        """.trimIndent()
        assertNull(UpdateCatalog.parse(json))
    }

    @Test
    fun returnsNullForAnEmptyReleaseList() {
        assertNull(UpdateCatalog.parse("[]"))
    }

    @Test
    fun historyReturnsEveryDownloadableReleaseAndSkipsInvalidOnes() {
        val json = """
            [
              {"tag_name": "v0.2.15", "draft": false, "assets": [
                {"name": "CorsairPlay-0.2.15.apk", "browser_download_url": "https://example.com/v15.apk"},
                {"name": "SHA256SUMS.txt", "browser_download_url": "https://example.com/v15.sum"}
              ]},
              {"tag_name": "v0.2.14", "draft": false, "assets": [
                {"name": "source.zip", "browser_download_url": "https://example.com/source.zip"}
              ]},
              {"tag_name": "v0.2.13", "draft": true, "assets": [
                {"name": "CorsairPlay-0.2.13.apk", "browser_download_url": "https://example.com/v13.apk"},
                {"name": "SHA256SUMS.txt", "browser_download_url": "https://example.com/v13.sum"}
              ]},
              {"tag_name": "v0.2.12", "draft": false, "assets": [
                {"name": "CorsairPlay-0.2.12.apk", "browser_download_url": "https://example.com/v12.apk"},
                {"name": "SHA256SUMS.txt", "browser_download_url": "https://example.com/v12.sum"}
              ]}
            ]
        """.trimIndent()

        assertEquals(
            listOf("v0.2.15", "v0.2.12"),
            UpdateCatalog.parseAll(json).map { it.tagName },
        )
    }
}

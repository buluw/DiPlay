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
              {"name": "DiPlay-0.2.15-leapmotor.apk", "browser_download_url": "https://github.com/buluw/DiPlay/releases/download/v0.2.15/DiPlay-0.2.15-leapmotor.apk"},
              {"name": "SHA256SUMS.txt", "browser_download_url": "https://github.com/buluw/DiPlay/releases/download/v0.2.15/SHA256SUMS.txt"}
            ]
          },
          {
            "tag_name": "v0.2.14",
            "draft": false,
            "prerelease": true,
            "assets": [
              {"name": "DiPlay-0.2.14-leapmotor.apk", "browser_download_url": "https://github.com/buluw/DiPlay/releases/download/v0.2.14/DiPlay-0.2.14-leapmotor.apk"},
              {"name": "SHA256SUMS.txt", "browser_download_url": "https://github.com/buluw/DiPlay/releases/download/v0.2.14/SHA256SUMS.txt"}
            ]
          }
        ]
    """.trimIndent()

    @Test
    fun skipsDraftsAndReturnsTheFirstPublishedRelease() {
        val release = UpdateCatalog.parse(releaseJson)
        assertEquals("v0.2.14", release?.tagName)
        assertEquals("DiPlay-0.2.14-leapmotor.apk", release?.apkName)
        assertEquals(
            "https://github.com/buluw/DiPlay/releases/download/v0.2.14/DiPlay-0.2.14-leapmotor.apk",
            release?.apkUrl,
        )
        assertEquals(
            "https://github.com/buluw/DiPlay/releases/download/v0.2.14/SHA256SUMS.txt",
            release?.checksumsUrl,
        )
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
                {"name": "DiPlay-0.2.14-leapmotor.apk", "browser_download_url": "https://example.com/DiPlay-0.2.14-leapmotor.apk"}
              ]}
            ]
        """.trimIndent()
        assertNull(UpdateCatalog.parse(json))
    }

    @Test
    fun returnsNullForAnEmptyReleaseList() {
        assertNull(UpdateCatalog.parse("[]"))
    }
}

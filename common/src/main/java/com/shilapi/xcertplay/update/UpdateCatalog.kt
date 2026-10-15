package com.shilapi.xcertplay.update

import org.json.JSONArray

/** The published release that a newer build can be downloaded from. */
internal data class UpdateRelease(
    val tagName: String,
    val apkName: String,
    val apkUrl: String,
    val checksumsUrl: String,
)

internal object UpdateCatalog {
    internal const val CHECKSUMS_FILE = "SHA256SUMS.txt"
    internal const val RELEASES_PAGE = "https://github.com/buluw/DiPlay/releases"
    private val APK = Regex("DiPlay-[0-9]+\\.[0-9]+\\.[0-9]+(?:\\.[0-9]+)?-leapmotor(?:-[0-9]{8})?\\.apk")

    internal fun trusted(release: UpdateRelease): Boolean {
        if (!APK.matches(release.apkName) || UpdateVersion.parse(release.tagName) == null) return false
        val base = RELEASES_PAGE + "/download/" + release.tagName + "/"
        return release.apkUrl == base + release.apkName &&
            release.checksumsUrl in listOf(base + CHECKSUMS_FILE, base + release.apkName + ".sha256")
    }

    /** Selects the newest unambiguous Leapmotor asset and its exact checksum from our fork. */
    internal fun parse(json: String): UpdateRelease? {
        val releases = JSONArray(json)
        var newest: UpdateRelease? = null
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft")) continue
            val assets = release.optJSONArray("assets") ?: continue
            val apks = mutableListOf<Pair<String, String>>()
            val checksums = mutableMapOf<String, String>()
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (APK.matches(name)) apks.add(name to url)
                if (name == CHECKSUMS_FILE || name.endsWith(".apk.sha256")) {
                    if (checksums.containsKey(name)) checksums[name] = "" else checksums[name] = url
                }
            }
            val (apkName, apkUrl) = apks.singleOrNull() ?: continue
            val checksumsUrl = checksums[apkName + ".sha256"] ?: checksums[CHECKSUMS_FILE] ?: continue
            val candidate = UpdateRelease(release.optString("tag_name"), apkName, apkUrl, checksumsUrl)
            if (trusted(candidate) && (newest == null || UpdateVersion.isNewer(candidate.tagName, newest.tagName))) newest = candidate
        }
        return newest
    }
}

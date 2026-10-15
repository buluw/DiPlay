package com.shilapi.xcertplay.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LeapmotorUpdateTest {
    private fun release(tag: String, apk: String, checksum: String = apk + ".sha256", repo: String = "buluw/DiPlay"): JSONObject {
        val base = "https://github.com/" + repo + "/releases/download/" + tag + "/"
        return JSONObject().put("tag_name", tag).put("prerelease", true).put("assets", JSONArray()
            .put(JSONObject().put("name", apk).put("browser_download_url", base + apk))
            .put(JSONObject().put("name", checksum).put("browser_download_url", base + checksum)))
    }

    @Test fun currentForkSidecarAndLegacyReleaseAliasWorkTogether() {
        val json = JSONArray().put(release("v0.2.19.1", "DiPlay-0.2.19-leapmotor-20261014.apk"))
        val found = UpdateCatalog.parse(json.toString())!!
        assertTrue(found.checksumsUrl.endsWith(found.apkName + ".sha256"))
        assertFalse(UpdateVersion.isNewer(found.tagName, "0.2.19-leapmotor"))
        assertTrue(UpdateVersion.isNewer("v0.2.19.2", "0.2.19-leapmotor"))
        assertTrue(UpdateVersion.isNewer("v0.2.19.10", "0.2.19.2-leapmotor"))
    }

    @Test fun rejectsWrongRepositoryWrongSidecarAndAmbiguousApks() {
        val apk = "DiPlay-0.2.19.2-leapmotor.apk"
        assertNull(UpdateCatalog.parse(JSONArray().put(release("v0.2.19.2", apk, repo = "shihabal3amri/DiPlay")).toString()))
        assertNull(UpdateCatalog.parse(JSONArray().put(release("v0.2.19.2", apk, checksum = "another.apk.sha256")).toString()))
        val duplicate = release("v0.2.19.2", apk)
        duplicate.getJSONArray("assets").put(duplicate.getJSONArray("assets").getJSONObject(0))
        assertNull(UpdateCatalog.parse(JSONArray().put(duplicate).toString()))
    }

    @Test fun skipsIncompleteReleasesAndSelectsNewestVersionRatherThanArrayOrder() {
        val json = JSONArray().put(JSONObject().put("tag_name", "v99.0.0"))
            .put(release("v0.2.19.2", "DiPlay-0.2.19.2-leapmotor.apk", "SHA256SUMS.txt"))
            .put(release("v0.2.19.10", "DiPlay-0.2.19.10-leapmotor.apk"))
        assertEquals("v0.2.19.10", UpdateCatalog.parse(json.toString())?.tagName)
        assertFalse(UpdateVersion.isNewer("v999999999999999999.2.3", "0.2.19"))
    }

    @Test fun androidIdentityMustBeASameSignerMonotonicUpgrade() {
        val installed = UpdatePackageVerifier.Identity("com.shihab.diplay.hudtest", 38, setOf("certificate"))
        assertTrue(UpdatePackageVerifier.accepts(installed, installed.copy(versionCode = 39)))
        assertFalse(UpdatePackageVerifier.accepts(installed, installed))
        assertFalse(UpdatePackageVerifier.accepts(installed, installed.copy(versionCode = 37)))
        assertFalse(UpdatePackageVerifier.accepts(installed, installed.copy(versionCode = 39, packageName = "another.app")))
        assertFalse(UpdatePackageVerifier.accepts(installed, installed.copy(versionCode = 39, signers = setOf("another"))))
        assertFalse(UpdatePackageVerifier.accepts(installed.copy(signers = emptySet()), installed.copy(versionCode = 39, signers = emptySet())))
    }

    @Test fun oldUpstreamCacheIsDiscardedWhenTheChannelChanges() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val upstream = UpdateRelease("v99.0.0", "DiPlay-99.0.0.apk",
            "https://github.com/shihabal3amri/DiPlay/releases/download/v99.0.0/DiPlay-99.0.0.apk",
            "https://github.com/shihabal3amri/DiPlay/releases/download/v99.0.0/SHA256SUMS.txt")
        UpdateAvailability.save(context, upstream)
        assertNull(UpdateAvailability.available(context, "0.2.19-leapmotor"))
        assertNull(context.getSharedPreferences("diplay_update_availability", 0).getString("tag", null))
    }
}

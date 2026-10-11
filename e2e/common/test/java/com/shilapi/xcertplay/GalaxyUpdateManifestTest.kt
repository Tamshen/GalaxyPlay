package com.shilapi.xcertplay

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URI

internal fun updateManifest(size: Int = 3, digest: String = "a".repeat(64), pkg: String = "com.ecarx.carplay"): JSONObject = JSONObject().apply {
    put("schemaVersion", 1); put("appId", "com-ecarx-carplay"); put("packageName", pkg)
    put("version", "0.1.119"); put("versionCode", 147); put("minSdkVersion", 29)
    put("releaseNotes", "- 更新"); put("size", size); put("sha256", digest)
    put("distribution", "direct"); put("parts", JSONArray())
    put("file", JSONObject().put("url", "../../../downloads/com-ecarx-carplay/v147/update.apk").put("size", size).put("sha256", digest))
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GalaxyUpdateManifestTest {
    private val base = GalaxyUpdateManifest.endpoint
    private fun parse(json: JSONObject) = GalaxyUpdateManifest.parse(json.toString(), base, "com.ecarx.carplay")
    private fun reject(json: JSONObject) {
        try { parse(json); fail("invalid manifest accepted") }
        catch (error: GalaxyUpdateException) { assertEquals(GalaxyUpdateFailure.MANIFEST, error.reason) }
    }
    @Test fun relativeUrlAndVersionCodeAreAuthoritative() {
        val release = parse(updateManifest())
        assertEquals(URI("https://car.tamshen.com/downloads/com-ecarx-carplay/v147/update.apk"), release.files.single().uri)
        assertTrue(release.newerThan(146)); assertFalse(release.newerThan(147)); assertFalse(release.newerThan(148))
    }
    @Test fun identityAndSchemaAreRequired() {
        reject(updateManifest().put("packageName", "other.app"))
        reject(updateManifest().put("schemaVersion", 2))
        reject(updateManifest().put("appId", "other-app"))
        reject(updateManifest().put("versionCode", 0))
        reject(updateManifest().put("version", ""))
    }
    @Test fun boundsAndDirectDigestMustMatch() {
        reject(updateManifest().put("size", GalaxyUpdateManifest.MAX_APK_SIZE + 1))
        reject(updateManifest().put("sha256", "bad"))
        reject(updateManifest().put("releaseNotes", "x".repeat(16 * 1024 + 1)))
        reject(updateManifest().also { it.getJSONObject("file").put("size", 2) })
        reject(updateManifest().also { it.getJSONObject("file").put("sha256", "b".repeat(64)) })
    }
    @Test fun downloadsCannotEscapeOriginOrVersionDirectory() {
        listOf("http://car.tamshen.com/downloads/com-ecarx-carplay/v147/a.apk", "https://other.com/a.apk",
            "https://user@car.tamshen.com/downloads/com-ecarx-carplay/v147/a.apk", "file:///tmp/a.apk",
            "../../../downloads/com-ecarx-carplay/v146/a.apk", "../../../downloads/com-ecarx-carplay/v147/../a.apk").forEach { url ->
            reject(updateManifest().also { it.getJSONObject("file").put("url", url) })
        }
    }
    private fun chunks() = updateManifest().put("distribution", "chunked").put("file", JSONObject.NULL).put("chunkSize", 2)
        .put("parts", JSONArray().put(JSONObject().put("index", 0).put("url", "../../../downloads/com-ecarx-carplay/v147/a.part001")
            .put("size", 2).put("sha256", "a".repeat(64)))
            .put(JSONObject().put("index", 1).put("url", "../../../downloads/com-ecarx-carplay/v147/a.part002")
                .put("size", 1).put("sha256", "b".repeat(64))))
    @Test fun chunkOrderAndTotalAreValidated() {
        assertEquals(listOf(2L, 1L), parse(chunks()).files.map { it.size })
        reject(chunks().also { it.getJSONArray("parts").getJSONObject(0).put("index", 1) })
        reject(chunks().put("size", 4))
        reject(chunks().put("parts", JSONArray()))
    }
    @Test fun oldReleasesMayHaveNoNotesOrMinimumSdk() {
        val value = parse(updateManifest().put("releaseNotes", JSONObject.NULL).put("minSdkVersion", JSONObject.NULL))
        assertEquals("", value.notes); assertNull(value.minSdk)
    }
}

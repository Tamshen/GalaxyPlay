package com.shilapi.xcertplay

import org.json.JSONObject
import java.net.URI

internal enum class GalaxyUpdateFailure { NETWORK, MANIFEST, INTEGRITY, APK, SIGNATURE, STORAGE, CANCELLED }
internal class GalaxyUpdateException(val reason: GalaxyUpdateFailure) : Exception(reason.name)
internal fun updateRequire(value: Boolean, reason: GalaxyUpdateFailure = GalaxyUpdateFailure.MANIFEST) {
    if (!value) throw GalaxyUpdateException(reason)
}

internal data class GalaxyUpdateFile(val uri: URI, val size: Long, val sha256: String)
internal data class GalaxyUpdateRelease(
    val packageName: String, val version: String, val code: Long, val minSdk: Int?,
    val notes: String, val size: Long, val sha256: String, val files: List<GalaxyUpdateFile>,
) {
    fun newerThan(code: Long) = this.code > code
}

/** 按 JSON 所在地址解析直链／分片，版本顺序只使用 versionCode。 */
internal object GalaxyUpdateManifest {
    val endpoint: URI = URI("https://car.tamshen.com/api/apps/com-ecarx-carplay/latest.json")
    const val MAX_APK_SIZE = 256L * 1024 * 1024
    const val MAX_METADATA_SIZE = 128 * 1024
    private val hash = Regex("[0-9a-fA-F]{64}")

    fun parse(text: String, base: URI, packageName: String): GalaxyUpdateRelease = try {
        val json = JSONObject(text)
        updateRequire(json.getInt("schemaVersion") == 1 && json.getString("appId") == "com-ecarx-carplay")
        updateRequire(json.getString("packageName") == packageName)
        val code = json.getLong("versionCode")
        val version = json.getString("version")
        val size = json.getLong("size")
        val digest = json.getString("sha256")
        updateRequire(code > 0 && version.isNotBlank() && version.length <= 128)
        updateRequire(size in 1..MAX_APK_SIZE && hash.matches(digest))
        val minSdk = if (json.isNull("minSdkVersion")) null else json.getInt("minSdkVersion").also { updateRequire(it > 0) }
        val notes = if (json.isNull("releaseNotes")) "" else json.getString("releaseNotes")
        updateRequire(notes.toByteArray(Charsets.UTF_8).size <= 16 * 1024)
        val parts = json.getJSONArray("parts")
        val files = when (json.getString("distribution")) {
            "direct" -> {
                updateRequire(parts.length() == 0)
                listOf(file(json.getJSONObject("file"), base, code)).also {
                    updateRequire(it.single().size == size && it.single().sha256.equals(digest, true))
                }
            }
            "chunked" -> {
                updateRequire(json.isNull("file") && parts.length() in 1..64)
                val chunkSize = json.getLong("chunkSize")
                updateRequire(chunkSize in 1..10_000_000)
                List(parts.length()) { index ->
                    val part = parts.getJSONObject(index)
                    updateRequire(part.getInt("index") == index)
                    file(part, base, code).also { updateRequire(it.size <= chunkSize && (index == parts.length() - 1 || it.size == chunkSize)) }
                }.also { updateRequire(it.sumOf { part -> part.size } == size) }
            }
            else -> throw GalaxyUpdateException(GalaxyUpdateFailure.MANIFEST)
        }
        GalaxyUpdateRelease(packageName, version, code, minSdk, notes, size, digest.lowercase(), files)
    } catch (error: GalaxyUpdateException) { throw error }
    catch (_: Exception) { throw GalaxyUpdateException(GalaxyUpdateFailure.MANIFEST) }

    private fun file(json: JSONObject, base: URI, code: Long): GalaxyUpdateFile {
        val size = json.getLong("size")
        val digest = json.getString("sha256")
        val uri = base.resolve(json.getString("url")).normalize()
        updateRequire(size in 1..MAX_APK_SIZE && hash.matches(digest))
        updateRequire(allowed(uri, base) && uri.path.startsWith("/downloads/com-ecarx-carplay/v$code/"))
        return GalaxyUpdateFile(uri, size, digest.lowercase())
    }

    // 不将商店响应中的下载地址或重定向扩展到其他主机、凭据或本地协议。
    fun allowed(uri: URI, origin: URI): Boolean = uri.scheme in listOf("http", "https") &&
        uri.host == origin.host && uri.userInfo == null && uri.fragment == null &&
        (uri.port == origin.port || origin.port == -1 && uri.port == -1) &&
        !(origin.scheme == "https" && uri.scheme != "https")
}

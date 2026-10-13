package com.shilapi.xcertplay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** 安装来源授权可能终止进程，仅保存已校验更新，恢复后仍由用户手动安装。 */
internal object GalaxyUpdateCache {
    data class Pending(val file: File, val release: GalaxyUpdateRelease)
    private fun directory(context: Context) = File(context.cacheDir, "app-updates")
    fun save(context: Context, file: File, release: GalaxyUpdateRelease) {
        val root = directory(context)
        updateRequire(file.canonicalFile.parentFile == root.canonicalFile, GalaxyUpdateFailure.STORAGE)
        val metadata = JSONObject().put("file", file.name).put("release", manifest(release)).toString().toByteArray(Charsets.UTF_8)
        val temporary = File(root, "pending.tmp")
        try {
            FileOutputStream(temporary).use { it.write(metadata); it.fd.sync() }
            updateRequire(temporary.renameTo(File(root, "pending.json")), GalaxyUpdateFailure.STORAGE)
        } catch (_: Exception) { throw GalaxyUpdateException(GalaxyUpdateFailure.STORAGE) }
        finally { temporary.delete() }
    }

    @Suppress("DEPRECATION")
    fun load(context: Context): Pending? {
        val root = directory(context)
        val record = File(root, "pending.json")
        var ownedFile: File? = null
        val pending = if (!record.exists()) null else try {
            updateRequire(record.length() in 1..GalaxyUpdateManifest.MAX_METADATA_SIZE.toLong())
            val json = JSONObject(record.readText())
            val name = json.getString("file")
            updateRequire(Regex("download-[a-zA-Z0-9-]+\\.apk").matches(name))
            val file = File(root, name)
            updateRequire(file.canonicalFile.parentFile == root.canonicalFile && file.isFile)
            updateRequire(file.canonicalFile.name == file.name)
            ownedFile = file
            updateRequire(System.currentTimeMillis() - record.lastModified() < GalaxyUpdateFiles.RETENTION_MILLIS)
            val release = GalaxyUpdateManifest.parse(json.getJSONObject("release").toString(), GalaxyUpdateManifest.endpoint, context.packageName)
            if (!release.newerThan(context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode)) {
                clear(context); GalaxyUpdateFiles.delete(root, file); null
            } else { updateRequire(file.length() == release.size); Pending(file, release) }
        } catch (_: Exception) {
            clear(context)
            ownedFile?.let { GalaxyUpdateFiles.delete(root, it) }
            null
        }
        GalaxyUpdateFiles.cleanup(root, pending?.file)
        return pending
    }

    fun clear(context: Context) { File(directory(context), "pending.json").delete() }

    private fun manifest(value: GalaxyUpdateRelease): JSONObject {
        fun part(file: GalaxyUpdateFile) = JSONObject().put("url", file.uri.toString()).put("size", file.size).put("sha256", file.sha256)
        val direct = value.files.size == 1
        val files = JSONArray()
        if (!direct) value.files.forEachIndexed { index, file -> files.put(part(file).put("index", index)) }
        return JSONObject().put("schemaVersion", 1).put("appId", "com-ecarx-carplay").put("packageName", value.packageName)
            .put("version", value.version).put("versionCode", value.code).put("minSdkVersion", value.minSdk ?: JSONObject.NULL)
            .put("releaseNotes", value.notes).put("size", value.size).put("sha256", value.sha256)
            .put("distribution", if (direct) "direct" else "chunked").put("parts", files)
            .put("file", if (direct) part(value.files.single()) else JSONObject.NULL)
            .put("chunkSize", if (direct) JSONObject.NULL else value.files.first().size)
    }
}

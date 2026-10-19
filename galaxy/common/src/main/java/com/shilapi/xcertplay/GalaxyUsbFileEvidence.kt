package com.shilapi.xcertplay

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

/** 只读固定系统位置；不打开设备节点、不执行 shell、不输出文件原文。 */
internal class GalaxyUsbFileEvidence {
    data class Read(val state: String, val text: String = "", val truncated: Boolean = false)

    fun stat(path: String): Map<String, String?> = try {
        val s = Os.stat(path)
        mapOf("usbVisibility" to "PRESENT", "usbMode" to s.st_mode.toString(8),
            "usbOwner" to "${s.st_uid}:${s.st_gid}",
            "usbNodeType" to when {
                OsConstants.S_ISCHR(s.st_mode) -> "CHARACTER"
                OsConstants.S_ISDIR(s.st_mode) -> "DIRECTORY"
                OsConstants.S_ISREG(s.st_mode) -> "REGULAR"
                else -> "OTHER"
            }, "usbAccessRead" to access(path, OsConstants.R_OK), "usbAccessWrite" to access(path, OsConstants.W_OK),
            "usbEffectiveCall" to "STAT_ACCESS_ONLY_NOT_OPENED")
    } catch (e: Exception) { mapOf("usbVisibility" to failure(e)) }

    private fun access(path: String, mode: Int): String = try { Os.access(path, mode).toString() }
        catch (e: Exception) { failure(e) }

    fun read(path: String, limit: Int = 4096, gzip: Boolean = false): Read = try {
        FileInputStream(path).use { raw ->
            (if (gzip) GZIPInputStream(raw) else raw).use { input ->
                val bytes = ByteArray(limit + 1)
                var size = 0
                while (size < bytes.size) {
                    val n = input.read(bytes, size, bytes.size - size)
                    if (n < 0) break
                    if (n == 0) break
                    size += n
                }
                Read("READ", String(bytes, 0, minOf(size, limit), Charsets.UTF_8), size > limit)
            }
        }
    } catch (e: Exception) { Read(failure(e)) }

    fun list(path: String, cap: Int = 12): Pair<String, List<String>> {
        val facts = stat(path)
        if (facts["usbVisibility"] != "PRESENT") return facts.getValue("usbVisibility").orEmpty() to emptyList()
        return try {
            val names = File(path).list() ?: return "ENUMERATION_UNAVAILABLE" to emptyList()
            (if (names.size > cap) "LIST_TRUNCATED" else "LISTED") to names.sorted().take(cap)
        } catch (e: Exception) { failure(e) to emptyList() }
    }

    fun tokenFile(path: String, allowed: Set<String>): Map<String, String?> {
        val read = read(path)
        val words = read.text.trim().split(Regex("[\\s,\\[\\]]+")).filter { it.isNotBlank() }
        return mapOf("usbVisibility" to read.state, "usbObserved" to words.filter { it in allowed }.distinct().joinToString(","),
            "usbOtherCount" to words.count { it !in allowed }.toString(), "usbTruncated" to read.truncated.toString())
    }

    companion object {
        fun failure(error: Throwable): String {
            val errno = generateSequence(error) { it.cause }.filterIsInstance<ErrnoException>().firstOrNull()?.errno
            return when {
                generateSequence(error) { it.cause }.any { it is SecurityException } || errno == OsConstants.EACCES || errno == OsConstants.EPERM -> "DENIED"
                errno == OsConstants.ENOENT || errno == OsConstants.ENOTDIR -> "NOT_VISIBLE_AT_PATH"
                else -> "UNAVAILABLE_${error.javaClass.simpleName}"
            }
        }
        fun markers(read: Read, needles: Map<String, String>): Map<String, String?> = mapOf(
            "usbVisibility" to read.state, "usbTruncated" to read.truncated.toString(),
            "usbObserved" to needles.filterValues { read.text.contains(it, ignoreCase = true) }.keys.joinToString(","),
            "usbEffectiveCall" to "READ_MARKERS_ONLY")
    }
}

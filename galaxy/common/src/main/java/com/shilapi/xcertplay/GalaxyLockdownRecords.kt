package com.shilapi.xcertplay

import android.content.SharedPreferences
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.LockdownPairingException
import java.security.MessageDigest
import java.util.WeakHashMap

/** 手机记录沿用原私有配置文件；索引与配对材料均不进入日志或配置导出。 */
internal class GalaxyLockdownRecords(private val prefs: SharedPreferences) {
    fun load(deviceKey: ByteArray?): LockdownPairRecord? = synchronized(lock) {
        val key = deviceKey?.copyOf()
        if (key != null && key.isEmpty()) return null
        val prefix = key?.let(::prefix)
        val failed = failedWrites[prefs].orEmpty()
        if (prefix != null) {
            if (prefix in failed) return null
            if (fields.any { prefs.contains(prefix + it) }) {
                return read(prefix)?.takeIf { MessageDigest.isEqual(it.devicePublicKeyPem, key) }
            }
        }
        if ("" in failed) return null
        val legacy = read("") ?: return null
        if (key == null) return legacy
        if (!MessageDigest.isEqual(legacy.devicePublicKeyPem, key)) return null
        // 先确认新记录持久化成功，再清理旧记录；不能只靠 SharedPreferences 的内存视图。
        save(legacy)
        val cleanup = prefs.edit()
        fields.forEach(cleanup::remove)
        runCatching { cleanup.commit() }
        legacy
    }

    fun save(record: LockdownPairRecord) = synchronized(lock) {
        val prefix = prefix(record.devicePublicKeyPem)
        val values = listOf(record.hostId, record.systemBuid, record.wifiMacAddress,
            record.devicePublicKeyPem.hex(), record.deviceCertificatePem.hex(), record.hostPrivateKeyPem.hex(),
            record.hostCertificatePem.hex(), record.rootPrivateKeyPem.hex(), record.rootCertificatePem.hex())
        val edit = prefs.edit()
        fields.zip(values).forEach { (field, value) -> edit.putString(prefix + field, value) }
        commit(edit, setOf(prefix))
    }

    fun clear(deviceKey: ByteArray?) = synchronized(lock) {
        val key = deviceKey?.copyOf()
        val edit = prefs.edit()
        val targets = mutableSetOf<String>()
        if (key == null) {
            val keys = prefs.all.keys.filter { it.startsWith(PREFIX) || it in fields }
            keys.forEach { name ->
                edit.remove(name)
                targets.add(if (name.startsWith(PREFIX)) name.substringBeforeLast('.') + "." else "")
            }
            targets.addAll(failedWrites[prefs].orEmpty())
        } else {
            val prefix = prefix(key)
            fields.forEach { edit.remove(prefix + it) }
            targets.add(prefix)
            if (read("")?.devicePublicKeyPem?.let { MessageDigest.isEqual(it, key) } == true) {
                fields.forEach(edit::remove)
                targets.add("")
            }
        }
        commit(edit, targets)
    }

    private fun commit(edit: SharedPreferences.Editor, targets: Set<String>) {
        val failed = failedWrites.getOrPut(prefs) { mutableSetOf() }
        val stored = try { edit.commit() } catch (_: Exception) { false }
        if (!stored) {
            // commit 失败也可能已更新内存，阻止下一次连接把未确认落盘的材料当作成功记录。
            failed.addAll(targets)
            throw LockdownPairingException.PersistenceFailed
        }
        failed.removeAll(targets)
    }

    private fun read(prefix: String): LockdownPairRecord? = try {
        val values = fields.map { prefs.getString(prefix + it, null) ?: return null }
        LockdownPairRecord.restore(values[0], values[1], values[2], values[3].bytes(), values[4].bytes(),
            values[5].bytes(), values[6].bytes(), values[7].bytes(), values[8].bytes())
    } catch (_: Exception) { null }

    private fun prefix(key: ByteArray): String {
        require(key.isNotEmpty()) { "Phone key is required" }
        return PREFIX + MessageDigest.getInstance("SHA-256").digest(key).hex() + "."
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun String.bytes(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private companion object {
        const val PREFIX = "lockdown.phone."
        val lock = Any()
        val failedWrites = WeakHashMap<SharedPreferences, MutableSet<String>>()
        val fields = listOf("lockdown_host_id", "lockdown_system_buid", "lockdown_wifi_mac", "lockdown_device_public",
            "lockdown_device_cert", "lockdown_host_private", "lockdown_host_cert", "lockdown_root_private", "lockdown_root_cert")
    }
}

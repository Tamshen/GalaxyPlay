package com.shilapi.xcertplay

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/** 排序与副本索引留在临时 SQLite，日志数量增加时不扩大内存集合。正文入库前仍须脱敏。 */
internal class RemoteLogArchiveIndex(file: File) : Closeable {
    private val database = SQLiteDatabase.openOrCreateDatabase(file, null)
    private val digest = MessageDigest.getInstance("SHA-256")
    private val insertEntry: SQLiteStatement
    private val insertConfiguration: SQLiteStatement
    var scanned = 0
        private set
    var duplicateCopies = 0
        private set
    var excluded = 0
        private set
    var truncated = 0
        private set
    var retained = 0
        private set

    init {
        database.execSQL("PRAGMA cache_size=-1024")
        database.execSQL("PRAGMA temp_store=FILE")
        database.execSQL("CREATE TABLE entries (position INTEGER PRIMARY KEY, source TEXT, message TEXT, config TEXT, time INTEGER, counter INTEGER, identity TEXT UNIQUE)")
        database.execSQL("CREATE TABLE configurations (id TEXT PRIMARY KEY, body TEXT)")
        insertEntry = database.compileStatement("INSERT OR IGNORE INTO entries(position,source,message,config,time,counter,identity) VALUES(?,?,?,?,?,?,?)")
        insertConfiguration = database.compileStatement("INSERT OR IGNORE INTO configurations(id,body) VALUES(?,?)")
        database.beginTransaction()
    }

    fun append(source: String, raw: String) {
        val position = scanned++
        if (raw.isBlank()) return
        if (raw.startsWith(GalaxyConfigurationEvidence.PREFIX)) {
            runCatching { GalaxyConfigurationEvidence.read(raw.removePrefix(GalaxyConfigurationEvidence.PREFIX)) }
                .onSuccess { evidence ->
                    insertConfiguration.clearBindings()
                    insertConfiguration.bindString(1, evidence.id)
                    insertConfiguration.bindString(2, evidence.text)
                    insertConfiguration.executeInsert()
                }
            return
        }
        val bounded = if (raw.length > DiagnosticRedactor.MAX_LINE)
            "oversized_log_line omittedChars=${raw.length} [truncated]" else raw
        val safe = DiagnosticRedactor.redact(bounded) ?: run { excluded++; return }
        if (safe.endsWith(" [truncated]")) truncated++
        val event = DiagnosticLogIdentity.read(raw)
        val identity = event?.let { it.id + ":" + hex(digest.digest(raw.toByteArray(Charsets.UTF_8))) }
        // 单个事务中复用预编译语句；正文与事件身份的副本判定保持不变。
        insertEntry.clearBindings()
        insertEntry.bindLong(1, position.toLong())
        insertEntry.bindString(2, source)
        insertEntry.bindString(3, safe)
        REFERENCE.find(safe)?.groupValues?.get(1)?.let { insertEntry.bindString(4, it) }
        insertEntry.bindLong(5, event?.millis ?: 0L)
        insertEntry.bindLong(6, event?.sequence ?: position.toLong())
        identity?.let { insertEntry.bindString(7, it) }
        if (insertEntry.executeInsert() == -1L)
            duplicateCopies++ else retained++
    }

    fun finish() {
        // 先批量写入再建立排序索引，避免每行都维护第二棵索引树。
        database.execSQL("CREATE INDEX chronology ON entries(time,counter,position)")
        database.setTransactionSuccessful()
        database.endTransaction()
    }

    fun configurations(checkpoint: () -> Unit): Sequence<RemoteLogEntry> = sequence {
        database.rawQuery("SELECT id,body FROM configurations WHERE id IN (SELECT config FROM entries) ORDER BY id", null).use { cursor ->
            while (cursor.moveToNext()) {
                checkpoint()
                yield(RemoteLogEntry(scanned++, "configuration", "configuration_snapshot", cursor.getString(0),
                    GalaxyConfigurationEvidence.read(cursor.getString(1)), "at_event"))
            }
        }
    }

    fun entries(checkpoint: () -> Unit): Sequence<RemoteLogEntry> = sequence {
        database.rawQuery("SELECT position,source,message,config FROM entries ORDER BY time,counter,position", null).use { cursor ->
            while (cursor.moveToNext()) {
                checkpoint()
                yield(RemoteLogEntry(cursor.getInt(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)))
            }
        }
    }

    override fun close() {
        try { if (database.inTransaction()) database.endTransaction() }
        finally {
            try { insertEntry.close() }
            finally { try { insertConfiguration.close() } finally { database.close() } }
        }
    }

    private fun hex(bytes: ByteArray): String = String(CharArray(bytes.size * 2).apply {
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 255
            this[index * 2] = HEX[value ushr 4]
            this[index * 2 + 1] = HEX[value and 15]
        }
    })

    private companion object {
        const val HEX = "0123456789abcdef"
        val REFERENCE = Regex("(?:^| )config_ref=(cfg_[g-v]{64})(?: |$)")
    }
}

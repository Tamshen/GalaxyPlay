package com.shilapi.xcertplay

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/** 排序与副本索引留在临时 SQLite，日志数量增加时不扩大内存集合。正文入库前仍须脱敏。 */
internal class RemoteLogArchiveIndex(file: File) : Closeable {
    private val database = SQLiteDatabase.openOrCreateDatabase(file, null)
    private val digest = MessageDigest.getInstance("SHA-256")
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
        database.execSQL("CREATE INDEX chronology ON entries(time,counter,position)")
        database.execSQL("CREATE TABLE configurations (id TEXT PRIMARY KEY, body TEXT)")
        database.beginTransaction()
    }

    fun append(source: String, raw: String) {
        val position = scanned++
        if (raw.isBlank()) return
        if (raw.startsWith(GalaxyConfigurationEvidence.PREFIX)) {
            runCatching { GalaxyConfigurationEvidence.read(raw.removePrefix(GalaxyConfigurationEvidence.PREFIX)) }
                .onSuccess { evidence -> database.insertWithOnConflict("configurations", null, ContentValues().apply {
                    put("id", evidence.id); put("body", evidence.text)
                }, SQLiteDatabase.CONFLICT_IGNORE) }
            return
        }
        val bounded = if (raw.length > DiagnosticRedactor.MAX_LINE)
            "oversized_log_line omittedChars=${raw.length} [truncated]" else raw
        val safe = DiagnosticRedactor.redact(bounded) ?: run { excluded++; return }
        if (safe.endsWith(" [truncated]")) truncated++
        val event = DiagnosticLogIdentity.read(raw)
        val identity = event?.let { it.id + ":" + digest.digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) } }
        val row = ContentValues().apply {
            put("position", position); put("source", source); put("message", safe)
            put("config", REFERENCE.find(safe)?.groupValues?.get(1))
            put("time", event?.millis ?: 0L); put("counter", event?.sequence ?: position.toLong())
            put("identity", identity)
        }
        if (database.insertWithOnConflict("entries", null, row, SQLiteDatabase.CONFLICT_IGNORE) == -1L)
            duplicateCopies++ else retained++
    }

    fun finish() { database.setTransactionSuccessful(); database.endTransaction() }

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
        if (database.inTransaction()) database.endTransaction()
        database.close()
    }

    private companion object { val REFERENCE = Regex("(?:^| )config_ref=(cfg_[g-v]{64})(?: |$)") }
}

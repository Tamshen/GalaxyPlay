package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import org.json.JSONArray
import org.json.JSONObject

/** 完整上传快照只存于私有临时目录；固定长度磁盘索引让进度和发送都无需加载全部批次。 */
internal class RemoteLogBatchStore(val directory: File, private val metadata: RemoteLogMetadata,
                                  private val protection: Closeable? = null) : Closeable {
    private val content = File(directory, "batches.bin")
    private val index = File(directory, "batches.idx")
    private var count = 0
    var lineCount = 0
        private set

    fun write(entries: List<RemoteLogEntry>) {
        val bytes = JSONArray(entries.map { entry -> JSONObject()
            .put("index", entry.index).put("source", entry.source).put("message", entry.message)
            .put("config_id", entry.configurationId).put("configuration", entry.configuration?.text)
            .put("role", entry.configurationRole) }).toString().toByteArray(Charsets.UTF_8)
        RandomAccessFile(content, "rw").use { data ->
            val offset = data.length()
            data.seek(offset); data.write(bytes)
            RandomAccessFile(index, "rw").use { offsets ->
                offsets.seek(offsets.length())
                offsets.writeLong(offset); offsets.writeInt(bytes.size); offsets.writeInt(entries.size)
            }
        }
        count++; lineCount += entries.size
    }

    val batches: List<RemoteLogBatch> = object : AbstractList<RemoteLogBatch>() {
        override val size get() = count
        override fun get(index: Int): RemoteLogBatch {
            require(index in 0 until count)
            return RandomAccessFile(this@RemoteLogBatchStore.index, "r").use { offsets ->
                offsets.seek(index * 16L)
                val offset = offsets.readLong(); val length = offsets.readInt(); val lines = offsets.readInt()
                RemoteLogBatch(metadata, index + 1, count, emptyList(), { read(offset, length) }, lines)
            }
        }
    }

    private fun read(offset: Long, length: Int): List<RemoteLogEntry> {
        val bytes = ByteArray(length)
        RandomAccessFile(content, "r").use { it.seek(offset); it.readFully(bytes) }
        val entries = JSONArray(bytes.toString(Charsets.UTF_8))
        return (0 until entries.length()).map { position ->
            val row = entries.getJSONObject(position)
            fun value(key: String) = if (row.isNull(key)) null else row.getString(key)
            RemoteLogEntry(row.getInt("index"), row.getString("source"), row.getString("message"),
                value("config_id"), value("configuration")?.let(GalaxyConfigurationEvidence::read), value("role"))
        }
    }

    override fun close() { try { directory.deleteRecursively() } finally { protection?.close() } }
}

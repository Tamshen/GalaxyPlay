package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import org.json.JSONArray

/** 完整上传快照只存于私有临时目录；固定长度磁盘索引让进度和发送都无需加载全部批次。 */
internal class RemoteLogBatchStore(val directory: File, private val metadata: RemoteLogMetadata,
                                  private val protection: Closeable? = null) : Closeable {
    private val content = File(directory, "batches.bin")
    private val index = File(directory, "batches.idx")
    private var count = 0
    var lineCount = 0
        private set

    /** 保存已经计量的事件 JSON；总批次数确定后只填两项批次字段，不重建配置和事件。 */
    fun write(events: List<String>) {
        val bytes = events.joinToString(",", "[", "]").toByteArray(Charsets.UTF_8)
        check(bytes.size <= RemoteLogReport.MAX_BODY)
        RandomAccessFile(content, "rw").use { data ->
            val offset = data.length()
            data.seek(offset); data.write(bytes)
            RandomAccessFile(index, "rw").use { offsets ->
                offsets.seek(offsets.length())
                offsets.writeLong(offset); offsets.writeInt(bytes.size); offsets.writeInt(events.size)
            }
        }
        count++; lineCount += events.size
    }

    val batches: List<RemoteLogBatch> = object : AbstractList<RemoteLogBatch>() {
        override val size get() = count
        override fun get(index: Int): RemoteLogBatch {
            require(index in 0 until count)
            return RandomAccessFile(this@RemoteLogBatchStore.index, "r").use { offsets ->
                offsets.seek(index * 16L)
                val offset = offsets.readLong(); val length = offsets.readInt(); val lines = offsets.readInt()
                RemoteLogBatch(metadata, index + 1, count, emptyList(), { entries(offset, length) }, lines,
                    { body(offset, length, index + 1) })
            }
        }
    }

    private fun read(offset: Long, length: Int): JSONArray {
        val bytes = ByteArray(length)
        RandomAccessFile(content, "r").use { it.seek(offset); it.readFully(bytes) }
        return JSONArray(bytes.toString(Charsets.UTF_8))
    }

    private fun entries(offset: Long, length: Int): List<RemoteLogEntry> {
        val entries = read(offset, length)
        return (0 until entries.length()).map { position ->
            val row = entries.getJSONObject(position)
            fun value(key: String) = if (row.isNull(key)) null else row.getString(key)
            RemoteLogEntry(row.getInt("line_index"), row.getString("source"), row.getString("message"),
                value("config_id")?.takeUnless { it == "unknown" },
                row.optJSONObject("configuration_file")?.toString()?.let(GalaxyConfigurationEvidence::read),
                value("configuration_role"))
        }
    }

    private fun body(offset: Long, length: Int, batch: Int): ByteArray {
        val entries = read(offset, length)
        for (position in 0 until entries.length()) {
            val row = entries.getJSONObject(position)
            // 快照配置回读仍按白名单重新校验；序列化优化不能绕过自由文本遮盖。
            row.optJSONObject("configuration_file")?.let { configuration ->
                val safe = GalaxyConfigurationEvidence.read(configuration.toString())
                row.put("configuration_file", org.json.JSONObject(safe.text))
                    .put("configuration_file_name", "${safe.id}.json")
            }
            row.put("batch_index", batch).put("batch_count", count)
        }
        return entries.toString().toByteArray(Charsets.UTF_8)
    }

    override fun close() { try { directory.deleteRecursively() } finally { protection?.close() } }
}

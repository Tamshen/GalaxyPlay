package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import java.text.DateFormat
import java.util.Date

/** 只保存服务端确认完整写入的回执；失败、取消及修改配置不覆盖成功历史。 */
internal object RemoteLogHistory {
    data class Entry(val time: Long, val lines: Int, val id: String = "", val remark: String = "", val stream: String = "")
    private fun preferences(context: Context) = context.getSharedPreferences("l7_log_history", Context.MODE_PRIVATE)

    fun save(context: Context, entry: Entry): Boolean {
        require(entry.time > 0 && entry.lines > 0)
        return preferences(context).edit().putLong("successful_at", entry.time).putInt("line_count", entry.lines)
            .putString("report_id", entry.id).putString("upload_remark", entry.remark).putString("stream", entry.stream).commit()
    }

    fun last(context: Context): Entry? {
        val saved = preferences(context).let {
            Entry(it.getLong("successful_at", 0), it.getInt("line_count", 0), it.getString("report_id", "").orEmpty(),
                it.getString("upload_remark", "").orEmpty(), it.getString("stream", "").orEmpty())
                .takeIf { value -> value.time > 0 && value.lines > 0 }
        }
        val current = RemoteLogUpload.status
        // 本地保存失败时，当前进程仍展示已被服务器确认的真实结果。
        return if (current.phase == RemoteLogUpload.Phase.SUCCESS && current.finishedAt > (saved?.time ?: 0) && current.totalLines > 0)
            Entry(current.finishedAt, current.totalLines, current.id, current.remark, current.stream) else saved
    }

    fun summary(context: Context): String {
        val entry = last(context) ?: return context.getString(R.string.l7_log_never_uploaded)
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM,
            context.resources.configuration.locales[0]).format(Date(entry.time))
        return context.getString(R.string.l7_log_last_upload, time, entry.lines) +
            (if (entry.stream.isEmpty()) "" else "\n" + context.getString(R.string.l7_log_receipt_stream, entry.stream)) +
            identification(context, entry.id, entry.remark)
    }

    fun identification(context: Context, id: String, remark: String): String =
        (if (id.isEmpty()) "" else "\n" + context.getString(R.string.l7_log_report_hash, id)) +
            (if (remark.isEmpty()) "" else "\n" + context.getString(R.string.l7_log_remark_value, remark))
}

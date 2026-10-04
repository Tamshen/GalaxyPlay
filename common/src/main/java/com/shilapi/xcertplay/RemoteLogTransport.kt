package com.shilapi.xcertplay

import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 单次请求，不跟随重定向，不自动重试，也不将响应或令牌写入本地日志。 */
internal class RemoteLogTransport : Closeable {
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var closed = false

    fun send(config: RemoteLogConfig, report: RemoteLogReport): Int {
        require(config.valid())
        val request = URL(config.endpoint).openConnection() as HttpURLConnection
        synchronized(this) {
            if (closed) throw IOException("cancelled")
            connection = request
        }
        try {
            request.connectTimeout = 5000
            request.readTimeout = 8000
            request.instanceFollowRedirects = false
            request.requestMethod = "POST"
            request.doOutput = true
            request.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            request.setRequestProperty("Authorization", config.authorization)
            request.setFixedLengthStreamingMode(report.body.size)
            request.outputStream.use { it.write(report.body) }
            val status = request.responseCode
            if (status !in 200..299) return status
            val bytes = request.inputStream.use { input ->
                val buffer = ByteArray(4097)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                buffer.copyOf(count)
            }
            if (bytes.size > 4096) throw IOException("invalid acknowledgement")
            val acknowledgement = JSONObject(bytes.toString(Charsets.UTF_8))
            val streams = acknowledgement.optJSONArray("status")
            val result = streams?.takeIf { it.length() == 1 }?.optJSONObject(0)
            if (acknowledgement.optInt("code") != 200 || result == null || result.optString("name") != config.stream ||
                result.optInt("successful", -1) < 0 || result.optInt("failed", -1) < 0) {
                throw IOException("invalid acknowledgement")
            }
            // HTTP 200 也可能部分拒收；只有该流全部写入才显示成功。
            if (result.getInt("successful") != report.lineCount || result.getInt("failed") != 0) return -1
            return status
        } finally {
            request.disconnect()
            synchronized(this) { if (connection === request) connection = null }
        }
    }

    override fun close() = synchronized(this) {
        closed = true
        connection?.disconnect()
        connection = null
    }
}

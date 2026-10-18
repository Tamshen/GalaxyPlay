package com.shilapi.xcertplay

import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** 一次手动上传的身份；失败重试复用，重新上传才生成新编号。 */
internal data class RemoteLogUploadRequest(val id: String, val remark: String, val created: String) {
    fun boundary(begin: Boolean, records: Int? = null): String =
        "UPLOAD_${if (begin) "BEGIN" else "END"} report_id=$id upload_remark=${JSONObject.quote(remark)}" +
            (records?.let { " records=$it" } ?: "")

    companion object {
        const val MAX_REMARK = 200

        fun create(remark: String = ""): RemoteLogUploadRequest {
            val nonce = UUID.randomUUID().toString()
            val id = MessageDigest.getInstance("SHA-256").digest(nonce.toByteArray(Charsets.UTF_8))
                .take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
            val singleLine = remark.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().take(MAX_REMARK)
            val safe = DiagnosticRedactor.redact(singleLine).orEmpty().take(MAX_REMARK)
            return RemoteLogUploadRequest(id, safe, Instant.now().toString())
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.mfi

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

/** 将文字认证材料校验后作为一对文件替换；失败时保留上一套可用材料。 */
object LocalMfiIdentityStore {
    const val MAX_TEXT_LENGTH = 32 * 1024
    private const val MAX_BYTES = 16 * 1024

    class Identity internal constructor(
        internal val key: ByteArray,
        internal val certificate: ByteArray,
    ) : Closeable {
        override fun close() { key.fill(0); certificate.fill(0) }
    }

    fun prepareText(privateKey: String, certificate: String): Identity {
        val key = decode(privateKey, setOf("PRIVATE KEY"))
        return try {
            prepareBytes(key, decode(certificate, setOf("PKCS7", "CERTIFICATE")))
        } finally {
            key.fill(0)
        }
    }

    fun prepareBytes(privateKey: ByteArray, certificate: ByteArray): Identity {
        LocalMfiAuthenticationClient.fromEncoded(privateKey, certificate)
        return Identity(privateKey.copyOf(), certificate.copyOf())
    }

    @Synchronized fun read(directory: File): Identity {
        recover(directory)
        LocalMfiAuthenticationClient.load(directory)
        val key = File(directory, "identity.pk8").readBytes()
        return try {
            prepareBytes(key, File(directory, "certificate.p7b").readBytes())
        } finally {
            key.fill(0)
        }
    }

    @Synchronized fun install(directory: File, identity: Identity) {
        LocalMfiAuthenticationClient.fromEncoded(identity.key, identity.certificate)
        recover(directory)
        val staging = File(directory.parentFile, "${directory.name}-importing")
        val previous = File(directory.parentFile, "${directory.name}-previous")
        check(staging.deleteRecursively() && staging.mkdir())
        restrict(staging, executable = true)
        try {
            write(File(staging, "identity.pk8"), identity.key)
            write(File(staging, "certificate.p7b"), identity.certificate)
            LocalMfiAuthenticationClient.load(staging)
            if (directory.exists()) check(directory.renameTo(previous))
            if (!staging.renameTo(directory)) {
                if (previous.exists()) check(previous.renameTo(directory))
                error("Could not replace local authentication")
            }
            // 两个文件同时随目录切换；残留备份会在下次读取时清理。
            previous.deleteRecursively()
        } finally {
            staging.deleteRecursively()
        }
    }

    @Synchronized fun recover(directory: File) {
        val previous = File(directory.parentFile, "${directory.name}-previous")
        if (!previous.exists()) return
        if (runCatching { LocalMfiAuthenticationClient.load(directory) }.isSuccess) {
            previous.deleteRecursively()
        } else {
            // 先确认备份有效，再恢复中断的目录切换。
            LocalMfiAuthenticationClient.load(previous)
            check(directory.deleteRecursively() && previous.renameTo(directory))
        }
    }

    private fun decode(text: String, labels: Set<String>): ByteArray {
        require(text.length <= MAX_TEXT_LENGTH) { "认证文字过长" }
        val trimmed = text.trim()
        val content = if (trimmed.startsWith("-----BEGIN ")) {
            val match = Regex("-----BEGIN ([A-Z0-9 ]+)-----\\s*([A-Za-z0-9+/=\\s]+?)\\s*-----END \\1-----")
                .matchEntire(trimmed)
            require(match != null && match.groupValues[1] in labels) { "PEM 类型或格式不正确" }
            match.groupValues[2]
        } else trimmed
        val base64 = content.filterNot(Char::isWhitespace)
        require(base64.isNotEmpty() && base64.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "+/=" }) {
            "请粘贴 PEM 或 Base64 文本"
        }
        return Base64.getDecoder().decode(base64).also {
            require(it.isNotEmpty() && it.size <= MAX_BYTES) { "认证文件过大或为空" }
        }
    }

    private fun write(file: File, bytes: ByteArray) {
        check(file.createNewFile())
        restrict(file)
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }

    private fun restrict(file: File, executable: Boolean = false) {
        check(file.setReadable(false, false) && file.setReadable(true, true))
        check(file.setWritable(false, false) && file.setWritable(true, true))
        if (executable) check(file.setExecutable(false, false) && file.setExecutable(true, true))
    }
}

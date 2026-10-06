package com.shilapi.xcertplay.transport

internal data class UsbMuxFrame(
    val protocol: Int,
    val length: Int,
    val word8: Int,
    val sequence: Int,
    val payload: ByteArray,
)

/** 增量组帧；调用方用 USBMUX 状态锁串行访问。 */
internal class UsbMuxFrameBuffer(private val diagnostic: (String) -> Unit = {}) {
    private var bytes = ByteArray(0)
    private var optionalReplyPadding: UsbMuxFrame? = null
    private var paddingReports = 0
    private var lastUsbReadBytes = 0
    val bufferedBytes: Int get() = bytes.size

    fun append(transfer: ByteArray) {
        lastUsbReadBytes = transfer.size
        bytes += transfer
    }

    fun takeFrame(): UsbMuxFrame? {
        if (bytes.size < HEADER_BYTES) return null
        var length = readU32(bytes, 4)
        if (length !in HEADER_BYTES..MAX_FRAME_BYTES) {
            val previous = optionalReplyPadding
            // 已捕获的 iOS 27 VERSION 和合法 RX TCP 回复，包括带载荷的回复，
            // 具有四字节尾部；不能扫描或丢弃整个 USB completion：
            // 只允许这一处四字节边界，后续必须是校验过的 TCP 或已捕获的
            // protocol-1 诊断形态。
            // 始于零偏移的正常或分片帧头始终保留。
            // 等待候选 MUX／TCP 帧头或完整诊断载荷；超时保持原有字节，
            // 不能丢失合法分片帧。
            if (previous != null && readU32(bytes, 0) != PROTOCOL_TCP &&
                bytes.size < PADDING_BYTES + HEADER_BYTES) return null
            val followingProtocol = readU32(bytes, PADDING_BYTES)
            val followingLength = readU32(bytes, PADDING_BYTES + 4)
            val candidateHeader = previous != null && readU32(bytes, 0) != PROTOCOL_TCP &&
                readU32(bytes, PADDING_BYTES + 8) == CAPTURED_REPLY_MAGIC
            val validTarget = when {
                candidateHeader && followingProtocol == PROTOCOL_TCP &&
                    followingLength in (HEADER_BYTES + TCP_HEADER_BYTES)..MAX_FRAME_BYTES -> {
                    if (bytes.size < PADDING_BYTES + HEADER_BYTES + TCP_HEADER_BYTES) return null
                    val tcpHeaderBytes = ((bytes[PADDING_BYTES + HEADER_BYTES + 12].toInt() ushr 4) and 0x0f) * 4
                    tcpHeaderBytes in TCP_HEADER_BYTES..(followingLength - HEADER_BYTES) &&
                        readU16(bytes, PADDING_BYTES + HEADER_BYTES) != 0 &&
                        readU16(bytes, PADDING_BYTES + HEADER_BYTES + 2) != 0
                }
                candidateHeader && followingProtocol == PROTOCOL_DIAGNOSTIC &&
                    followingLength in (HEADER_BYTES + 2)..(HEADER_BYTES + MAX_DIAGNOSTIC_PAYLOAD_BYTES) -> {
                    // Issue #100 同时捕获了 protocol 1 前后的四字节尾部。
                    // 恢复前校验完整且有界的子类型／文本载荷。
                    // 超时不修改尾部和不完整帧。
                    if (bytes.size < PADDING_BYTES + followingLength) return null
                    isCapturedDiagnosticPayload(bytes, PADDING_BYTES + HEADER_BYTES, followingLength - HEADER_BYTES)
                }
                else -> false
            }
            if (previous != null && validTarget) {
                bytes = bytes.copyOfRange(PADDING_BYTES, bytes.size)
                optionalReplyPadding = null
                if (paddingReports++ < MAX_PADDING_REPORTS) report(
                    "USBMUX optional reply padding skipped bytes=$PADDING_BYTES " +
                        "previousProtocol=${previous.protocol} previousLength=${previous.length} " +
                        "nextProtocol=$followingProtocol nextLength=$followingLength " +
                        "lastUsbReadBytes=$lastUsbReadBytes bufferedBytes=${bytes.size}")
                if (bytes.size < HEADER_BYTES) return null
                length = readU32(bytes, 4)
            } else {
                report("USBMUX framing rejected declaredLength=$length bufferedBytes=${bytes.size} " +
                    "lastUsbReadBytes=$lastUsbReadBytes optionalReplyPadding=${previous != null}")
                throw IphoneUsbException.Protocol("Invalid USBMUX frame length $length")
            }
        }
        if (bytes.size < length) return null
        val frame = UsbMuxFrame(
            protocol = readU32(bytes, 0), length = length, word8 = readU32(bytes, 8),
            sequence = ((bytes[12].toInt() and 0xff) shl 8) or (bytes[13].toInt() and 0xff),
            payload = bytes.copyOfRange(HEADER_BYTES, length),
        )
        bytes = bytes.copyOfRange(length, bytes.size)
        optionalReplyPadding = frame.takeIf(::canHaveOptionalReplyPadding)
        return frame
    }

    private fun canHaveOptionalReplyPadding(frame: UsbMuxFrame): Boolean {
        if (frame.protocol == PROTOCOL_VERSION && frame.length == VERSION_BYTES && frame.word8 == 2) return true
        if (frame.protocol == PROTOCOL_DIAGNOSTIC && frame.word8 == CAPTURED_REPLY_MAGIC) {
            return isCapturedDiagnosticPayload(frame.payload, 0, frame.payload.size)
        }
        if (frame.protocol != PROTOCOL_TCP || frame.word8 != CAPTURED_REPLY_MAGIC ||
            frame.payload.size < TCP_HEADER_BYTES) return false
        val tcpHeaderBytes = ((frame.payload[12].toInt() ushr 4) and 0x0f) * 4
        return tcpHeaderBytes in TCP_HEADER_BYTES..frame.payload.size &&
            readU16(frame.payload, 0) != 0 && readU16(frame.payload, 2) != 0
    }

    /** 只接受已捕获的诊断子类型及有界可打印 ASCII。 */
    private fun isCapturedDiagnosticPayload(source: ByteArray, offset: Int, length: Int): Boolean =
        length in 2..MAX_DIAGNOSTIC_PAYLOAD_BYTES && source[offset].toInt() == DIAGNOSTIC_TEXT_SUBTYPE &&
            (offset + 1 until offset + length).all { source[it].toInt() in 0x20..0x7e }

    private fun report(line: String) { runCatching { diagnostic(line) } }

    private fun readU16(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private fun readU32(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private companion object {
        const val HEADER_BYTES = 16
        const val TCP_HEADER_BYTES = 20
        const val MAX_FRAME_BYTES = 65_536
        const val VERSION_BYTES = 20
        const val PADDING_BYTES = 4
        const val MAX_PADDING_REPORTS = 4
        const val PROTOCOL_VERSION = 0
        const val PROTOCOL_DIAGNOSTIC = 1
        const val PROTOCOL_TCP = 6
        const val DIAGNOSTIC_TEXT_SUBTYPE = 4
        const val MAX_DIAGNOSTIC_PAYLOAD_BYTES = 1_024
        val CAPTURED_REPLY_MAGIC = 0xfaceface.toInt()
    }
}

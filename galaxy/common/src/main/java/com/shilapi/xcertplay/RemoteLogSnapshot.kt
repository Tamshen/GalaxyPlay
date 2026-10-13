package com.shilapi.xcertplay

/** 先保留有界近期原文，再做脱敏与 JSON；淘汰的历史记录不参与昂贵的整理。 */
internal class RemoteLogSnapshot {
    data class Line(val index: Int, val source: String, val text: String)
    private val window = RemoteLogWindow<Line>(RemoteLogReport.MAX_LINES, MAX_CHARACTERS)
    val lines get() = ArrayDeque(window.ordered().map { it.value })
    val duplicateCopies get() = window.duplicateCopies
    val headers = linkedSetOf<String>()
    var count = 0
        private set
    var omitted = 0
        private set

    fun append(source: String, text: String) {
        val index = count++
        if (text.isBlank()) return
        if (text.startsWith(GalaxyConfigurationEvidence.PREFIX)) {
            val header = text.removePrefix(GalaxyConfigurationEvidence.PREFIX)
            if (header.length <= GalaxyConfigurationEvidence.MAX_BYTES) {
                headers += header
                if (headers.size > MAX_HEADERS) headers.remove(headers.first())
            }
            return
        }
        // 普通落盘行已有长度限制。异常超长行只保留长度，避免无界正则或裁剪后漏识别原始载荷。
        val bounded = if (text.length > DiagnosticRedactor.MAX_LINE)
            "oversized_log_line omittedChars=${text.length} [truncated]" else text
        omitted += window.add(index, source, bounded, Line(index, source, bounded), bounded.length)
    }

    companion object {
        const val MAX_HEADERS = 64
        const val MAX_CHARACTERS = 2 * RemoteLogReport.MAX_REPORT_BODY
    }
}

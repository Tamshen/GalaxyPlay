package com.shilapi.xcertplay

/** 只遮盖敏感值；保留技术字段、状态和计数，原始协议载荷仍不进入诊断。 */
internal object DiagnosticRedactor {
    const val MAX_LINE = 4096
    private val field = Regex("""(?i)(?<![\w-])["']?(password|passphrase|pass|token|access[_-]?token|refresh[_-]?token|private[_-]?key|secret|certificate|pair[_-]?record|ssid|authorization|cookie|android[_-]?id|serial(?:number)?|imei|imsi|iccid|vin|fingerprint|phone[_-]?number|(?:phone|device|peer|host|bluetooth)?[_-]?name|phone|device|peer|device[_-]?id|body|payload|hex)["']?\s*[:=]\s*""")
    private val nextField = Regex("""[,;\s]+(?=["']?[A-Za-z_][\w.\-/\[\]]*["']?\s*[:=])""")
    private val rawDump = Regex("""(?:^|\s)(?:TRACE|PHONE)\s|-----\s*(?:BEGIN|END)\b|^[A-Za-z0-9+/]{48,}={0,2}$""")
    private val mac = Regex("(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])")
    private val identifier = Regex("(?i)\\b[0-9a-f]{24,}\\b|\\b[0-9a-f]{8}-[0-9a-f-]{27,}\\b")
    private val address = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")
    private val ipv6 = Regex("(?i)(?:[0-9a-f]{1,4}:)*[0-9a-f]{0,4}::[0-9a-f:]*(?:%[a-z0-9_.-]+)?|(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}")
    private val url = Regex("""(?i)https?://[^\s<>"']+""")
    private val path = Regex("""(?:/Users/|/storage/|/data/).*?(?=\s+[A-Za-z_][\w.-]*\s*[:=]|["'<>]|$)""")
    private val bearer = Regex("""(?i)\b(?:Bearer|Basic)\s+[A-Za-z0-9+/_.=-]+""")
    private val versionField = Regex("""(?i)(?:[\w.]*version|firmware|build)\s*[:=]\s*["']?$""")

    fun redact(line: String): String? {
        if (rawDump.containsMatchIn(line) || line.any { it.code < 32 && it != '\t' || it.code == 127 }) return null
        var safe = fields(line).replace(url, "[url]").replace(path, "[path]")
            .replace(bearer, "[authorization]").replace(mac, "[address]").replace(identifier, "[identifier]")
        val beforeAddresses = safe
        safe = address.replace(safe) { match ->
            // 四段版本号不是网络地址，版本上下文中的数字完整保留。
            if (versionField.containsMatchIn(beforeAddresses.substring(0, match.range.first))) match.value else "[ip]"
        }.replace(ipv6, "[ip]")
        return if (safe.length <= MAX_LINE) safe else safe.take(MAX_LINE - 12) + " [truncated]"
    }

    private fun fields(line: String): String = buildString {
        var cursor = 0
        while (cursor < line.length) {
            val match = field.find(line, cursor) ?: break
            val start = match.range.last + 1
            val key = match.groupValues[1].lowercase().replace("_", "").replace("-", "")
            val end = if (key == "cookie" && line.getOrNull(start) !in listOf('"', '\'')) line.length else valueEnd(line, start)
            val value = line.substring(start, end)
            append(line, cursor, start)
            append(if (technicalValue(key, value)) value else "[redacted]")
            cursor = end
        }
        append(line, cursor, line.length)
    }

    private fun technicalValue(key: String, value: String): Boolean {
        val plain = value.trim().trim('"', '\'')
        if (plain in setOf("[redacted]", "[ip]", "[address]", "[identifier]", "[url]", "[path]", "[authorization]")) return true
        if (key in setOf("device", "peer") && (mac.matches(plain) || address.matches(plain) || ipv6.matches(plain))) return true
        return when (key) {
            "name", "device", "peer", "deviceid" ->
                plain.matches(Regex("(?i)(?:c2\\.|OMX\\.)[\\w.-]+|BUS[0-9]{2}_[A-Z0-9_]+|[0-9]{1,6}"))
            else -> false
        }
    }

    private fun valueEnd(line: String, start: Int): Int {
        if (start >= line.length) return line.length
        val first = line[start]
        if (first !in "\"'[{(") return nextField.find(line, start)?.range?.first ?: line.length
        var quote: Char? = null
        var escaped = false
        var depth = 0
        for (i in start until line.length) {
            val char = line[i]
            if (escaped) { escaped = false; continue }
            if (char == '\\') { escaped = true; continue }
            if (quote != null) {
                if (char == quote) {
                    quote = null
                    if (depth == 0) return i + 1
                }
            } else when (char) {
                '\'', '"' -> quote = char
                '[', '{', '(' -> depth++
                ']', '}', ')' -> if (--depth == 0) return i + 1
            }
        }
        // 引号或结构不完整时遮蔽余下内容，不把残缺载荷当作普通字段。
        return line.length
    }
}

package com.shilapi.xcertplay

/** 搜索按普通文本匹配，不执行正则；复制基于完整快照，不受列表可见行影响。 */
internal class L7LogViewModel {
    var lines: List<String> = emptyList()
        private set
    var query = ""
        private set
    var matches: List<String> = emptyList()
        private set

    fun replace(value: List<String>) { lines = value.toList(); search(query) }
    fun search(value: String) {
        query = value.trim()
        matches = if (query.isEmpty()) lines else lines.filter { it.contains(query, ignoreCase = true) }
    }
    fun copyParts(filtered: Boolean): List<String> {
        val text = (if (filtered) matches else lines).joinToString("\n")
        val parts = mutableListOf<String>()
        var start = 0
        // 避免超过 Android 剪贴板的 Binder 容量；分段明确展示，不能静默丢弃后半段。
        while (start < text.length) {
            var end = (start + 180_000).coerceAtMost(text.length)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            parts += text.substring(start, end)
            start = end
        }
        return parts
    }
}

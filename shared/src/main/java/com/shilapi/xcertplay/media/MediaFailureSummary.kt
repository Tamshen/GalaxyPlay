package com.shilapi.xcertplay.media

/** 导出有界异常元数据；异常原文可能包含私有输入，不予记录。 */
internal object MediaFailureSummary {
    fun describe(error: Throwable): String {
        val failures = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (failures.size < 3) {
            val next = current ?: break
            if (failures.any { it === next }) break
            failures += next
            current = next.cause
        }
        val types = failures.joinToString("/") { identifier(it.javaClass.simpleName, 64) }
        // 仅保留一个媒体代码位置，不导出完整堆栈、源文件名或任意调用方。
        val frame = failures.asSequence().flatMap { it.stackTrace.asSequence() }.firstOrNull {
            it.className.startsWith("android.media.") ||
                it.className.startsWith("com.shilapi.xcertplay.media.")
        }
        val location = frame?.let {
            "${identifier(it.className, 128)}.${identifier(it.methodName, 64)}:${it.lineNumber}"
        } ?: "unavailable"
        return "error=${identifier(error.javaClass.simpleName, 64)} causes=$types at=$location"
    }

    private fun identifier(value: String, limit: Int): String =
        value.take(limit).filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '$' }
            .ifEmpty { "unknown" }
}

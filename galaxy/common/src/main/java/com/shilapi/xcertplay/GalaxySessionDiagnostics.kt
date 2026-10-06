package com.shilapi.xcertplay

import android.content.Context

/** 媒体线程只捕获会话日志和观察编号，不持有已经退出的 Activity。 */
internal object GalaxySessionDiagnostics {
    fun audio(context: Context, file: SessionLogFile?, generation: Int, product: Boolean): (String) -> Unit {
        L7VoiceDiagnostics.initialize(context.applicationContext)
        val owner = L7VoiceDiagnostics.store.session(generation)
        val scoped = scoped(file, generation, product)
        return { message ->
            runCatching { L7VoiceDiagnostics.store.observe(owner, message) }
            scoped(message)
        }
    }

    /** 迟到释放回调捕获旧文件与代次，不向新的手机会话改写记录。 */
    fun scoped(file: SessionLogFile?, generation: Int, product: Boolean): (String) -> Unit = { message ->
        val tagged = "g=$generation $message"
        if (product) runCatching { L7DebugLog.record(tagged) }
        runCatching { AsyncDiagnosticLog.append(file, tagged) }
    }
}

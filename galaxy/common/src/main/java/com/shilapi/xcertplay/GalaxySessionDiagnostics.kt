package com.shilapi.xcertplay

import android.content.Context

/** 媒体线程只捕获会话日志和观察编号，不持有已经退出的 Activity。 */
internal object GalaxySessionDiagnostics {
    fun audio(context: Context, file: SessionLogFile?, generation: Int, product: Boolean): (String) -> Unit {
        L7VoiceDiagnostics.initialize(context.applicationContext)
        val owner = L7VoiceDiagnostics.store.session()
        return { message ->
            L7VoiceDiagnostics.store.observe(owner, message)
            val tagged = "g=$generation $message"
            if (product) L7DebugLog.record(tagged)
            AsyncDiagnosticLog.append(file, tagged)
        }
    }
}

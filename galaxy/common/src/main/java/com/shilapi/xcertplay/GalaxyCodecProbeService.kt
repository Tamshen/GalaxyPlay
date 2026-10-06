package com.shilapi.xcertplay

import android.app.Service
import android.content.Intent
import android.os.*
import android.os.Process
import android.view.Surface
import com.shilapi.xcertplay.media.GalaxyNativeCodecProbe

/** 仅本应用绑定的可回收进程；不写日志文件，不创建正式 CarPlay 会话。 */
class GalaxyCodecProbeService : Service() {
    @Volatile private var working = false
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.sendingUid != Process.myUid()) return
            val reply = message.replyTo ?: return
            if (message.what == CodecProbeProtocol.CONNECT) {
                send(reply, CodecProbeProtocol.HELLO, Bundle().apply { putInt("pid", Process.myPid()); putInt("uid", Process.myUid()) })
            } else if (message.what == CodecProbeProtocol.RUN && !working) {
                working = true
                val data = message.data
                data.classLoader = Surface::class.java.classLoader
                @Suppress("DEPRECATION") val surface = data.getParcelable<Surface>("surface") ?: return
                Thread({ perform(reply, data, surface) }, "codec-probe-worker").start()
            }
        }
    })
    override fun onBind(intent: Intent) = messenger.binder

    private fun perform(reply: Messenger, data: Bundle, surface: Surface) {
        val started = SystemClock.elapsedRealtime()
        val run = data.getLong("run")
        val method = CodecProbeMethod.entries[data.getInt("method")]
        val video = CodecProbeVideo.entries[data.getInt("video")]
        var phase = CodecProbeStage.SAMPLE
        fun progress(stage: CodecProbeStage) {
            phase = stage
            send(reply, CodecProbeProtocol.STAGE, Bundle().apply { putLong("run", run); putInt("stage", stage.ordinal) })
        }
        var result: CodecProbeResult
        try {
            progress(CodecProbeStage.SAMPLE)
            val sample = GalaxyCodecProbeSample.read(this, video)
            result = if (method == CodecProbeMethod.NDK) native(run, video, sample, data.getString("name")!!, surface, ::progress)
                else GalaxyJavaCodecProbe(run, method, sample, data.getString("name")!!, surface, data.getBoolean("software"), ::progress).decode()
        } catch (failure: Throwable) {
            // 仅保存类型；厂商异常原文可能含路径、属性或载荷，不跨进程传播。
            result = CodecProbeResult(run, method, phase, reason = failure.javaClass.simpleName)
        } finally { surface.release() }
        working = false
        send(reply, CodecProbeProtocol.RESULT, result.copy(elapsedMs = SystemClock.elapsedRealtime() - started, video = video).bundle())
    }

    private fun native(run: Long, video: CodecProbeVideo, sample: GalaxyCodecProbeSample, name: String,
        surface: Surface, progress: (CodecProbeStage) -> Unit): CodecProbeResult {
        var phase = CodecProbeStage.CREATE
        var actual = ""
        val stats = GalaxyNativeCodecProbe.decode(name, video.mime, surface, sample.csd, sample.packets, sample.times,
            object : GalaxyNativeCodecProbe.Listener {
                override fun stage(value: Int) { phase = CodecProbeStage.entries[value]; progress(phase) }
                override fun decoder(name: String) { actual = name.take(96) }
            })
        val decoder = CodecProbeDecoder.list(video).firstOrNull { it.name == actual }
        return CodecProbeResult(run, CodecProbeMethod.NDK, if (stats[0] == 0L) CodecProbeStage.DONE else CodecProbeStage.entries[stats[5].toInt().coerceIn(0, 8)],
            actual, decoder?.hardware == true, decoder?.software == true, stats[1].toInt(), stats[2].toInt(),
            -1, stats[3] == 1L, stats[4] == 1L, stats[0].toInt(), if (stats[0] == 0L) "" else "NDK_STATUS")
    }
    private fun send(reply: Messenger, what: Int, data: Bundle) {
        try { reply.send(Message.obtain(null, what).apply { this.data = data }) } catch (_: RemoteException) { }
    }
}

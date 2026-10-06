package com.shilapi.xcertplay

import android.app.ActivityManager
import android.content.*
import android.os.*
import android.os.Process
import android.view.Surface
import java.io.Closeable

/** 父进程看门狗独立于 codec worker；仅结束经私有 Binder 确认的本应用测试进程。 */
internal interface CodecProbeAttempt { val run: Long; fun start(); fun stop(reason: String) }

internal class GalaxyCodecProbeClient(private val context: Context, override val run: Long,
    private val method: CodecProbeMethod, private val video: CodecProbeVideo,
    private val name: String, private val software: Boolean, private val surface: Surface,
    private val onStage: (CodecProbeStage) -> Unit, private val onResult: (CodecProbeResult) -> Unit) : Closeable, CodecProbeAttempt {
    private val handler = Handler(Looper.getMainLooper())
    private val started = SystemClock.elapsedRealtime()
    private var stage = CodecProbeStage.BIND
    private var pid = 0
    private var workerStarted = false
    private var bound = false
    private var finished = false
    private var stopping = false
    private var pending: CodecProbeResult? = null
    private var remote: Messenger? = null
    private var binder: IBinder? = null
    private val death = IBinder.DeathRecipient { handler.post {
        complete(pending ?: failure("PROCESS_DIED"), true)
    } }
    private val timeout = Runnable { stop("TIMEOUT") }
    private val total = Runnable { stop("TIMEOUT") }
    private val receiver: Messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) { receive(message) }
    })
    private fun receive(message: Message) {
        if (finished || message.sendingUid != Process.myUid()) return
        when (message.what) {
            CodecProbeProtocol.HELLO -> {
                if (stopping || pid != 0 || message.data.getInt("uid") != Process.myUid()) return
                val next = message.data.getInt("pid")
                if (!ownChild(next)) { stop("INVALID_PROCESS"); return }
                pid = next
                workerStarted = true
                try { remote?.send(Message.obtain(null, CodecProbeProtocol.RUN).apply {
                    replyTo = receiver
                    data = Bundle().apply {
                        putLong("run", run); putInt("method", method.ordinal); putInt("video", video.ordinal)
                        putString("name", name); putBoolean("software", software); putParcelable("surface", surface)
                    }
                }) } catch (_: RemoteException) { stop("PROCESS_DIED") }
            }
            CodecProbeProtocol.STAGE -> if (!stopping && message.data.getLong("run") == run) {
                stage = CodecProbeStage.entries.getOrNull(message.data.getInt("stage")) ?: run { stop("INVALID_STAGE"); return }
                onStage(stage); arm()
            }
            CodecProbeProtocol.RESULT -> if (!stopping && message.data.getLong("run") == run) {
                val result = CodecProbeResult.read(message.data)
                if (result.method != method) return
                if (result.released) {
                    // 正常释放后保留系统缓存的空闲进程，避免连续冷启动和驱动重复载入。
                    runCatching { context.stopService(Intent(context, GalaxyCodecProbeService::class.java)) }
                    complete(result)
                } else {
                    pending = result
                    terminate()
                }
            }
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(component: ComponentName, service: IBinder) {
            if (finished || stopping) return
            binder = service
            try {
                service.linkToDeath(death, 0)
                remote = Messenger(service)
                remote!!.send(Message.obtain(null, CodecProbeProtocol.CONNECT).apply { replyTo = receiver })
            } catch (_: RemoteException) { stop("PROCESS_DIED") }
        }
        override fun onServiceDisconnected(component: ComponentName) {
            if (!finished && !stopping) stop("PROCESS_DIED")
        }
        override fun onBindingDied(component: ComponentName) { stop("BIND_FAILED") }
        override fun onNullBinding(component: ComponentName) { stop("BIND_FAILED") }
    }
    override fun start() {
        arm(); handler.postDelayed(total, 15_000)
        try {
            bound = context.bindService(Intent(context, GalaxyCodecProbeService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound) complete(failure("BIND_FAILED"))
        } catch (_: Exception) { complete(failure("BIND_FAILED")) }
    }
    private fun arm() { handler.removeCallbacks(timeout); handler.postDelayed(timeout, 8_000) }
    override fun stop(reason: String) {
        if (finished || stopping) return
        pending = failure(reason)
        terminate()
    }
    private fun failure(reason: String) = CodecProbeResult(run, method, stage, reason = reason,
        inputs = if (workerStarted) -1 else 0, outputs = if (workerStarted) -1 else 0,
        elapsedMs = SystemClock.elapsedRealtime() - started, video = video)
    private fun ownChild(candidate: Int) = runCatching { candidate > 0 && candidate != Process.myPid() &&
        context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty().any {
            it.pid == candidate && it.uid == Process.myUid() && it.processName == "${context.packageName}:codec_probe"
        } }.getOrDefault(false)
    private fun terminate() {
        stopping = true
        if (bound) { runCatching { context.unbindService(connection) }; bound = false }
        if (pid != 0 && ownChild(pid)) {
            runCatching { Process.killProcess(pid) }
            // Binder death 是资源所有者退出的证据；迟到结果不能覆盖取消判断。
            handler.postDelayed({
                if (!finished) complete(pending ?: failure("PROCESS_DIED"), !ownChild(pid))
            }, 500)
        } else {
            runCatching { context.stopService(Intent(context, GalaxyCodecProbeService::class.java)) }
            complete(pending ?: failure("PROCESS_DIED"), pid != 0 && !ownChild(pid))
        }
        handler.removeCallbacks(timeout); handler.removeCallbacks(total)
    }
    private fun complete(result: CodecProbeResult, exited: Boolean = false) {
        if (finished) return
        finished = true
        if (bound) { runCatching { context.unbindService(connection) }; bound = false }
        runCatching { binder?.unlinkToDeath(death, 0) }
        handler.removeCallbacks(timeout); handler.removeCallbacks(total)
        onResult(result.copy(processExited = exited, workerStarted = workerStarted))
    }
    override fun close() { stop("CANCELLED") }
}

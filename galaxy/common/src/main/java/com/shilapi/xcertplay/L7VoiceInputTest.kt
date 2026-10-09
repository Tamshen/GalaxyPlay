package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.SystemClock
import com.shilapi.xcertplay.media.MicrophoneSignalStats
import java.io.Closeable
import java.util.concurrent.Executors

/** 前台显式启动的有界电平测试；只保存摘要，采集、停止与释放都在同一 worker。 */
internal class L7VoiceInputTest(
    private val access: Access,
    private val log: (String) -> Unit = L7VoiceDiagnostics.store::record,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : Closeable {
    interface Recorder : Closeable {
        fun start()
        fun read(buffer: ByteArray): Int
        val routeType: Int?
        val silenced: Boolean
        val recordingState: Int? get() = null
    }
    interface Access {
        fun permitted(): Boolean
        fun occupied(): Boolean
        fun create(source: Int): Recorder
    }
    enum class Phase { IDLE, STARTING, CAPTURING, STOPPING, STOPPED, COMPLETE, INTERRUPTED, FAILED }
    data class Snapshot(val phase: Phase = Phase.IDLE, val elapsedMs: Long = 0, val bytes: Long = 0,
        val rms: Int = 0, val peak: Int = 0, val zeroPercent: Int = 0, val routeType: Int? = null,
        val silenced: Boolean = false, val reason: String = "", val code: Int? = null,
        val reads: Long = 0, val zeroReads: Long = 0, val maxReadMs: Long = 0,
        val recordingState: Int? = null) {
        val busy get() = phase in setOf(Phase.STARTING, Phase.CAPTURING, Phase.STOPPING)
    }
    @Volatile var snapshot = Snapshot()
        private set
    @Volatile private var stopRequested = false
    @Volatile private var closed = false
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "l7-voice-test") }

    @Synchronized fun start(source: Int): Boolean {
        if (closed || snapshot.busy) return false
        if (!access.permitted()) { snapshot = Snapshot(Phase.FAILED, reason = "PERMISSION"); return false }
        if (access.occupied()) { snapshot = Snapshot(Phase.FAILED, reason = "UPLINK_ACTIVE"); return false }
        stopRequested = false
        snapshot = Snapshot(Phase.STARTING)
        worker.execute { capture(source) }
        return true
    }

    @Synchronized fun stop() {
        if (!snapshot.busy) return
        stopRequested = true
        snapshot = snapshot.copy(phase = Phase.STOPPING)
    }

    private fun capture(source: Int) {
        val started = now()
        val run = started
        var recorder: Recorder? = null
        var finalPhase = Phase.STOPPED
        var reason = "USER_OR_BACKGROUND"
        var code: Int? = null
        var total = 0L
        var reads = 0L
        var zeroReads = 0L
        var maxReadMs = 0L
        var lastUpdate = started
        var lastLog = started
        val signal = MicrophoneSignalStats()
        val buffer = ByteArray(640)
        fun emit(phase: String) = runCatching { log("VOICE_TEST run=$run phase=$phase source=$source rate=16000 channels=1 " +
            "elapsedMs=${now() - started} bytes=$total rms=${signal.rms} peak=${signal.peak} " +
            "zeroPercent=${signal.zeroPercent} routeType=${runCatching { recorder?.routeType }.getOrNull() ?: snapshot.routeType ?: "unknown"} " +
            "silenced=${runCatching { recorder?.silenced }.getOrNull() ?: snapshot.silenced} " +
            "reads=$reads zeroReads=$zeroReads maxReadMs=$maxReadMs readMode=NON_BLOCKING bufferBytes=${buffer.size} " +
            "recordingState=${runCatching { recorder?.recordingState }.getOrNull() ?: snapshot.recordingState ?: "unknown"} " +
            "reason=$reason code=${code ?: "none"}") }
        try {
            emit("START")
            if (stopRequested || closed) return
            if (access.occupied()) { finalPhase = Phase.INTERRUPTED; reason = "UPLINK_ACTIVE"; return }
            recorder = access.create(source)
            if (stopRequested || closed) return
            if (access.occupied()) { finalPhase = Phase.INTERRUPTED; reason = "UPLINK_ACTIVE"; return }
            recorder.start()
            while (!stopRequested && !closed) {
                if (!access.permitted()) { finalPhase = Phase.FAILED; reason = "PERMISSION_REVOKED"; break }
                if (access.occupied()) { finalPhase = Phase.INTERRUPTED; reason = "UPLINK_ACTIVE"; break }
                if (now() - started >= MAX_MILLIS) {
                    finalPhase = if (total == 0L) Phase.FAILED else Phase.COMPLETE
                    reason = if (total == 0L) "NO_DATA" else "TIME_LIMIT"
                    break
                }
                val readStarted = now()
                val count = recorder.read(buffer)
                reads++
                maxReadMs = maxOf(maxReadMs, (now() - readStarted).coerceAtLeast(0))
                if (count == 0) zeroReads++
                if (count < 0) { finalPhase = Phase.FAILED; reason = "READ"; code = count; break }
                if (count > 0) { signal.add(buffer, count); total += count }
                if (now() - lastUpdate >= 250) {
                    snapshot = Snapshot(Phase.CAPTURING, now() - started, total, signal.rms, signal.peak,
                        signal.zeroPercent, recorder.routeType, recorder.silenced,
                        reads = reads, zeroReads = zeroReads, maxReadMs = maxReadMs,
                        recordingState = recorder.recordingState)
                    lastUpdate = now()
                    if (now() - lastLog >= 1000) { emit("LEVEL"); lastLog = now() }
                    signal.resetWindow()
                }
                Thread.sleep(10)
            }
        } catch (error: Exception) {
            if (!stopRequested && !closed) { finalPhase = Phase.FAILED; reason = error.javaClass.simpleName.take(64) }
        } finally {
            val route = runCatching { recorder?.routeType }.getOrNull()
            val silenced = runCatching { recorder?.silenced }.getOrNull() ?: false
            val recordingState = runCatching { recorder?.recordingState }.getOrNull()
            runCatching { recorder?.close() }
            recorder = null
            // 先释放录音器再允许下一轮，快速点击不能让两个采集实例重叠。
            snapshot = snapshot.copy(phase = finalPhase, elapsedMs = now() - started, bytes = total,
                routeType = route, silenced = silenced, reason = reason, code = code,
                reads = reads, zeroReads = zeroReads, maxReadMs = maxReadMs, recordingState = recordingState)
            emit(finalPhase.name)
            buffer.fill(0)
        }
    }

    @Synchronized override fun close() { closed = true; stop(); worker.shutdown() }

    class SystemAccess(context: Context) : Access {
        private val app = context.applicationContext
        override fun permitted() = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        override fun occupied() = CarPlayBackgroundSession.snapshot()?.sink?.hasMicrophoneUplink() == true
        override fun create(source: Int): Recorder {
            val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "MIN_BUFFER" }
            val record = AudioRecord.Builder().setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum, 1280)).build()
            if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); error("INITIALIZATION") }
            return object : Recorder {
                override fun start() { record.startRecording(); check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) }
                override fun read(buffer: ByteArray) = record.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                override val routeType get() = record.routedDevice?.type
                override val silenced get() = record.activeRecordingConfiguration?.isClientSilenced ?: false
                override val recordingState get() = record.recordingState
                override fun close() { runCatching { record.stop() }; record.release() }
            }
        }
    }
    companion object { const val MAX_MILLIS = 10_000L }
}

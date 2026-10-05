package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.toHexString
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Each audio stream keeps its own track and usage so
 * media and navigation guidance stay independently routable. Call [close]
 * when the session tears down.
 */
class AndroidMediaSink(
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    private val audioFocusEnabled: Boolean = false,
    private val mediaChannel: Int = 0,
    private val navigationChannel: Int = 0,
    context: Context? = null,
    private val navigationStreamType: Int = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val mediaBufferMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
    private val onAudioDiagnostic: (String) -> Unit = {},
    onMediaAudioChanged: (Boolean) -> Unit = {},
    onVideoSizeChanged: ((Int, Int) -> Unit)? = null,
    enableL7AudioRouting: Boolean = false,
    onVideoFailure: ((VideoCodec, String) -> Unit)? = null,
    private val assistantChannel: Int = 0,
    private val callProcessingEnabled: Boolean = true,
    enableL7AudioProfile: Boolean = false,
    private val wirelessAudio: Boolean = false,
    private val audioRoutingTemplate: AudioRoutingTemplate? = null,
) : MediaSink {
    @Volatile private var mediaAudioChanged = onMediaAudioChanged

    /** 回调绑定控制器归属，旧渲染线程不得改变新会话的媒体按键状态。 */
    fun setMediaAudioChangedListener(listener: (Boolean) -> Unit) { mediaAudioChanged = listener }

    fun resumeMediaAudioFocus() = audioFocusCoordinator.resumeMedia()
    @Volatile private var videoFailure = onVideoFailure
    @Volatile private var mainVideoFailure: Pair<VideoCodec, String>? = null
    @Volatile private var videoSizeChanged = onVideoSizeChanged
    @Volatile private var mainVideoSize = videoWidth to videoHeight
    private val appContext = context?.applicationContext
    private val factoryAudio = if (enableL7AudioProfile) L7FactoryAudioProfile.load() else null
    private val audioFocusCoordinator = AudioFocusCoordinator(
        appContext,
        audioFocusEnabled,
        onAudioDiagnostic,
        factoryRouting = factoryAudio != null,
    )
    private val callMode = TelephonyAudioMode(appContext?.getSystemService(AudioManager::class.java), onAudioDiagnostic)
    private val audioRouting = if (appContext != null)
        L7AudioRouting(appContext, audioRoutingTemplate?.preferBus ?: enableL7AudioRouting, audioRoutingTemplate, onAudioDiagnostic) else null
    private val screenStateLock = Any()
    private val videoLifecycleLock = Any()
    @Volatile private var closed = false
    private val retiringVideoDecoders = mutableSetOf<VideoDecoder>()
    private val activeScreenTypes = mutableSetOf<Int>()
    private var defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val mediaAudioTypes = mutableSetOf<AudioStreamId>()
    private val assistantAudioTypes = mutableSetOf<AudioStreamId>()
    private val assistantMicrophoneTypes = mutableSetOf<AudioStreamId>()
    /** 按协议的语音流状态判断，不将电话录音或媒体存在视为 Siri 活动。 */
    @Synchronized fun isAssistantAudioActive(): Boolean =
        !closed && (assistantAudioTypes.isNotEmpty() || assistantMicrophoneTypes.isNotEmpty())
    /** 本地调试录音避让已存在的电话或 Siri 上行。 */
    fun hasMicrophoneUplink(): Boolean = !closed && microphoneUplinks.isNotEmpty()
    private val audioRenderers = ConcurrentHashMap<AudioStreamId, AudioRenderer>()
    private val microphoneUplinks = ConcurrentHashMap<AudioStreamId, MicrophoneUplink>()
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val videoDiagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    private val recoveryPending = AtomicBoolean(false)
    private val recoveryExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "carplay-video-recovery").apply { isDaemon = true }
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        videoRecoveryHandlers[type] = handler
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        videoDiagnosticHandlers[type] = handler
    }

    private fun requestVideoRecovery(type: Int) {
        if (!recoveryPending.compareAndSet(false, true)) return
        try {
            recoveryExecutor.execute {
                try { if (!closed) videoRecoveryHandlers[type]?.invoke() }
                catch (error: Exception) { Log.w("xcertplay-usb", "Video keyframe request failed", error) }
                finally { recoveryPending.set(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { recoveryPending.set(false) }
    }

    fun setSurface(type: Int, surface: Surface) {
        synchronized(videoLifecycleLock) {
            if (closed) return
            surfaces[type] = surface
            videoDecoders[type]?.setSurface(surface)
        }
    }

    fun clearSurface(type: Int, surface: Surface) {
        synchronized(videoLifecycleLock) {
            if (surfaces.remove(type, surface)) videoDecoders[type]?.setSurface(null)
        }
    }

    /** 先阻止新 decoder 绑定，再等所有仍可能持有目标的 worker 解除，UI 无需阻塞。 */
    fun detachSurface(surface: Surface, onDetached: () -> Unit) {
        val decoders = synchronized(videoLifecycleLock) {
            surfaces.entries.removeIf { it.value === surface }
            if (defaultSurface === surface) defaultSurface = null
            (videoDecoders.values + retiringVideoDecoders).distinct().also { targets ->
                if (targets.isNotEmpty()) {
                    val remaining = AtomicInteger(targets.size)
                    targets.forEach { decoder ->
                        decoder.detachSurface(surface) {
                            if (remaining.decrementAndGet() == 0) onDetached()
                        }
                    }
                }
            }
        }
        if (decoders.isEmpty()) onDetached()
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        synchronized(screenStateLock) {
            screenStreamActiveChanged = listener
            activeScreenTypes.forEach { listener?.invoke(it, true) }
        }
    }

    fun setVideoSizeChangedListener(listener: ((Int, Int) -> Unit)?) {
        videoSizeChanged = listener
        val (width, height) = mainVideoSize
        listener?.invoke(width, height)
    }

    fun setVideoFailureListener(listener: ((VideoCodec, String) -> Unit)?) {
        videoFailure = listener
        mainVideoFailure?.let { listener?.invoke(it.first, it.second) }
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        pendingVideoCodec[type] = codec
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        val codec = pendingVideoCodec[type] ?: VideoCodec.H264
        videoDecoder(type)?.configure(codec, codecData)
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        videoDecoder(type)?.submit(naluBytes)
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (!active) {
            videoRecoveryHandlers.remove(type)
            videoDiagnosticHandlers.remove(type)
            synchronized(videoLifecycleLock) {
                videoDecoders.remove(type)?.let { decoder ->
                    retiringVideoDecoders.add(decoder)
                    decoder.close()
                }
            }
            pendingVideoCodec.remove(type)
        }
        synchronized(screenStateLock) {
            if (active) activeScreenTypes.add(type) else activeScreenTypes.remove(type)
            screenStreamActiveChanged?.invoke(type, active)
        }
    }

    @Synchronized
    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        if (closed) return
        audioRenderer(id, format)?.start()
        if (format.audioType == "media") updateMediaAudio(id, true)
        if (format.audioType.equals("speechrecognition", ignoreCase = true)) assistantAudioTypes.add(id)
    }

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        audioRenderer(id, format)?.submit(rtp, sample)
    }

    @Synchronized
    override fun onAudioStopped(id: AudioStreamId) {
        audioRenderers.remove(id)?.close()
        assistantAudioTypes.remove(id)
        updateMediaAudio(id, false)
    }

    private fun updateMediaAudio(id: AudioStreamId, active: Boolean) {
        val (before, after) = synchronized(mediaAudioTypes) {
            val before = mediaAudioTypes.isNotEmpty()
            if (active) mediaAudioTypes.add(id) else mediaAudioTypes.remove(id)
            before to mediaAudioTypes.isNotEmpty()
        }
        if (before != after) mediaAudioChanged(after)
    }

    @Synchronized
    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        if (closed) return
        try {
            if (callProcessingEnabled && config.audioType.equals("telephony", ignoreCase = true)) callMode.acquire(id)
            if (config.audioType.equals("speechrecognition", ignoreCase = true)) assistantMicrophoneTypes.add(id)
            val uplink = microphoneUplinks.computeIfAbsent(id) {
                MicrophoneUplink(config, audioRouting, onAudioDiagnostic, callProcessingEnabled,
                    factorySource = factoryAudio?.microphoneSource(config.audioType, config.sampleRate, wirelessAudio)) { callMode.release(id) }
            }
            if (!uplink.start()) onMicrophoneStopped(id)
        } catch (error: Exception) {
            // 麦克风失败不能打断下行音频，释放本次录音和通信模式后等待下一次流启动。
            MicrophoneCaptureStats.reportStartFailure(config, error, onAudioDiagnostic)
            onMicrophoneStopped(id)
        }
    }

    @Synchronized
    override fun onMicrophoneStopped(id: AudioStreamId) {
        try { microphoneUplinks.remove(id)?.close() }
        finally {
            assistantMicrophoneTypes.remove(id)
            callMode.release(id)
        }
    }

    fun close() {
        synchronized(videoLifecycleLock) {
            if (closed) return
            closed = true
            videoDecoders.values.forEach { retiringVideoDecoders.add(it); it.close() }
            videoDecoders.clear()
            surfaces.clear()
            defaultSurface = null
            videoSizeChanged = null
        }
        synchronized(screenStateLock) {
            activeScreenTypes.forEach { screenStreamActiveChanged?.invoke(it, false) }
            activeScreenTypes.clear()
            screenStreamActiveChanged = null
        }
        videoRecoveryHandlers.clear()
        videoDiagnosticHandlers.clear()
        recoveryExecutor.shutdownNow()
        synchronized(this) {
            audioFocusCoordinator.close()
            audioRouting?.close()
            audioRenderers.values.forEach(AudioRenderer::close)
            audioRenderers.clear()
            try { microphoneUplinks.values.forEach { runCatching { it.close() } } }
            finally { microphoneUplinks.clear(); callMode.close() }
            assistantAudioTypes.clear()
            assistantMicrophoneTypes.clear()
        }
        val hadMedia = synchronized(mediaAudioTypes) { mediaAudioTypes.isNotEmpty().also { mediaAudioTypes.clear() } }
        if (hadMedia) mediaAudioChanged(false)
    }

    private fun videoDecoder(type: Int): VideoDecoder? = synchronized(videoLifecycleLock) {
        if (closed) return@synchronized null
        videoDecoders.computeIfAbsent(type) {
            VideoDecoder(
                type,
                surfaces[type] ?: defaultSurface,
                videoWidth,
                videoHeight,
                preferSoftwareHevcDecoder,
                requestKeyFrame = { requestVideoRecovery(type) },
                report = {
                    if (type == 110 && it == "first frame rendered") mainVideoFailure = null
                    videoDiagnosticHandlers[type]?.invoke(it)
                },
                onFailure = { codec, reason ->
                    if (type == 110 && !closed) {
                        mainVideoFailure = codec to reason
                        videoFailure?.invoke(codec, reason)
                    }
                },
                onClosed = { decoder -> synchronized(videoLifecycleLock) { retiringVideoDecoders.remove(decoder) } },
                onOutputSize = { width, height ->
                    if (type == 110 && !closed) {
                        mainVideoSize = width to height
                        videoSizeChanged?.invoke(width, height)
                    }
                },
            )
        }
    }

    @Synchronized
    private fun audioRenderer(id: AudioStreamId, format: AudioFormat): AudioRenderer? {
        if (closed) return null
        val existing = audioRenderers[id]
        if (existing?.format == format) return existing
        existing?.close()
        return AudioRenderer(
            format,
            if (audioRoutingTemplate != null) true else advancedAudioChannelMapping,
            audioFocusEnabled,
            audioRoutingTemplate?.choice(AudioOutputRole.MEDIA) ?: mediaChannel,
            audioRoutingTemplate?.choice(AudioOutputRole.NAVIGATION) ?: navigationChannel,
            audioRoutingTemplate?.choice(AudioOutputRole.ASSISTANT) ?: assistantChannel,
            audioFocusCoordinator,
            audioRouting,
            factoryAudio,
            navigationStreamType,
            mediaBufferMillis,
            onAudioDiagnostic,
            wirelessAudio,
        ).also { audioRenderers[id] = it }
    }
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(
    streamType: Int,
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val requestKeyFrame: () -> Unit,
    private val report: (String) -> Unit,
    private val onFailure: (VideoCodec, String) -> Unit,
    private val onClosed: (VideoDecoder) -> Unit,
    private val onOutputSize: (Int, Int) -> Unit,
) : Closeable {
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    @Volatile private var outputSurface: Surface? = surface
    @Volatile private var desiredSurface: Surface? = surface
    private val lifecycleLock = Any()
    private var terminated = false
    private val exitCallbacks = mutableListOf<() -> Unit>()
    private val recoveryGate = VideoRecoveryGate()
    private val startupWatchdog = VideoStartupWatchdog()
    private var failureReported = false
    private val decoderCandidateCache = mutableMapOf<String, List<VideoDecoderCandidate>>()
    private val outputInfo = MediaCodec.BufferInfo()
    private val inputTiming = VideoInputTiming()
    private val renderHandler = Handler(Looper.getMainLooper())
    @Volatile private var surfaceChangedUs = 0L
    private var lastConfig: VideoJob.Config? = null
    @Volatile private var renderedFrameLogged = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private val referenceChain = VideoReferenceChain()
    private var lastKeyFrameRequestNs = 0L
    // 主屏统计不添加流类型后缀，其余流独立标记。
    private val stats = VideoStats(if (streamType == 110) "" else " stream=$streamType")
    private val queue = VideoDecodeQueue(onDepth = stats::onQueued)
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true; start() }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        synchronized(lifecycleLock) { if (running) queue.offer(VideoJob.Config(codec, codecData)) }
    }

    fun submit(nalus: ByteArray) {
        synchronized(lifecycleLock) {
            if (!running) return
            val frame = VideoJob.Frame(nalus)
            stats.onReceived(nalus.size)
            queue.offer(frame)
        }
    }

    fun setSurface(surface: Surface?) {
        synchronized(lifecycleLock) {
            if (!running) return
            desiredSurface = surface
            queue.offer(VideoJob.SurfaceChanged(surface))
        }
    }

    fun detachSurface(surface: Surface, onDetached: () -> Unit) {
        val completeNow = synchronized(lifecycleLock) {
            if (desiredSurface === surface) desiredSurface = null
            when {
                terminated -> true
                !running -> { exitCallbacks.add(onDetached); false }
                else -> { queue.offer(VideoJob.SurfaceChanged(desiredSurface, onDetached)); false }
            }
        }
        if (completeNow) onDetached()
    }

    override fun close() {
        synchronized(lifecycleLock) { running = false; desiredSurface = null; thread.interrupt() }
    }

    private fun run() {
        try {
            while (running) {
                // 有输入待输出时快排空；静态画面降低轮询，控制/输入入队会立即唤醒。
                val job = queue.poll(if (inputTiming.pending > 0) 5 else 50)
                try {
                    when (job) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            if (VideoFrameBudget.remainingNs(job.receivedNs, System.nanoTime()) == 0L) {
                                stats.onDropped(1 + queue.discardFrames(), VideoDropReason.EXPIRED)
                                recover("video backlog exceeded 250 ms")
                            } else feed(job)
                        }
                        is VideoJob.SurfaceChanged -> {
                            try { changeSurface(job.surface) }
                            catch (error: Exception) { releaseDecoder(); throw error }
                            finally { job.onApplied() }
                        }
                        is VideoJob.Resync -> recover("video queue overflow")
                        null -> Unit
                    }
                    decoder?.let(::drainOutput)
                    if (outputSurface?.isValid == true && outputSurface === desiredSurface) {
                        startupWatchdog.failure(System.nanoTime())?.let {
                            report("startup stalled: $it")
                            reportFailure(it)
                        }
                    }
                    stats.logIfDue()?.let(report)
                    if (!recoveryGate.exhausted && referenceChain.needsKeyFrame &&
                        lastConfig != null && outputSurface != null) requestKeyFrameIfDue()
                } catch (error: Exception) {
                    if (running) Log.e(TAG, "video decoder job failed: ${job?.javaClass?.simpleName}", error)
                    if (running) report("decoder error ${error.javaClass.simpleName}; waiting for keyframe")
                    if (running) recover("decoder exception ${error.javaClass.simpleName}")
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            releaseDecoder()
            val callbacks = synchronized(lifecycleLock) {
                terminated = true
                queue.drain().filterIsInstance<VideoJob.SurfaceChanged>().map { it.onApplied } +
                    exitCallbacks.toList().also { exitCallbacks.clear() }
            }
            callbacks.forEach { runCatching { it() } }
            onClosed(this)
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        val changed = previous == null || previous.codec != config.codec || !previous.codecData.contentEquals(config.codecData)
        if (changed) { recoveryGate.reset(); failureReported = false }
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        if (!recoveryGate.canRetry(System.nanoTime())) return
        duplicateConfigLogged = false
        releaseDecoder()
        referenceChain.reset()
        val surface = outputSurface?.takeIf { it.isValid && it === desiredSurface } ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val csd = if (codec == VideoCodec.H265) {
            MediaCodecSupport.hevcCodecSpecificData(codecData).takeIf { it.isNotEmpty() }
                ?.let { listOf(it) } ?: emptyList()
        } else {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            listOfNotNull(
                sps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
                pps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
            )
        }
        if (codec == VideoCodec.H265 && csd.isEmpty()) {
            report("HEVC configuration rejected: missing or malformed VPS/SPS/PPS bytes=${codecData.size}")
            recover("HEVC initialization parameters invalid")
            reportFailure("HEVC initialization parameters invalid")
            return
        }
        report("codec config mime=$mime source=${if (codecData.firstOrNull() == 1.toByte()) "record" else "AnnexB"} csdBytes=${csd.sumOf { it.size }}")
        // 每个可用硬件先试调优格式，再试精简格式；全部失败才尝试软件。
        val candidates = decoderCandidates(mime)
        val attempts = candidates.flatMap { candidate ->
            if (candidate.software) listOf(DecoderAttempt(candidate, false))
            else listOf(DecoderAttempt(candidate, true), DecoderAttempt(candidate, false))
        }
        var next: MediaCodec? = null
        for (attempt in attempts) {
            next = tryConfigure(mime, csd, surface, attempt)
            if (next != null) break
        }
        if (next == null) {
            recover("decoder configuration failed mime=$mime size=${width}x$height")
        }
        decoder = next
        renderedFrameLogged = false
        submittedFrameLogged = false
        if (next != null) {
            val info = next.codecInfo
            val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isHardwareAccelerated
            val software = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isSoftwareOnly
            report("decoder=${next.name} hardware=$hardware software=$software mime=$mime size=${width}x$height")
            Log.i(
                TAG,
                "video decoder configured name=${next.name} mime=$mime size=${width}x$height",
            )
        }
    }

    private data class DecoderAttempt(val candidate: VideoDecoderCandidate, val tuned: Boolean)

    private fun buildFormat(mime: String, csd: List<ByteArray>, tuned: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(mime, width, height).apply {
            if (tuned) {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            csd.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
        }

    private fun tryConfigure(
        mime: String,
        csd: List<ByteArray>,
        surface: Surface,
        attempt: DecoderAttempt,
    ): MediaCodec? {
        var candidate: MediaCodec? = null
        var phase = "create"
        return try {
            val format = buildFormat(mime, csd, attempt.tuned)
            val codec = MediaCodec.createByCodecName(attempt.candidate.name)
            candidate = codec
            if (attempt.tuned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                attempt.candidate.lowLatency) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            phase = "configure"
            codec.configure(format, surface, null, 0)
            codec.setOnFrameRenderedListener({ source, presentationUs, renderedNs ->
                if (running && decoder === source && outputSurface === desiredSurface &&
                    desiredSurface != null && presentationUs >= surfaceChangedUs) {
                    stats.onPresented(presentationUs, renderedNs)
                    if (!renderedFrameLogged) {
                        renderedFrameLogged = true
                        startupWatchdog.rendered()
                        report("first frame rendered")
                        Log.i(TAG, "video decoder first Surface presentation callback")
                    }
                }
            }, renderHandler)
            phase = "start"
            codec.start()
            report("decoder attempt=${codec.name} tuned=${attempt.tuned} " +
                "lowLatency=${attempt.tuned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && attempt.candidate.lowLatency}")
            codec
        } catch (error: Exception) {
            runCatching { candidate?.release() }
            val detail = (error as? MediaCodec.CodecException)?.diagnosticInfo ?: error.javaClass.simpleName
            val codecError = error as? MediaCodec.CodecException
            report("configure rejected decoder=${attempt.candidate.name} phase=$phase tuned=${attempt.tuned} " +
                "mime=$mime size=${width}x$height diagnostic=$detail errorCode=${codecError?.errorCode} " +
                "recoverable=${codecError?.isRecoverable} transient=${codecError?.isTransient} " +
                "reason=${error.message?.replace('\n', ' ')?.replace('\r', ' ')?.take(160)}")
            Log.w(
                TAG,
                "video decoder configure failed name=${attempt.candidate.name} " +
                    "tuned=${attempt.tuned} mime=$mime size=${width}x$height",
                error,
            )
            null
        }
    }

    private fun decoderCandidates(mime: String): List<VideoDecoderCandidate> = decoderCandidateCache.getOrPut(mime) {
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.mapNotNull { info ->
            if (info.isEncoder || mime !in info.supportedTypes) return@mapNotNull null
            runCatching {
                val capabilities = info.getCapabilitiesForType(mime)
                val video = capabilities.videoCapabilities
                val supported = video?.isSizeSupported(width, height) == true
                report("decoder capability codec=${info.name} mime=$mime requested=${width}x$height " +
                    "sizeSupported=$supported widths=${video?.supportedWidths} heights=${video?.supportedHeights} " +
                    "alignment=${video?.widthAlignment}x${video?.heightAlignment} fpsRange=${video?.supportedFrameRates} " +
                    "profiles=${capabilities.profileLevels.joinToString(",") { "${it.profile}:${it.level}" }}")
                if (!supported) return@runCatching null
                VideoDecoderCandidate(
                    info.name,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isHardwareAccelerated,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isSoftwareOnly,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && capabilities.isFeatureSupported("low-latency"),
                )
            }.onFailure { report("decoder capability query failed codec=${info.name} mime=$mime error=${it.javaClass.simpleName}") }.getOrNull()
        }
        if (candidates.isEmpty()) report("no usable decoder mime=$mime size=${width}x$height reason=capability_filter")
        VideoDecoderSelection.ordered(candidates,
            preferSoftwareHevcDecoder && mime == MediaFormat.MIMETYPE_VIDEO_HEVC)
    }

    private fun changeSurface(surface: Surface?) {
        if (surface != null && surface !== desiredSurface) {
            // 排队期间已被替换的目标不再创建或重绑 codec；解除仍持有的旧目标。
            if (outputSurface !== desiredSurface) {
                releaseDecoder(); outputSurface = null; referenceChain.reset()
            }
            return
        }
        if (outputSurface === surface) return
        outputSurface = surface
        surfaceChangedUs = System.nanoTime() / 1000
        renderedFrameLogged = false
        startupWatchdog.reset()
        failureReported = false
        if (surface == null) {
            releaseDecoder()
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        // 新的显示目标是显式恢复机会；保留成功切换的 codec，不无条件重建。
        recoveryGate.reset()
        val codec = decoder
        if (codec != null) {
            try {
                codec.setOutputSurface(surface)
                Log.i(TAG, "video decoder output surface updated")
                return
            } catch (error: Exception) {
                Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        releaseDecoder()
        lastConfig?.let(::configureDecoder)
    }

    private fun feed(frame: VideoJob.Frame) {
        val nalus = frame.nalus
        val config = lastConfig ?: run { stats.onDropped(reason = VideoDropReason.NO_CONFIG); return }
        if (outputSurface == null || outputSurface !== desiredSurface) {
            // 跳过的是压缩参考帧，目标返回后必须重新建立参考链。
            stats.onDropped(reason = VideoDropReason.NO_TARGET); releaseDecoder(); referenceChain.reset(); return
        }
        if (decoder == null && !recoveryGate.canRetry(System.nanoTime())) {
            stats.onDropped(reason = VideoDropReason.RECOVERY_WAIT); return
        }
        val annexB = MediaCodecSupport.toAnnexB(nalus)
        if (annexB.isEmpty()) { stats.onDropped(); recover("invalid video access unit"); return }
        if (!referenceChain.accepts(annexB, config.codec)) {
            stats.onDropped(reason = VideoDropReason.WAIT_KEYFRAME)
            requestKeyFrameIfDue()
            return
        }
        if (decoder == null) configureDecoder(config)
        val codec = decoder ?: run { stats.onDropped(reason = VideoDropReason.RECOVERY_WAIT); return }
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(
                TAG,
                "video decoder first input avcc=${nalus.size} annexB=${annexB.size} " +
                    "head=${annexB.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }}",
            )
        }
        val index = VideoInputPump.acquire(
            running = { running && outputSurface === desiredSurface }, drain = { drainOutput(codec) },
            dequeue = {
                val remaining = VideoFrameBudget.remainingNs(frame.receivedNs, System.nanoTime())
                if (remaining == 0L) -1 else codec.dequeueInputBuffer(minOf(INPUT_TIMEOUT_US, remaining / 1000))
            },
            timeoutNs = VideoFrameBudget.remainingNs(frame.receivedNs, System.nanoTime()),
        )
        if (!running) return
        if (outputSurface !== desiredSurface) {
            stats.onDropped(reason = VideoDropReason.NO_TARGET); releaseDecoder(); referenceChain.reset(); return
        }
        if (index < 0 || VideoFrameBudget.remainingNs(frame.receivedNs, System.nanoTime()) == 0L) {
            stats.onDropped(1 + queue.discardFrames(), VideoDropReason.EXPIRED)
            recover("video decoder input exceeded frame age budget"); return
        }
        val input = checkNotNull(codec.getInputBuffer(index)) { "Decoder input buffer unavailable" }
        input.clear()
        if (annexB.size <= input.remaining()) {
            input.put(annexB)
            if (VideoFrameBudget.remainingNs(frame.receivedNs, System.nanoTime()) == 0L) {
                stats.onDropped(1 + queue.discardFrames(), VideoDropReason.EXPIRED)
                recover("video input copy exceeded frame age budget"); return
            }
            // 本地接收时刻作为 PTS，可关联输入、输出和 Surface 回调的帧龄。
            val inputNs = System.nanoTime()
            codec.queueInputBuffer(index, 0, annexB.size, frame.receivedNs / 1000, 0)
            inputTiming.record(frame.receivedNs / 1000, inputNs)
            stats.onInput(frame.receivedNs)
            startupWatchdog.input(inputNs)
            referenceChain.onQueued()
        } else {
            stats.onDropped(reason = VideoDropReason.INPUT_CAPACITY)
            recover("video frame exceeded codec input capacity")
            return
        }
        drainOutput(codec)
    }

    private fun recover(reason: String) {
        if (recoveryGate.exhausted || (decoder == null && !recoveryGate.canRetry(System.nanoTime()))) return
        Log.w(TAG, "Video recovery: $reason; waiting for keyframe")
        stats.onRecovery()
        recoveryGate.onFailure(System.nanoTime())
        report("recovery: $reason attempt=${recoveryGate.failures}/4 " +
            "${if (recoveryGate.exhausted) "budget exhausted; restart session to retry" else "waiting for keyframe"}")
        if (recoveryGate.exhausted) reportFailure(reason)
        // 重建时重新提交初始化数据，不能 flush 后丢失首帧参数。
        releaseDecoder()
        referenceChain.reset()
        if (!recoveryGate.exhausted) requestKeyFrameIfDue()
    }

    private fun reportFailure(reason: String) {
        if (failureReported) return
        val config = lastConfig ?: return
        failureReported = true
        report("video unavailable codec=${config.codec} reason=$reason")
        onFailure(config.codec, reason)
    }

    private fun requestKeyFrameIfDue() {
        if (!running || recoveryGate.exhausted || desiredSurface == null) return
        val now = System.nanoTime()
        if (lastKeyFrameRequestNs != 0L && now - lastKeyFrameRequestNs < 1_000_000_000L) return
        lastKeyFrameRequestNs = now
        requestKeyFrame()
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = outputInfo
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    startupWatchdog.output()
                    val now = System.nanoTime()
                    val expired = VideoFrameBudget.remainingNs(info.presentationTimeUs * 1000, now) == 0L
                    val render = running && !expired && outputSurface != null && outputSurface === desiredSurface
                    val decodeNs = inputTiming.take(info.presentationTimeUs, now)
                    // 已解码图像可以丢弃，不会破坏 codec 内部参考链；避免补播过期画面。
                    codec.releaseOutputBuffer(index, render)
                    if (render) {
                        stats.onSubmitted(info.presentationTimeUs, decodeNs); recoveryGate.onOutput(now)
                    } else stats.onDropped(reason = if (expired) VideoDropReason.OUTPUT_EXPIRED else VideoDropReason.NO_TARGET)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun logOutputFormat(format: MediaFormat) {
        val codedWidth = format.intOrNull(MediaFormat.KEY_WIDTH) ?: width
        val codedHeight = format.intOrNull(MediaFormat.KEY_HEIGHT) ?: height
        val visibleWidth = ((format.intOrNull("crop-right") ?: (codedWidth - 1)) -
            (format.intOrNull("crop-left") ?: 0) + 1).coerceIn(1, codedWidth.coerceAtLeast(1))
        val visibleHeight = ((format.intOrNull("crop-bottom") ?: (codedHeight - 1)) -
            (format.intOrNull("crop-top") ?: 0) + 1).coerceIn(1, codedHeight.coerceAtLeast(1))
        onOutputSize(visibleWidth, visibleHeight)
        report("output format requested=${width}x${height} " +
            "coded=${format.intOrNull(MediaFormat.KEY_WIDTH)}x${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
            "crop=${format.intOrNull("crop-left")},${format.intOrNull("crop-top")}," +
            "${format.intOrNull("crop-right")},${format.intOrNull("crop-bottom")} " +
            "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
            "color=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)}/${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)}/${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}")
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    @Synchronized
    private fun releaseDecoder() {
        val codec = decoder
        decoder = null
        inputTiming.clear()
        startupWatchdog.reset()
        if (codec != null) {
            runCatching { codec.setOnFrameRenderedListener(null, null) }
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val INPUT_TIMEOUT_US = 10_000L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val audioFocusEnabled: Boolean,
    private val mediaChannel: Int,
    private val navigationChannel: Int,
    private val assistantChannel: Int,
    private val audioFocusCoordinator: AudioFocusCoordinator,
    private val audioRouting: L7AudioRouting?,
    private val factoryAudio: L7FactoryAudioProfile?,
    private val navigationStreamType: Int,
    private val mediaBufferMillis: Int,
    private val report: (String) -> Unit,
    private val wirelessAudio: Boolean,
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int, val receivedNs: Long)

    private val e5Realtime = E5WirelessAudioBuffer.applies(wirelessAudio, format)
    private val playoutClock = if (e5Realtime) WirelessAudioPlayoutClock(format.sampleRate) else null
    private var pendingPacket: AudioPacket? = null
    private var pendingDueNs = 0L

    private var routeBinding: L7AudioRouting.Binding? = null
    private var trackAttributes: AudioAttributes? = null
    private var mappedChannel: AudioChannel? = null
    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var fadeApplied = false
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var shortOpusPackets = 0
    private var decoderUnavailablePackets = 0
    private var outputBuffers = 0
    private var firstPcmLogged = false
    private val packetsReceived = AtomicInteger()
    private val packetsDropped = AtomicInteger()
    private val lastArrivalNs = AtomicLong()
    private val maxArrivalGapMs = AtomicLong()
    private val frameBytes = if (format.channels >= 2) 4 else 2
    private var totalWrittenFrames = 0L
    private var writtenFramesThisWindow = 0L
    private var writeErrorsThisWindow = 0
    private var lastWriteErrorCode: Int? = null
    private var zeroWritesThisWindow = 0
    private var partialWritesThisWindow = 0
    private var lastPlaybackHeadFrames: Long? = null
    private var maxWriteMs = 0L
    private var statsWindowStartNs = 0L
    private var statsLastUnderruns = 0
    private var bytesPerSecond = 0
    private val bufferProgress = AudioBufferProgress(if (format.channels >= 2) 4 else 2)
    private var underrunsAtPlaybackStart = 0
    private var lastPcmWriteNs = 0L
    private var rebufferCount = 0
    private var diagnosticStage = "starting"
    private var lastDecoderOutputMetadata: String? = null
    private var decoderOutputReports = 0
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    fun start() {
        if (started) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (started) {
            packetsReceived.incrementAndGet()
            val now = System.nanoTime()
            val previous = lastArrivalNs.getAndSet(now)
            if (previous != 0L) maxArrivalGapMs.accumulateAndGet((now - previous) / 1_000_000L, ::maxOf)
        }
        if (!started || !queue.offer(AudioPacket(rtp, sample, System.nanoTime()))) {
            if (started) packetsDropped.incrementAndGet()
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
                report("Audio: queue full audioType=${format.audioType}")
            }
        }
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        try {
            runCatching { report("Audio: starting api=${Build.VERSION.SDK_INT} " +
                "audioType=${format.audioType} codec=${format.codec} rate=${format.sampleRate} channels=${format.channels} " +
                "mapping=${if (advancedAudioChannelMapping) "automotive" else "mobile"} " +
                "mediaChannel=$mediaChannel navigationChannel=$navigationChannel assistantChannel=$assistantChannel focus=$audioFocusEnabled") }
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                AudioCodecKind.LPCM -> Unit
            }
            if (!running) return
            createTrack()
            diagnosticStage = "focus"
            requestAudioFocus()
            while (running) {
                diagnosticStage = "packet"
                nextPacket()?.let(::handle)
                // 尾包之后仍需轮询解码输出，避免短句等待下一个网络包才播放。
                diagnosticStage = "decoder-output"
                codec?.let(::drainCodec)
                diagnosticStage = "buffer-maintenance"
                maintainPlaybackBuffer()
                diagnosticStage = "stats"
                logStatsIfDue()
            }
        } catch (_: InterruptedException) {
            // 会话关闭，结束当前 worker。
        } catch (error: Exception) {
            if (running) {
                Log.e(TAG, "audio renderer worker failed", error)
                reportFailure(error)
            }
        } catch (error: LinkageError) {
            // 记录不支持的系统 API，保留原异常语义。
            if (running) reportFailure(error)
            throw error
        } finally {
            runCatching { logStatsIfDue(force = true) }
            release()
        }
    }

    private fun nextPacket(): AudioPacket? {
        if (pendingPacket == null) {
            val packet = queue.poll(AUDIO_POLL_MILLIS, TimeUnit.MILLISECONDS) ?: return null
            val clock = playoutClock ?: return packet
            pendingPacket = packet
            pendingDueNs = clock.dueNs(packet.sample, packet.receivedNs)
        }
        val remaining = pendingDueNs - System.nanoTime()
        if (remaining > 0) {
            TimeUnit.NANOSECONDS.sleep(minOf(remaining, AUDIO_POLL_MILLIS * 1_000_000L))
            return null
        }
        return pendingPacket.also { pendingPacket = null }
    }

    private fun configureCodec(mime: String) {
        diagnosticStage = "decoder-format"
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        codec = try {
            MediaCodecStartup.create(
                create = { diagnosticStage = "decoder-create"; MediaCodec.createDecoderByType(mime) },
                configure = { diagnosticStage = "decoder-configure"; it.configure(mediaFormat, null, null, 0) },
                start = { diagnosticStage = "decoder-start"; it.start() },
                release = { it.release() },
            ).also {
                // 驱动名称查询或诊断回调失败不能丢弃已启动的解码器。
                runCatching {
                    val name = it.name
                    Log.i(TAG, "audio decoder configured mime=$mime name=$name")
                    report("Audio: decoder ready audioType=${format.audioType} codec=${format.codec} name=$name")
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
            reportFailure(error)
            null
        }
    }

    private fun createTrack() {
        diagnosticStage = "track-buffer-size"
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            runCatching { report("Audio: track unavailable stage=$diagnosticStage " +
                "audioType=${format.audioType} rate=${format.sampleRate} channels=${format.channels} minBufferResult=$minBuffer") }
            return
        }
        val selection = mappedSelection()
        mappedChannel = selection.channel
        val streamOverride = channelOverride(selection.channel)
        var attributes = audioAttributesFor(selection, streamOverride)
        trackAttributes = attributes
        val plan = if (e5Realtime) E5WirelessAudioBuffer.plan(format.sampleRate, format.channels, minBuffer)
            else MediaAudioBuffer.plan(selection.channel == AudioChannel.MEDIA,
                format.sampleRate, format.channels, minBuffer, mediaBufferMillis)
        bytesPerSecond = format.sampleRate * frameBytes
        val built: AudioTrack
        var routeLabel: String
        diagnosticStage = "track-build"
        if (!AudioOutputPolicy.isLegacy(streamOverride)) {
            routeLabel = "usage=${attributes.usage} preset=$streamOverride"
            built = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(pcmFormat(encoding, channelMask))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(plan.trackBufferBytes)
                .build()
        } else {
            val streamType = streamOverride
            routeLabel = "streamType=$streamType"
            built = LegacyAudioFallback.build(
                createLegacy = {
                    AudioTrack(streamType, format.sampleRate, channelMask, encoding,
                        plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                },
                isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
                release = { it.release() },
                createFallback = {
                    diagnosticStage = "track-fallback-build"
                    routeLabel = "streamType=$streamType(fallback=usage)"
                    Log.w(TAG, "streamType=$streamType rejected by this ROM; falling back to usage-based track")
                    attributes = audioAttributesFor(selection)
                    AudioTrack.Builder()
                        .setAudioAttributes(attributes)
                        .setAudioFormat(pcmFormat(encoding, channelMask))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(plan.trackBufferBytes)
                        .build()
                },
            )
        }
        track = built
        diagnosticStage = "track-attributes"
        trackAttributes = audioTrackAttributesForFocus(built, attributes)
        routeBinding = audioRouting?.bind(built, AudioOutputPolicy.routingChannel(selection.channel, streamOverride),
            false, format.sampleRate, format.channels, useBus = !AudioOutputPolicy.isLegacy(streamOverride))
        diagnosticStage = "track-capacity"
        val capacityBytes = built.bufferSizeInFrames * frameBytes
        startThresholdBytes = MediaAudioBuffer.startBytesFor(plan.startBytes, capacityBytes,
            if (e5Realtime) frameBytes else PREBUFFER_WRITE_CHUNK_BYTES)
        report("Audio: ready audioType=${format.audioType} codec=${format.codec} " +
            "rate=${format.sampleRate} channels=${format.channels} " +
            "route=$routeLabel " +
            "bufferMs=${capacityBytes * 1000L / bytesPerSecond} startMs=${startThresholdBytes * 1000L / bytesPerSecond} " +
            "wireless=$wirelessAudio jitterTargetMs=${if (e5Realtime) E5WirelessAudioBuffer.JITTER_MILLIS else 0} " +
            "trackState=${built.state} usage=${trackAttributes?.usage} contentType=${trackAttributes?.contentType}")
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "route=$routeLabel " +
                "buffer=${capacityBytes * 1000L / bytesPerSecond}ms start=${startThresholdBytes * 1000L / bytesPerSecond}",
        )
        Log.i(
            TAG,
            "audio route type=${format.payloadType} audioType=${format.audioType} " +
                "mode=${if (advancedAudioChannelMapping) AudioChannelMappingMode.AUTOMOTIVE_BUS else AudioChannelMappingMode.MOBILE_COMPATIBLE} " +
                "channel=${selection.channel} usage=${built.audioAttributes.usage} " +
                "contentType=${built.audioAttributes.contentType} " +
                "streamOverride=$streamOverride " +
                "focus=${if (audioFocusEnabled) "on" else "off"}",
        )
    }

    /** 输出策略可修改，电话保持协议用途；未知持久化值退回内置推荐。 */
    private fun channelOverride(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> mediaChannel
        AudioChannel.NAVIGATION -> navigationChannel
        AudioChannel.ASSISTANT -> assistantChannel
        AudioChannel.PHONE, AudioChannel.RINGTONE -> 0
    }.takeIf(AudioOutputPolicy::valid) ?: 0

    private fun audioAttributesFor(
        selection: AudioChannelSelection,
        streamOverride: Int,
    ): AudioAttributes {
        if (streamOverride in AudioManager.STREAM_SYSTEM..AudioManager.STREAM_ACCESSIBILITY) {
            // Android accepts only its defined legacy stream IDs here. BYD audio policy can
            // map these standard streams to vehicle outputs; arbitrary channel numbers are
            // not valid AudioAttributes legacy stream types.
            try {
                return AudioAttributes.Builder().setLegacyStreamType(streamOverride).build()
            } catch (error: Exception) {
                Log.w(TAG, "legacy audio stream $streamOverride rejected; keeping usage routing", error)
            }
        }
        factoryAudio?.let { return it.attributes(selection.channel, contentTypeFor(selection.contentType), streamOverride) }
        return AudioAttributes.Builder()
            .setUsage(AudioOutputPolicy.usage(selection.channel, streamOverride))
            .setContentType(contentTypeFor(selection.contentType))
            .build()
    }

    private fun mappedSelection(): AudioChannelSelection {
        if (factoryAudio != null && format.audioType.equals("alert", true)) {
            return AudioChannelSelection(AudioChannel.RINGTONE, AudioContentType.SPEECH)
        }
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
        )
    }

    private fun audioAttributesFor(selection: AudioChannelSelection): AudioAttributes =
        factoryAudio?.attributes(selection.channel, contentTypeFor(selection.contentType), 0) ?: AudioAttributes.Builder()
            .setUsage(usageFor(selection.channel))
            .setContentType(contentTypeFor(selection.contentType))
            .build()

    /** L7 对齐博越配置，导航也参与共享焦点；通用核心保留无厂商配置分支。 */
    private fun requestAudioFocus() {
        val channel = mappedChannel ?: return
        val attributes = trackAttributes ?: return
        if (channel == AudioChannel.NAVIGATION && factoryAudio == null) {
            runCatching { report("Audio: focus skipped channel=NAVIGATION policy=diplay-0.2.11") }
            return
        }
        track?.let { audioFocusCoordinator.acquire(it, channel, attributes) }
    }

    private fun abandonAudioFocus() {
        track?.let(audioFocusCoordinator::release)
    }

    private fun pcmFormat(encoding: Int, channelMask: Int) = AndroidAudioFormat.Builder()
        .setSampleRate(format.sampleRate)
        .setChannelMask(channelMask)
        .setEncoding(encoding)
        .build()

    private fun streamType(): Int {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
            navigationStreamType = navigationStreamType,
        ).streamType
    }

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun usageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioChannel.RINGTONE -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
        AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private fun handle(packet: AudioPacket) {
        val rtp = packet.rtp
        val timestampUs = sampleTimestampUs(packet.sample)
        when (format.codec) {
            AudioCodecKind.LPCM -> writePcm(byteSwapS16(rtp.copyOfRange(12, rtp.size)))
            AudioCodecKind.AAC_LC -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.isNotEmpty()) {
                    if (!firstAacPayloadLogged) {
                        firstAacPayloadLogged = true
                        Log.i(
                            TAG,
                            "audio AAC access unit bytes=${accessUnit.size}",
                        )
                    }
                    feedCodec(
                        MediaCodecSupport.adtsFrame(accessUnit, format.sampleRate, format.channels),
                        timestampUs,
                    )
                }
            }
            AudioCodecKind.OPUS -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                    shortOpusPackets++
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        Log.i(
                            TAG,
                            "audio Opus skipping short packet bytes=${accessUnit.size}",
                        )
                    }
                    return
                }
                feedCodec(accessUnit, timestampUs)
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    private fun feedCodec(payload: ByteArray, presentationTimeUs: Long) {
        val codec = codec ?: run { decoderUnavailablePackets++; return }
        diagnosticStage = "decoder-input"
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                Log.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        if (payload.size <= input.remaining()) {
            input.put(payload)
            codec.queueInputBuffer(index, 0, payload.size, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=${payload.size}",
                )
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    private fun drainCodec(codec: MediaCodec) {
        diagnosticStage = "decoder-output"
        val info = MediaCodec.BufferInfo()
        while (running) {
            diagnosticStage = "decoder-output"
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // 驱动元数据查询失败不应中断已正常工作的播放。
                    if (decoderOutputReports < 4) runCatching {
                        val outputFormat = codec.outputFormat
                        val metadata = "rate=${outputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE)} " +
                            "channels=${outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)} " +
                            "pcmEncoding=${outputFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING)}"
                        if (metadata != lastDecoderOutputMetadata) {
                            lastDecoderOutputMetadata = metadata
                            decoderOutputReports++
                            report("Audio: decoded format audioType=${format.audioType} codec=${format.codec} $metadata")
                        }
                    }
                }
                index >= 0 -> {
                    val size = info.size
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            Log.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    if (size > 0) {
                        val output = codec.getOutputBuffer(index)
                        if (output != null) {
                            if (size > pcm.size) pcm = ByteArray(size)
                            output.position(info.offset)
                            output.limit(info.offset + size)
                            output.get(pcm, 0, size)
                            writePcm(pcm, 0, size)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val track = track ?: return
        diagnosticStage = "track-write"
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length",
            )
        }
        if (!fadeApplied) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
        }
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES, (startThresholdBytes - prebufferBytes).coerceAtLeast(frameBytes))
            }
            val writeStarted = System.nanoTime()
            diagnosticStage = "track-write"
            val count = track.write(data, offset + written, writeLength, AudioTrack.WRITE_BLOCKING)
            maxWriteMs = maxOf(maxWriteMs, (System.nanoTime() - writeStarted) / 1_000_000L)
            if (count < 0) {
                writeErrorsThisWindow++
                lastWriteErrorCode = count
                break
            }
            if (count == 0) {
                zeroWritesThisWindow++
                break
            }
            if (count < writeLength) partialWritesThisWindow++
            written += count
            val framesWritten = count / frameBytes
            totalWrittenFrames += framesWritten
            writtenFramesThisWindow += framesWritten
            bufferProgress.written(count)
            lastPcmWriteNs = System.nanoTime()
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    startPlayback(track)
                    Log.i(TAG, "audio playback started type=${format.payloadType}")
                }
            }
        }
    }

    private fun startPlayback(track: AudioTrack) {
        diagnosticStage = "track-play"
        underrunsAtPlaybackStart = track.underrunCount
        track.play()
        playbackStarted = true
    }

    private fun maintainPlaybackBuffer() {
        val track = track ?: return
        if (bufferProgress.shouldRebuffer(!e5Realtime && mappedChannel == AudioChannel.MEDIA, playbackStarted,
                track.underrunCount > underrunsAtPlaybackStart, queue.isEmpty(), track.playbackHeadPosition)) {
            // The hardware buffer has actually drained. Pause without flushing or discarding PCM,
            // then use the configured start threshold again when music resumes.
            track.pause()
            playbackStarted = false
            prebufferBytes = 0
            rebufferCount++
        }
        // A short final burst may never reach the start threshold. Play it after a bounded wait.
        if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
            System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
            startPlayback(track)
        }
    }

    // Persist counters even during packet starvation, and flush before disconnect releases the track.
    private fun logStatsIfDue(force: Boolean = false) {
        val now = System.nanoTime()
        if (statsWindowStartNs == 0L) statsWindowStartNs = now
        if (!force && now - statsWindowStartNs < STATS_WINDOW_NS) return
        routeBinding?.reportActual()
        val underruns = track?.underrunCount ?: 0
        val lastRx = lastArrivalNs.get()
        val currentTrack = track
        val playbackHeadFrames = currentTrack?.playbackHeadPosition
            ?.toLong()?.and(0xffff_ffffL)
        val playbackAdvanceFrames = playbackHeadFrames?.let { current ->
            val previous = lastPlaybackHeadFrames
            lastPlaybackHeadFrames = current
            previous?.let { (current - it) and 0xffff_ffffL }
        }
        val queuedFrames = playbackHeadFrames?.let { (totalWrittenFrames - it).coerceAtLeast(0L) }
        val line = "audio stats audioType=${format.audioType} channel=$mappedChannel " +
            "routeType=${currentTrack?.routedDevice?.type ?: -1} codec=${format.codec} " +
            "trackState=${currentTrack?.state ?: -1} playState=${currentTrack?.playState ?: -1} " +
            "sampleRate=${currentTrack?.sampleRate ?: format.sampleRate} " +
            "trackBufferFrames=${currentTrack?.bufferSizeInFrames ?: -1} " +
            "rx=${packetsReceived.getAndSet(0)} " +
            "dropped=${packetsDropped.getAndSet(0)} underruns=+${underruns - statsLastUnderruns} queue=${queue.size} " +
            "playing=$playbackStarted maxGapMs=${maxArrivalGapMs.getAndSet(0)} " +
            "sinceRxMs=${if (lastRx == 0L) -1 else (now - lastRx) / 1_000_000L} maxWriteMs=$maxWriteMs " +
            "writtenFrames=$writtenFramesThisWindow totalWrittenFrames=$totalWrittenFrames " +
            "playbackHeadFrames=${playbackHeadFrames ?: -1} playbackAdvanceFrames=${playbackAdvanceFrames ?: -1} " +
            "estimatedQueuedFrames=${queuedFrames ?: -1} writeErrors=$writeErrorsThisWindow " +
            "lastWriteError=${lastWriteErrorCode ?: "none"} zeroWrites=$zeroWritesThisWindow " +
            "partialWrites=$partialWritesThisWindow " +
            "decoderDroppedTotal=$inputDropped outputBuffersTotal=$outputBuffers rebuffers=$rebufferCount ended=$force"
        Log.i(STATS_TAG, line)
        report(line)
        if (format.codec != AudioCodecKind.LPCM) {
            // 单独输出解码计数，避免被诊断记录的单行长度限制截断。
            val decoderLine = "Audio: decoder stats audioType=${format.audioType} codec=${format.codec} " +
                "inputQueuedTotal=$inputQueued inputDroppedTotal=$inputDropped " +
                "shortOpusPacketsTotal=$shortOpusPackets decoderUnavailablePacketsTotal=$decoderUnavailablePackets " +
                "outputBuffersTotal=$outputBuffers ended=$force"
            Log.i(STATS_TAG, decoderLine)
            runCatching { report(decoderLine) }
        }
        statsLastUnderruns = underruns
        maxWriteMs = 0L
        writtenFramesThisWindow = 0L
        writeErrorsThisWindow = 0
        lastWriteErrorCode = null
        zeroWritesThisWindow = 0
        partialWritesThisWindow = 0
        statsWindowStartNs = now
    }

    private fun reportFailure(error: Throwable) {
        runCatching { report("Audio: renderer failed api=${Build.VERSION.SDK_INT} " +
            "audioType=${format.audioType} codec=${format.codec} stage=$diagnosticStage " +
            MediaFailureSummary.describe(error)) }
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    private fun byteSwapS16(source: ByteArray): ByteArray {
        for (index in 0 until source.size - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
        return source
    }

    @Synchronized
    private fun release() {
        abandonAudioFocus()
        routeBinding?.close()
        routeBinding = null
        val codec = codec
        this.codec = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        val track = track
        this.track = null
        if (track != null) {
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 4
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val AUDIO_POLL_MILLIS = 10L
        const val BUFFER_TAIL_WAIT_NS = 500_000_000L
        // Holds a burst after a Wi-Fi gap (~4 s of AAC) instead of dropping it.
        const val MAX_QUEUED_PACKETS = 192
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val STATS_TAG = "DiPlay-AudioStats"
        const val STATS_WINDOW_NS = 5_000_000_000L
        const val DECODED_BUFFER_LOG_INTERVAL = 50
    }
}

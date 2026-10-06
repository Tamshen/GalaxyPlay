package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor

/** 独立的无声合成曲目；标准媒体会话与 OEM 注册共用真实上报路径，回调只改变测试数据。 */
internal class L7ManualMediaReport(
    context: Context,
    private val log: (String) -> Unit,
    released: () -> Unit,
    private val current: () -> Boolean,
    port: L7MediaCenterPort = L7ReflectiveMediaCenter(context),
    worker: Executor? = null,
) : L7ReportingSession {
    private val app = context.applicationContext
    private val titles = listOf(context.getString(R.string.l7_report_track_a), context.getString(R.string.l7_report_track_b))
    private val artist = context.getString(R.string.l7_report_artist)
    private val covers = mutableListOf<Bitmap>()
    private var track = 0
    private var cover = 0
    private var playing = false
    private var elapsed = 0L
    @Volatile private var closed = false
    private var native: MediaSession? = null
    private val observed = object : L7MediaCenterPort by port {
        override fun close() {
            try { port.close(); log("stage=oemRelease result=RETURNED") }
            catch (error: Throwable) {
                if (error !is Exception && error !is LinkageError) throw error
                log("stage=oemRelease exceptionType=${error.javaClass.simpleName}")
            } finally { released() }
        }
    }
    private val oem = if (worker == null) L7MediaCenterSession(observed, app.packageName, current, ::command, log = log)
        else L7MediaCenterSession(observed, app.packageName, current, ::command, worker = worker, log = log)
    private val artwork = if (worker == null) L7MediaArtwork(app, report = log, publish = ::artworkChanged)
        else L7MediaArtwork(app, workerExecutor = worker, mainExecutor = worker, report = log, publish = ::artworkChanged)

    private fun artworkChanged(@Suppress("UNUSED_PARAMETER") uri: android.net.Uri?) {
        if (!closed && current()) publish()
    }

    override fun start() {
        if (closed || !current()) return
        native = MediaSession(app, "GalaxyPlay 手动上报测试").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = command(CarPlayMediaButton.PLAY, "android")
                override fun onPause() = command(CarPlayMediaButton.PAUSE, "android")
                override fun onSkipToNext() = command(CarPlayMediaButton.NEXT, "android")
                override fun onSkipToPrevious() = command(CarPlayMediaButton.PREVIOUS, "android")
            }, Handler(Looper.getMainLooper()))
            isActive = true
        }
        covers.add(image(0xff238d64.toInt()))
        covers.add(image(0xff2d67ba.toInt()))
        selectCover()
        publish()
        oem.start()
    }

    override fun action(value: L7ReportingAction) {
        if (closed || !current()) return
        when (value) {
            L7ReportingAction.TRACK -> { track = 1 - track; elapsed = 0; cover = track; selectCover() }
            L7ReportingAction.PLAY_PAUSE -> playing = !playing
            L7ReportingAction.PROGRESS -> elapsed = (elapsed + 15_000).coerceAtMost(180_000)
            L7ReportingAction.COVER -> { cover = (cover + 1) % 3; selectCover() }
            else -> return
        }
        publish()
    }

    private fun command(index: Int, origin: String) {
        if (closed || !current()) return
        log("stage=control origin=$origin index=$index target=TEST_FIXTURE phoneCommand=false")
        when (index) {
            CarPlayMediaButton.PLAY -> playing = true
            CarPlayMediaButton.PAUSE -> playing = false
            CarPlayMediaButton.PLAY_PAUSE -> playing = !playing
            CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS -> {
                track = 1 - track; elapsed = 0; cover = track; selectCover()
            }
            else -> return
        }
        publish()
    }

    private fun publish() {
        if (closed || !current() || native == null) return
        val transfer = if (cover < 2) cover + 1 else null
        val value = CarPlayNowPlaying(title = titles[track], album = "GalaxyPlay", artist = artist,
            sourceApp = "GalaxyPlay", durationMillis = 180_000, elapsedMillis = elapsed,
            artworkTransferId = transfer, playing = playing, playbackKnown = true)
        native?.setMetadata(NowPlayingMetadata.androidMetadata(value, covers.getOrNull(cover)))
        native?.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, elapsed, 0f).build())
        log("stage=androidMedia result=RETURNED track=${if (track == 0) "A" else "B"} playing=$playing elapsedMs=$elapsed cover=${transfer ?: "none"} display=NOT_VERIFIED")
        oem.update(value, artwork.selectedUri(transfer))
    }

    private fun selectCover() {
        val transfer = if (cover < 2) cover + 1 else null
        artwork.select(transfer)
        if (transfer != null && artwork.selectedUri(transfer) == null) {
            val bytes = ByteArrayOutputStream().use { output ->
                covers[cover].compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray()
            }
            artwork.submit(transfer, bytes)
        }
    }

    private fun image(background: Int): Bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
        eraseColor(background)
        val drawable = requireNotNull(app.getDrawable(R.drawable.ic_carplay_mark))
        drawable.setBounds(16, 16, 112, 112)
        drawable.draw(Canvas(this))
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            native?.setCallback(null)
            native?.isActive = false
            native?.release()
            log("stage=androidMediaRelease result=RETURNED")
        } finally {
            native = null
            try { artwork.close() }
            finally {
                try { covers.forEach(Bitmap::recycle); covers.clear() }
                finally { oem.close() }
            }
        }
    }
}

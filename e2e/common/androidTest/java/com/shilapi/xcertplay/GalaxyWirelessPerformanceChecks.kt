package com.shilapi.xcertplay

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Process
import android.os.IBinder
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.network.NativeHotspotConfiguration
import com.shilapi.xcertplay.network.NativeHotspotRead
import com.shilapi.xcertplay.network.CarPlayVpnService
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Android VM 中使用真实 AudioTrack、无线锁及产品播放入口；合成静音包不代表实车听感。 */
object GalaxyWirelessPerformanceChecks {
    @JvmStatic fun prepare(context: Context) {
        File(context.cacheDir, "wireless-adb-public-key").writeBytes(
            AdbKeys.publicKeyMessage(AdbKeys.load(context).public))
    }

    @JvmStatic fun run(context: Context) {
        check(android.os.Build.VERSION.SDK_INT == 30)
        val readEvents = mutableListOf<String>()
        val read = NativeHotspotConfiguration(context, readEvents::add).read()
        check(read.credentials?.valid() == true) { "HOTSPOT_READ_${read.problem} ${readEvents.joinToString()}" }
        val shellRead = shellRead(context)
        check(shellRead.credentials?.valid() == true) { "SHELL_HOTSPOT_READ_${shellRead.problem}" }
        check(read.credentials!!.matches(shellRead.credentials!!))
        locks(context)
        listeners(context)
        repeat(3) { scans(context) { playback(context) } }
        check(shellRead.credentials!!.matches(shellRead(context).credentials!!))
    }

    private fun listeners(context: Context) {
        val connected = CountDownLatch(1)
        var service: CarPlayVpnService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = (binder as CarPlayVpnService.LocalBinder).service
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        check(context.bindService(Intent(context, CarPlayVpnService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            check(connected.await(5, TimeUnit.SECONDS))
            val active = service!!
            val received = LinkedBlockingQueue<AirPlayTcpAccepted>()
            val addresses = listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1"))
            val config = AirPlayConfig("synthetic", "synthetic", "", "1", AirPlayDisplayConfig(800, 480), port = 0)
            var previous: AirPlayListenerIdentity? = null
            repeat(3) { generation ->
                val owner = AirPlayListenerIdentity(generation)
                check(active.attachWireless(addresses[0], config,
                    AirPlayIdentity(ByteArray(32), ByteArray(32), "synthetic"), PairingStore(), null,
                    object : AirPlaySessionListener {
                        override fun onTcpAccepted(event: AirPlayTcpAccepted) { received.add(event) }
                    }, object : AirPlayMediaHandler {}, additionalBindAddresses = addresses.drop(1),
                    listenerIdentity = owner) == CarPlayVpnService.AttachResult.Started)
                previous?.let(active::detachWireless)
                check(active.isAttached())
                for (address in addresses) Socket(address, active.boundPort()!!).use {
                    val event = received.poll(3, TimeUnit.SECONDS) ?: error("DUAL_STACK_TCP_TIMEOUT")
                    check(event.listener === owner && event.internal && event.acceptedAtNanos > 0)
                }
                previous = owner
            }
            active.detachWireless(previous!!)
            check(!active.isAttached() && active.awaitDetached(3_000))
        } finally { service?.detach(); context.unbindService(connection) }
    }

    private fun shellRead(context: Context): NativeHotspotRead {
        val type = Class.forName("com.shilapi.xcertplay.network.GalaxyHotspotShellRead")
        return type.getDeclaredMethod("read", Context::class.java, kotlin.jvm.functions.Function1::class.java)
            .apply { isAccessible = true }.invoke(type.getDeclaredField("INSTANCE").get(null),
                context, { _: String -> Unit }) as NativeHotspotRead
    }

    private fun scans(context: Context, play: () -> Unit) {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val type = Class.forName("com.shilapi.xcertplay.network.WifiScanPause")
        val owner = type.declaredConstructors.single { it.parameterCount == 5 }
            .apply { isAccessible = true }.newInstance(context, { message: String -> events.add(message); Unit }, null, 4, null)
        val prefs = context.getSharedPreferences("diplay_wifi_scan_pause", 0)
        try {
            type.getDeclaredMethod("pause").apply { isAccessible = true }.invoke(owner)
            await { events.any { it.contains("paused=true reason=done") } }
            check(prefs.getBoolean("restore_pending", false))
            play()
        } finally {
            type.getDeclaredMethod("close").apply { isAccessible = true }.invoke(owner)
            await { !prefs.contains("restore_pending") }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 25_000_000_000L
        while (!condition()) { check(System.nanoTime() < deadline) { "WIRELESS_HELPER_TIMEOUT" }; Thread.sleep(50) }
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)

    private fun locks(context: Context) {
        val type = Class.forName("com.shilapi.xcertplay.network.WirelessPerformanceLock")
        val owner = type.declaredConstructors.single { it.parameterCount == 2 }
            .apply { isAccessible = true }.newInstance(context, { _: String -> Unit })
        fun call(name: String) = type.getDeclaredMethod(name).apply { isAccessible = true }.invoke(owner)
        try {
            call("acquire")
            val high = field(owner, "highPerformance") as WifiManager.WifiLock
            val low = field(owner, "lowLatency") as WifiManager.WifiLock
            check(high.isHeld && low.isHeld)
            call("acquire")
            check(field(owner, "highPerformance") === high)
            call("release")
            check(!high.isHeld && !low.isHeld)
            call("acquire")
            val replacement = field(owner, "highPerformance") as WifiManager.WifiLock
            check(replacement.isHeld)
            call("close")
            call("acquire")
            check(!replacement.isHeld && field(owner, "highPerformance") == null)
        } finally { call("close") }
    }

    private fun playback(context: Context) {
        val reports = Collections.synchronizedList(mutableListOf<String>())
        val sink = AndroidMediaSink(context = context, mediaBufferMillis = 100,
            advancedAudioChannelMapping = true, mediaChannel = 101, navigationChannel = 103,
            onAudioDiagnostic = { reports.add(it) })
        val musicId = AudioStreamId(102, "media")
        val navId = AudioStreamId(102, "default")
        val music = AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media")
        val nav = AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "default")
        val workers = mutableListOf<Thread>()
        try {
            sink.onAudioStarted(musicId, music, 0)
            sink.onAudioStarted(navId, nav, 0)
            val renderers = field(sink, "audioRenderers") as Map<*, *>
            val media = renderers[musicId]!!
            val navigation = renderers[navId]!!
            workers += field(media, "thread") as Thread
            workers += field(navigation, "thread") as Thread
            val start = System.nanoTime()
            repeat(160) { packet ->
                val data = ByteArray(12 + 480 * 4).apply { this[0] = 0x80.toByte(); this[1] = 96 }
                sink.onAudioRtp(musicId, music, data, packet * 480)
                if (packet < 40) sink.onAudioRtp(navId, nav, data, packet * 480)
                val wait = start + (packet + 1) * 10_000_000L - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            }
            val track = field(media, "track") as? AudioTrack
                ?: error("AUDIO_TRACK_MISSING stage=${field(media, "diagnosticStage")}")
            check(track.state == AudioTrack.STATE_INITIALIZED && track.playbackHeadPosition > 0)
            check(track.playState == AudioTrack.PLAYSTATE_PLAYING)
            check(track.sampleRate == music.sampleRate && track.playbackRate == music.sampleRate)
            val navTrack = field(navigation, "track") as? AudioTrack ?: error("NAVIGATION_TRACK_MISSING")
            check(navTrack.state == AudioTrack.STATE_INITIALIZED && navTrack.playbackHeadPosition > 0)
            check(track.audioAttributes.usage == 1 && navTrack.audioAttributes.usage == 12)
            check(navTrack.sampleRate == nav.sampleRate && navTrack.playbackRate == nav.sampleRate)
            val tids = File("/proc/self/task").listFiles().orEmpty().filter {
                runCatching { File(it, "comm").readText().trim() == "carplay-audio" }.getOrDefault(false)
            }
            check(tids.size >= 2 && tids.all { Process.getThreadPriority(it.name.toInt()) <= Process.THREAD_PRIORITY_AUDIO })
            check(reports.none { it.startsWith("Audio: failed") || it.contains("clock trim off") })
            sink.onAudioStopped(navId)
            sink.onAudioStopped(musicId)
        } finally {
            sink.close()
            workers.forEach { it.join(3_000); check(!it.isAlive) { "AUDIO_WORKER_LEAK" } }
        }
    }
}

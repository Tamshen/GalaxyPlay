package com.shilapi.xcertplay

import android.app.Activity
import android.media.MediaRecorder
import android.os.SystemClock
import android.view.Surface
import com.shilapi.xcertplay.host.R

/** 完整检查复用原测试组件；不会创建手机连接、修改路由或自动上传。 */
internal object GalaxyDebugChecks {
    fun build(activity: Activity, codecController: GalaxyCodecProbeController, surface: () -> Surface): List<GalaxyDebugFlow.Check> = buildList {
        add(GalaxyDebugFlow.Check("ENVIRONMENT", R.string.l7_probe_environment, R.string.full_debug_environment) {
            val started = L7ProbeRunner.start(activity, L7ProbeEnvironment.window(activity))
            val id = L7ProbeRunner.current?.id
            object : GalaxyDebugFlow.Attempt {
                override fun poll(): GalaxyDebugFlow.Evidence {
                    val report = L7ProbeRunner.current?.takeIf { it.id == id }
                    val done = started && !L7ProbeRunner.busy && report?.phase == L7ProbePhase.COMPLETED &&
                        !L7ProbeRunner.storageFailed && !L7ProbeRunner.logFailed
                    return GalaxyDebugFlow.Evidence(automatic = done,
                        question = !started || !L7ProbeRunner.busy && !done, unavailable = !started,
                        token = id.orEmpty())
                }
                override fun stop() { if (started && L7ProbeRunner.current?.id == id) L7ProbeRunner.stop() }
                override fun released() = !started || !L7ProbeRunner.busy
            }
        })
        addAll(GalaxyDebugCodecChecks.build(activity, codecController, surface))
        addAll(GalaxyDebugReportingChecks.build(activity))
        add(GalaxyDebugFlow.Check("MICROPHONE", R.string.l7_voice_title, R.string.full_debug_microphone) {
            L7VoiceDiagnostics.initialize(activity)
            val test = L7VoiceInputTest(L7VoiceInputTest.SystemAccess(activity))
            val started = test.start(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            object : GalaxyDebugFlow.Attempt {
                override fun poll(): GalaxyDebugFlow.Evidence {
                    val value = test.snapshot
                    return GalaxyDebugFlow.Evidence(question = !started || !value.busy,
                        canConfirm = value.phase == L7VoiceInputTest.Phase.COMPLETE && value.bytes > 0 && !value.silenced,
                        unavailable = !started, token = "${value.phase}:${value.bytes}",
                        detail = if (value.busy) activity.getString(R.string.full_debug_voice_level,
                            (value.elapsedMs / 1000).toInt(), value.rms) else "")
                }
                override fun stop() = test.close()
                override fun released() = !test.snapshot.busy
            }
        })
        addAll(GalaxyDebugInputChecks.build(activity))
    }

    fun unavailable(detail: String = "", automatic: Boolean = false) = object : GalaxyDebugFlow.Attempt {
        override fun poll() = GalaxyDebugFlow.Evidence(question = !automatic, automatic = automatic, unavailable = true, detail = detail)
        override fun stop() {}
        override fun released() = true
    }
    fun now() = SystemClock.elapsedRealtime()
}

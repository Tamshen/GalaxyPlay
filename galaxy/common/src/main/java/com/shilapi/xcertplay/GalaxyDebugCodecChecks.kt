package com.shilapi.xcertplay

import android.app.Activity
import android.view.Surface
import com.shilapi.xcertplay.host.R

/** 自动枚举硬件与调用方式，每次释放后才进入下一项；软件不计硬件结果。 */
internal object GalaxyDebugCodecChecks {
    fun build(activity: Activity, controller: GalaxyCodecProbeController, surface: () -> Surface): List<GalaxyDebugFlow.Check> {
        var phoneBlocked = false
        val checks = CodecProbeVideo.entries.flatMap { video ->
            controller.video = video
            controller.available.distinctBy { it.name }.flatMap { decoder ->
                CodecProbeMethod.entries.map { method ->
                    GalaxyDebugFlow.Check("CODEC_${video.name}_${method.name}_${decoder.name}",
                        R.string.codec_probe_title, R.string.full_debug_codec) {
                        if (phoneBlocked && CarPlayBackgroundSession.hasSession())
                            return@Check GalaxyDebugChecks.unavailable(automatic = true)
                        controller.video = video; controller.selected = decoder.name; controller.method = method
                        var started: Boolean? = null
                        var run = 0L
                        val began = GalaxyDebugChecks.now()
                        object : GalaxyDebugFlow.Attempt {
                            override fun poll(): GalaxyDebugFlow.Evidence {
                                if (started == null && surface().isValid) {
                                    started = controller.start(surface(), false)
                                    run = controller.results.lastOrNull()?.run ?: 0
                                }
                                val result = controller.results.lastOrNull()?.takeIf { it.run > run }
                                val skipped = result?.reason in listOf("UnsupportedVideo", "QtiDecoderRequired")
                                return GalaxyDebugFlow.Evidence(automatic = skipped || result?.let { it.hardwarePassed && !it.method.surfaceOutput } == true,
                                    question = started == false || !controller.busy && result != null || started == null && GalaxyDebugChecks.now() - began > 5_000,
                                    canConfirm = result?.hardwarePassed == true && result.method.surfaceOutput,
                                    unavailable = started != true || skipped, token = result?.run?.toString().orEmpty(),
                                    detail = "${video.title} · ${decoder.name}\n${activity.getString(method.title)}")
                            }
                            override fun observe(normal: Boolean): Boolean {
                                if (!normal && started == false && CarPlayBackgroundSession.hasSession()) phoneBlocked = true
                                controller.observe(normal); return true
                            }
                            override fun stop() { controller.stop("SUITE_NEXT") }
                            override fun released() = !controller.busy && controller.notice != "PROCESS_UNCONFIRMED"
                        }
                    }
                }
            }
        }
        return checks.ifEmpty { listOf(GalaxyDebugFlow.Check("CODEC_UNAVAILABLE", R.string.codec_probe_title,
            R.string.full_debug_no_hardware) { GalaxyDebugChecks.unavailable() }) }
    }
}

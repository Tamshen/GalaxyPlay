package com.shilapi.xcertplay

import android.app.Activity
import com.shilapi.xcertplay.host.R

/** 方控必须收到真实输入；Siri 必须有真实手机会话，人工结果不冒充协议响应。 */
internal object GalaxyDebugInputChecks {
    fun build(activity: Activity): List<GalaxyDebugFlow.Check> = buildList {
        val titles = listOf(R.string.debug_key_left, R.string.debug_key_right, R.string.debug_key_play,
            R.string.debug_key_voice_short, R.string.debug_key_voice_long, R.string.debug_key_volume_up, R.string.debug_key_volume_down)
        SteeringListeningController.LABELS.take(7).forEachIndexed { index, key ->
            add(GalaxyDebugFlow.Check("STEERING_$key", titles[index], R.string.full_debug_key) {
                L7SteeringDiagnostics.initialize(activity)
                VehicleSteeringInputLog.initialize(activity)
                val controller = SteeringListening.controller
                controller.start(L7AudioTemplates.model(activity).id)
                var shown = controller.snapshot()
                object : GalaxyDebugFlow.Attempt {
                    override fun poll(): GalaxyDebugFlow.Evidence {
                        controller.poll(L7AudioTemplates.model(activity).id)
                        shown = controller.snapshot()
                        return GalaxyDebugFlow.Evidence(question = true,
                            canConfirm = controller.active() && shown.pending != null,
                            token = "${shown.run}:${shown.pending?.id ?: 0}")
                    }
                    override fun observe(normal: Boolean): Boolean {
                        val pending = shown.pending
                        if (normal && pending == null) return false
                        if (pending != null && !controller.answer(pending.id, if (normal) key else "SKIP")) return false
                        L7SteeringDiagnostics.record("SteeringListen stage=USER_EFFECT run=${shown.run} " +
                            "sample=${pending?.id ?: 0} key=$key result=${if (normal) "EXPECTED" else "MISSING_OR_ABNORMAL"} origin=MANUAL")
                        return true
                    }
                    override fun stop() = controller.stop("SUITE_NEXT")
                    override fun released() = !controller.active()
                }
            })
        }
        add(GalaxyDebugFlow.Check("SIRI", R.string.l7_voice_siri, R.string.full_debug_siri) {
            val session = CarPlayBackgroundSession.snapshot()
            val owner = session?.controller?.activeMediaSessionOwner()
            val queued = owner != null && session.controller.hasActiveSession() &&
                !session.sink.hasMicrophoneUplink() && session.controller.requestSiri()
            val request = GalaxyDebugChecks.now()
            object : GalaxyDebugFlow.Attempt {
                override fun poll(): GalaxyDebugFlow.Evidence {
                    val current = CarPlayBackgroundSession.snapshot()
                    val same = queued && current?.controller?.hasActiveSession() == true &&
                        current.controller.activeMediaSessionOwner() === owner
                    return GalaxyDebugFlow.Evidence(question = true, canConfirm = same,
                        unavailable = !same, token = "$request:$same")
                }
                override fun observe(normal: Boolean): Boolean {
                    L7VoiceDiagnostics.store.record("VOICE_SIRI phase=USER_OBSERVATION request=$request " +
                        "result=${if (normal) "EXPECTED" else "MISSING_OR_ABNORMAL"} origin=MANUAL")
                    return true
                }
                override fun stop() {}
                override fun released() = true
            }
        })
    }
}

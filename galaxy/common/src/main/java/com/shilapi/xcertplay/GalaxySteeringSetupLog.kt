package com.shilapi.xcertplay

import com.shilapi.xcertplay.diagnostics.DiagnosticChannel
import com.shilapi.xcertplay.diagnostics.DiagnosticEvent
import com.shilapi.xcertplay.diagnostics.DiagnosticSink
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/** 接入阶段只传固定枚举与数值；每次订阅独占通道，保留旧订阅的释放证据。 */
internal class GalaxySteeringSetupLog(private val report: (String) -> Unit) : Closeable {
    enum class Stage {
        START, SKIPPED, BIND_BEGIN, BOUND, CONNECTED, DESCRIPTOR_OK, REGISTER_BEGIN, REGISTERED,
        WAIT_LEASE, INPUT, INPUT_DROPPED, INPUT_FAILED, MALFORMED_INPUT, TIMEOUT, IPC_BUSY,
        UNAVAILABLE, BIND_FAILED, REGISTER_FAILED, LATE_REGISTER, DISCONNECTED, BINDING_DIED,
        BINDER_DIED, NULL_BINDING, CLOSE_REQUESTED, UNREGISTER_BEGIN, UNREGISTERED,
        UNREGISTER_FAILED, UNBOUND, UNBIND_FAILED, RELEASED, SDK_CANDIDATE, SDK_CLASS,
        SDK_CONTRACT, SDK_SELECTED, SDK_REJECTED, ROUTES,
    }
    val id = serial.incrementAndGet()
    private val channel = DiagnosticChannel(id, DiagnosticSink { event ->
        val fields = event.metrics.filterKeys { it != "stage" }.map { (key, value) ->
            when (key) {
                "provider" -> "provider=${PROVIDERS.getOrNull(value.toInt()) ?: "OTHER"}"
                "target" -> "target=${TARGETS.getOrNull(value.toInt()) ?: "OTHER"}"
                "fault" -> "exceptionType=${FAULTS.getOrNull(value.toInt()) ?: "OTHER"}"
                "reason" -> "reason=${REASONS.getOrNull(value.toInt()) ?: "OTHER"}"
                else -> "$key=$value"
            }
        }.joinToString(" ")
        report("STEERING_SETUP run=$run attempt=${event.session} seq=${event.sequence} elapsedMs=${event.elapsedMillis} " +
            "result=${Stage.entries[event.metrics.getValue("stage").toInt()]} $fields")
    })

    fun step(stage: Stage, metrics: Map<String, Long> = emptyMap(), error: Throwable? = null) {
        val fields = metrics + mapOf("stage" to stage.ordinal.toLong()) +
            (error?.let { mapOf("fault" to FAULTS.indexOf(it.javaClass.simpleName).toLong()) } ?: emptyMap())
        channel.emit(DiagnosticEvent.Component.INPUT, DiagnosticEvent.Kind.STATE, DiagnosticEvent.State.UNKNOWN, fields)
    }
    override fun close() = channel.close()

    companion object {
        private val serial = AtomicLong()
        private val run = System.nanoTime()
        private val REASONS = listOf("CONTROL_DISABLED", "MODEL_UNSUPPORTED", "NO_SESSION")
        private val PROVIDERS = listOf("APP_OR_FRAMEWORK", "com.geely.linkhmi", "com.flyme.auto.music",
            "com.netease.cloudmusic.iot", "com.flyme.auto.map")
        private val TARGETS = listOf("com.ecarx.eas.sdk.mediacenter.MediaCenterAPI",
            "com.ecarx.eas.sdk.mediacenter.MusicClient", "com.ecarx.eas.sdk.mediacenter.MusicPlaybackInfo",
            "com.ecarx.eas.sdk.ECarXApiClient\$Callback")
        private val FAULTS = listOf("ClassNotFoundException", "NoClassDefFoundError", "NameNotFoundException",
            "SecurityException", "NoSuchMethodException", "NoSuchFieldException", "IllegalArgumentException",
            "IllegalStateException", "DeadObjectException", "RemoteException", "RejectedExecutionException")
        fun provider(name: String) = PROVIDERS.indexOf(name).toLong()
        fun target(name: String) = TARGETS.indexOf(name).toLong()
    }
}

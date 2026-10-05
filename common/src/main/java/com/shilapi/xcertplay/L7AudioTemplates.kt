package com.shilapi.xcertplay

import android.content.Context
import android.util.AtomicFile
import com.shilapi.xcertplay.media.AudioOutputRole
import com.shilapi.xcertplay.media.AudioRoutingTemplate
import java.io.File
import java.io.InputStream

/** 内置资源只读；L7／L6 显式选择，各自保留方案和原子保存的自定义文件。 */
internal object L7AudioTemplates {
    enum class Model(val id: String) { L7("l7"), L6("l6") }
    enum class Mode(val id: String) { L7("l7"), BUS("l7-bus"), CUSTOM("custom"), L6("l6") }
    private const val PREFS = "l7_audio_templates"
    private const val MODEL = "model"

    fun model(context: Context): Model = Model.entries.firstOrNull {
        it.id == context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MODEL, null)
    } ?: Model.L7

    fun modes(context: Context): List<Mode> = if (model(context) == Model.L6)
        listOf(Mode.L6, Mode.CUSTOM) else listOf(Mode.L7, Mode.BUS, Mode.CUSTOM)

    fun defaultMode(context: Context): Mode = if (model(context) == Model.L6) Mode.L6 else Mode.L7

    @Synchronized fun selectModel(context: Context, model: Model) {
        // 切换前先完成旧 L7 偏好的迁移，避免之后的默认恢复覆盖尚未迁移的选择。
        mode(context)
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(MODEL, model.id).commit())
        mode(context)
    }

    private fun modeKey(context: Context): String = if (model(context) == Model.L6) "mode_l6" else "mode"

    @Synchronized fun mode(context: Context): Mode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(modeKey(context), null)?.let { id ->
            return modes(context).firstOrNull { it.id == id } ?: defaultMode(context)
        }
        val selected = if (model(context) == Model.L6) Mode.L6 else migrateLegacy(context)
        check(prefs.edit().putString(modeKey(context), selected.id).commit())
        return selected
    }

    private fun migrateLegacy(context: Context): Mode {
        val choices = mapOf(AudioOutputRole.MEDIA to AirPlayPersistence.loadMediaAudioChannel(context),
            AudioOutputRole.NAVIGATION to AirPlayPersistence.loadNavigationAudioChannel(context),
            AudioOutputRole.ASSISTANT to AirPlayPersistence.loadAssistantAudioChannel(context))
        val default = builtin(context, Mode.L7)
        return if (choices.any { (role, value) -> value != default.choice(role) }) {
            var legacy = builtin(context, Mode.BUS).withBusEnabled(AirPlayPersistence.loadL7AudioBusEnabled(context))
            choices.forEach { (role, value) -> legacy = legacy.withChoice(role, value) }
            writeCustom(context, legacy)
            Mode.CUSTOM
        } else if (AirPlayPersistence.loadL7AudioBusEnabled(context)) Mode.BUS else Mode.L7
    }

    @Synchronized fun load(context: Context): AudioRoutingTemplate = when (val mode = mode(context)) {
        Mode.CUSTOM -> runCatching { readCustom(context) }.getOrElse { builtin(context, defaultMode(context)) }
        else -> builtin(context, mode)
    }

    @Synchronized fun select(context: Context, mode: Mode) {
        require(mode in modes(context))
        if (mode == Mode.CUSTOM && !customExists(context)) {
            // 新建自定义草稿沿用当前方案；已有文件一律保留。
            writeCustom(context, load(context))
        }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(modeKey(context), mode.id).commit())
    }

    @Synchronized fun saveCustom(context: Context, template: AudioRoutingTemplate, expectedModel: Model = model(context)) {
        check(model(context) == expectedModel) { "AUDIO_TEMPLATE_MODEL_CHANGED" }
        writeCustom(context, AudioRoutingTemplate.parse(template.toJson()))
        select(context, Mode.CUSTOM)
    }

    @Synchronized fun customInvalid(context: Context): Boolean =
        mode(context) == Mode.CUSTOM && runCatching { readCustom(context) }.isFailure

    fun builtin(context: Context, mode: Mode): AudioRoutingTemplate {
        require(mode != Mode.CUSTOM)
        return context.assets.open("audio-templates/${mode.id}.json").use { parse(it) }
    }

    fun parse(input: InputStream): AudioRoutingTemplate {
        val bytes = input.readBytesBounded()
        val decoder = Charsets.UTF_8.newDecoder()
        return AudioRoutingTemplate.parse(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString())
    }

    private fun InputStream.readBytesBounded(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(2048)
        while (true) {
            val count = read(buffer)
            if (count < 0) return out.toByteArray()
            require(out.size() + count <= AudioRoutingTemplate.MAX_BYTES)
            out.write(buffer, 0, count)
        }
    }

    private fun customExists(context: Context): Boolean {
        val base = file(context).baseFile
        // Android 10/11 的 AtomicFile 可从备份恢复，切换方案不能抢先覆盖该备份。
        return base.exists() || File(base.path + ".bak").exists()
    }

    private fun readCustom(context: Context): AudioRoutingTemplate = file(context).openRead().use { parse(it) }

    private fun file(context: Context): AtomicFile = AtomicFile(File(context.filesDir,
        if (model(context) == Model.L6) "audio-template-l6.json" else "audio-template.json"))

    private fun writeCustom(context: Context, template: AudioRoutingTemplate) {
        val target = file(context)
        val stream = target.startWrite()
        try {
            stream.write(template.toJson().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }
}

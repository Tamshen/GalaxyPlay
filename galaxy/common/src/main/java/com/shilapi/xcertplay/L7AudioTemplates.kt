package com.shilapi.xcertplay

import android.content.Context
import android.util.AtomicFile
import com.shilapi.xcertplay.media.AudioOutputRole
import com.shilapi.xcertplay.media.AudioRoutingTemplate
import java.io.File
import java.io.InputStream

/** 内置资源只读；L7／L6 自动应用适配方案；自定义车型独立保存文件，各文件原子写入。 */
internal object L7AudioTemplates {
    enum class Model(val id: String) { L7("l7"), L6("l6"), CUSTOM("custom") }
    enum class Mode(val id: String) { L7("l7"), BUS("l7-bus"), CUSTOM("custom"), L6("l6"), SYSTEM("system") }
    private const val PREFS = "l7_audio_templates"
    private const val MODEL = "model"

    fun model(context: Context): Model = Model.entries.firstOrNull {
        it.id == context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MODEL, null)
    } ?: Model.L7

    fun modes(context: Context): List<Mode> = when (model(context)) {
        Model.L7 -> listOf(Mode.L7, Mode.BUS, Mode.CUSTOM)
        Model.L6 -> listOf(Mode.L6, Mode.CUSTOM)
        Model.CUSTOM -> listOf(Mode.SYSTEM, Mode.CUSTOM)
    }

    fun defaultMode(context: Context): Mode = defaultMode(model(context))

    private fun defaultMode(model: Model): Mode = when (model) {
        Model.L7 -> Mode.L7
        Model.L6 -> Mode.L6
        Model.CUSTOM -> Mode.SYSTEM
    }

    @Synchronized fun selectModel(context: Context, model: Model) {
        // 先迁移旧 L7 配置；重新选车型自动填入适配方案，但不删除已编辑的文件。
        mode(context)
        val preset = builtin(context, defaultMode(model))
        if (model == Model.CUSTOM && !customExists(context, model)) writeCustom(context, preset, model)
        val selected = if (model == Model.CUSTOM) Mode.CUSTOM else defaultMode(model)
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(MODEL, model.id).putString(modeKey(model), selected.id).commit())
    }

    private fun modeKey(model: Model): String = when (model) {
        Model.L7 -> "mode"
        Model.L6 -> "mode_l6"
        Model.CUSTOM -> "mode_custom"
    }

    @Synchronized fun mode(context: Context): Mode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(modeKey(model(context)), null)?.let { id ->
            return modes(context).firstOrNull { it.id == id } ?: defaultMode(context)
        }
        val selected = if (model(context) == Model.L7) migrateLegacy(context) else defaultMode(context)
        check(prefs.edit().putString(modeKey(model(context)), selected.id).commit())
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
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(modeKey(model(context)), mode.id).commit())
    }

    @Synchronized fun saveCustom(context: Context, template: AudioRoutingTemplate, expectedModel: Model = model(context)) {
        check(model(context) == expectedModel) { "AUDIO_TEMPLATE_MODEL_CHANGED" }
        writeCustom(context, AudioRoutingTemplate.parse(template.toJson()))
        select(context, Mode.CUSTOM)
    }

    @Synchronized fun customInvalid(context: Context): Boolean =
        mode(context) == Mode.CUSTOM && ((context is GalaxyConfigurationContext && model(context).id in context.audioRecovery) ||
            runCatching { readCustom(context) }.isFailure)

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

    private fun customExists(context: Context, targetModel: Model = model(context)): Boolean {
        if (context is GalaxyConfigurationContext) return targetModel.id in context.audio
        val base = file(context, targetModel).baseFile
        // Android 10/11 的 AtomicFile 可从备份恢复，切换方案不能抢先覆盖该备份。
        return base.exists() || File(base.path + ".bak").exists()
    }

    private fun readCustom(context: Context): AudioRoutingTemplate = if (context is GalaxyConfigurationContext)
        AudioRoutingTemplate.parse(context.audio.getValue(model(context).id))
    else file(context).openRead().use { parse(it) }

    private fun file(context: Context, targetModel: Model = model(context)): AtomicFile =
        AtomicFile(File(context.filesDir, when (targetModel) {
            Model.L7 -> "audio-template.json"
            Model.L6 -> "audio-template-l6.json"
            Model.CUSTOM -> "audio-template-custom.json"
        }))

    private fun writeCustom(context: Context, template: AudioRoutingTemplate, targetModel: Model = model(context)) {
        if (context is GalaxyConfigurationContext) {
            context.audio[targetModel.id] = template.toJson()
            context.audioRecovery.remove(targetModel.id)
            context.onChanged?.invoke()
            return
        }
        val target = file(context, targetModel)
        val stream = target.startWrite()
        try {
            stream.write(template.toJson().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
            GalaxyProfiles.changed(context)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }
}

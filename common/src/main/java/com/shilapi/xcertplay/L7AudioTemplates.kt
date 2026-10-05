package com.shilapi.xcertplay

import android.content.Context
import android.util.AtomicFile
import com.shilapi.xcertplay.media.AudioOutputRole
import com.shilapi.xcertplay.media.AudioRoutingTemplate
import java.io.File
import java.io.InputStream

/** 内置资源只读；自定义文件原子替换，切换方案不会覆盖自定义模板。 */
internal object L7AudioTemplates {
    enum class Mode(val id: String) { L7("l7"), BUS("l7-bus"), CUSTOM("custom") }
    private const val PREFS = "l7_audio_templates"
    private const val MODE = "mode"

    @Synchronized fun mode(context: Context): Mode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(MODE, null)?.let { id ->
            return Mode.entries.firstOrNull { it.id == id } ?: Mode.L7
        }
        val choices = mapOf(AudioOutputRole.MEDIA to AirPlayPersistence.loadMediaAudioChannel(context),
            AudioOutputRole.NAVIGATION to AirPlayPersistence.loadNavigationAudioChannel(context),
            AudioOutputRole.ASSISTANT to AirPlayPersistence.loadAssistantAudioChannel(context))
        val default = builtin(context, Mode.L7)
        val selected = if (choices.any { (role, value) -> value != default.choice(role) }) {
            var legacy = builtin(context, Mode.BUS).withBusEnabled(AirPlayPersistence.loadL7AudioBusEnabled(context))
            choices.forEach { (role, value) -> legacy = legacy.withChoice(role, value) }
            writeCustom(context, legacy)
            Mode.CUSTOM
        } else if (AirPlayPersistence.loadL7AudioBusEnabled(context)) Mode.BUS else Mode.L7
        check(prefs.edit().putString(MODE, selected.id).commit())
        return selected
    }

    @Synchronized fun load(context: Context): AudioRoutingTemplate = when (val mode = mode(context)) {
        Mode.CUSTOM -> runCatching { readCustom(context) }.getOrElse { builtin(context, Mode.L7) }
        else -> builtin(context, mode)
    }

    @Synchronized fun select(context: Context, mode: Mode) {
        if (mode == Mode.CUSTOM && !customExists(context)) {
            // 新建自定义草稿沿用当前方案；已有文件一律保留。
            writeCustom(context, load(context))
        }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(MODE, mode.id).commit())
    }

    @Synchronized fun saveCustom(context: Context, template: AudioRoutingTemplate) {
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

    private fun file(context: Context): AtomicFile = AtomicFile(File(context.filesDir, "audio-template.json"))

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

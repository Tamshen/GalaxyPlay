package com.shilapi.xcertplay

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import com.shilapi.xcertplay.host.R
import java.io.File
import java.security.SecureRandom

/** 文件是配置来源；旧偏好仅为现有调用提供兼容镜像，不包含身份或协议。 */
internal class GalaxyProfiles(private val context: Context,
    private val persist: (AtomicFile, String) -> Unit = { target, text -> write(target, text) }) {
    private val folder = File(context.filesDir, "configurations")
    private val selection = AtomicFile(File(folder, "active"))
    private fun file(id: String) = File(folder, "$id.json")
    data class Catalog(val profiles: List<GalaxyProfile>, val unavailable: Int)
    fun list(): List<GalaxyProfile> = catalog().profiles
    fun catalog(): Catalog = synchronized(lock) {
        initialize()
        val results = folder.listFiles().orEmpty().filter { it.extension == "json" }
            .map { runCatching { read(it.nameWithoutExtension) } }
        Catalog(results.mapNotNull { it.getOrNull() }
            .sortedWith(compareBy<GalaxyProfile> { it.id != selectedId() }.thenBy { it.name }), results.count { it.isFailure })
    }
    fun active(): GalaxyProfile = synchronized(lock) { initialize(); read(selectedId()) }
    private fun selectedId() = selection.openRead().use { it.readBytes().toString(Charsets.UTF_8) }.also {
        require(it.matches(Regex("[a-z0-9_]{1,64}")))
    }
    private fun read(id: String): GalaxyProfile {
        val target = file(id)
        return runCatching { parse(target) }.getOrElse {
            // 内容损坏只能恢复上一份有效文件，不能静默覆盖为另一车型默认值。
            val previous = parse(File(target.path + ".previous"))
            persist(AtomicFile(target), previous.json().toString())
            previous
        }.let { profile ->
            check(profile.id == id)
            val vehicle = GalaxyApplicationPreferences.vehicle(profile.configuration)
            if (vehicle == profile.configuration) profile else profile.copy(configuration = vehicle,
                revision = profile.revision + 1, updatedAt = System.currentTimeMillis()).also(::saveFile)
        }
    }
    private fun parse(file: File): GalaxyProfile = AtomicFile(file).openRead().use { stream ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(2048)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(output.size() + count <= GalaxyConfiguration.MAX_BYTES)
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        require(bytes.size <= GalaxyConfiguration.MAX_BYTES)
        GalaxyProfile.parse(bytes.toString(Charsets.UTF_8))
    }
    private fun initialize() {
        if (selection.baseFile.exists() || File(selection.baseFile.path + ".bak").exists()) return
        folder.mkdirs()
        // 首次捕获必须先完成既有音频迁移；不会确认尚未选择的车型。
        L7AudioTemplates.mode(context)
        val labels = AppLocale.wrap(context)
        val initial = GalaxyProfile("current", labels.getString(R.string.profile_migrated), 1,
            System.currentTimeMillis(), GalaxyConfigurationFields.capture(context))
        saveFile(initial)
        persist(selection, initial.id)
    }
    /** 车型是快速模板，直接覆盖当前完整文件；设备身份仍由兼容镜像保护。 */
    fun applyTemplate(model: String): GalaxyProfile = synchronized(lock) {
        require(model in setOf("l7", "l6"))
        val current = refresh()
        save(current.copy(configuration = GalaxyConfigurationFields.factory(context, model)))
    }
    fun draft(model: String, name: String): GalaxyProfile = synchronized(lock) {
        val configuration = if (model == "current") refresh().configuration else GalaxyConfigurationFields.factory(context, model)
        GalaxyProfile(newId(), name, 0, 0, configuration)
    }
    /** 原设置页明确保存后同步整份文件；编辑模态框使用独立 Context，不进入此路径。 */
    fun refresh(): GalaxyProfile = synchronized(lock) {
        val old = active()
        val actual = GalaxyConfigurationFields.capture(context)
        if (actual.json().toString() == old.configuration.json().toString()) return@synchronized old
        val next = old.copy(revision = old.revision + 1, updatedAt = System.currentTimeMillis(), configuration = actual)
        saveFile(next)
        next
    }
    fun save(draft: GalaxyProfile): GalaxyProfile = synchronized(lock) {
        initialize()
        require(draft.name.isNotBlank() && draft.name.length <= 40 && draft.name.none { it.code < 32 })
        if (list().any { it.id != draft.id && it.name.trim().equals(draft.name.trim(), true) }) throw GalaxyProfileNameConflict()
        GalaxyConfigurationFields.validate(draft.configuration)
        // 序列化回读同时验证类型、音频及文件预算；失败不写兼容存储。
        val next = GalaxyProfile.parse(draft.copy(configuration = GalaxyApplicationPreferences.vehicle(draft.configuration), revision = draft.revision + 1,
            updatedAt = System.currentTimeMillis()).json().toString())
        val old = if (file(next.id).exists()) read(next.id) else null
        check(old?.revision == draft.revision || (old == null && draft.revision == 0)) { "CONFIG_EDIT_CONFLICT" }
        require(old != null || folder.listFiles().orEmpty().count { it.extension == "json" } < MAX_PROFILES)
        val active = selectedId() == next.id
        val before = if (active) GalaxyConfigurationFields.capture(context) else null
        try {
            saveFile(next)
            if (active) mirror(next.configuration)
        } catch (error: Exception) {
            if (old != null) runCatching { persist(AtomicFile(file(old.id)), old.json().toString()) }
            else AtomicFile(file(next.id)).delete()
            if (before != null) runCatching { mirror(before) }
            throw error
        }
        if (active) L7DebugLog.refreshConfiguration(context)
        next
    }
    fun select(id: String): GalaxyProfile = synchronized(lock) {
        val old = refresh()
        val next = read(id)
        GalaxyConfigurationFields.validate(next.configuration)
        try { mirror(next.configuration); persist(selection, next.id) }
        catch (error: Exception) { runCatching { mirror(old.configuration); persist(selection, old.id) }; throw error }
        L7DebugLog.refreshConfiguration(context)
        next
    }
    fun restore() = synchronized(lock) { mirror(active().configuration) }
    private fun saveFile(profile: GalaxyProfile) {
        val target = file(profile.id)
        if (target.exists()) persist(AtomicFile(File(target.path + ".previous")), parse(target).json().toString())
        persist(AtomicFile(target), profile.json().toString())
    }
    private fun mirror(configuration: GalaxyConfiguration) {
        mirroring = true
        try {
            GalaxyConfigurationFields.names.forEach { name ->
                val prefs = context.getSharedPreferences(name, 0)
                val edit = prefs.edit()
                prefs.all.keys.filter { GalaxyConfigurationFields.vehicleAllowed(name, it) }.forEach(edit::remove)
                configuration.preferences[name].orEmpty().filterKeys { GalaxyConfigurationFields.vehicleAllowed(name, it) }.forEach { (key, value) -> put(edit, key, value) }
                check(edit.commit()) { "CONFIG_COMPAT_WRITE_FAILED" }
            }
            L7AudioTemplates.Model.entries.forEach { model ->
                // 旧损坏文件保留供核对；沿用原播放器的内置回退，并在配置及界面明确标记。
                if (model.id in configuration.audioRecovery) return@forEach
                val target = AtomicFile(File(context.filesDir, GalaxyConfigurationFields.audioFile(model.id)))
                val original = File(target.baseFile.path + ".unreadable")
                if (target.baseFile.exists() && !original.exists() &&
                    runCatching { target.openRead().use(L7AudioTemplates::parse) }.isFailure)
                    target.baseFile.copyTo(original)
                configuration.audio[model.id]?.let { persist(target, it) } ?: target.delete()
            }
        } finally { mirroring = false }
    }
    companion object {
        private val lock = Any()
        const val MAX_PROFILES = 20
        @Volatile private var mirroring = false
        @Volatile var storageFailed = false
            private set
        private val observers = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private val observerLock = Any()
        private var pending: Runnable? = null
        fun install(context: Context) {
            runCatching { GalaxyProfiles(context).restore() }.onFailure { storageFailed = true }
            GalaxyConfigurationFields.names.forEach { name ->
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (!mirroring && key != null && GalaxyConfigurationFields.allowed(name, key)) changed(context)
                }
                observers += listener
                context.getSharedPreferences(name, 0).registerOnSharedPreferenceChangeListener(listener)
            }
        }
        fun changed(context: Context) {
            if (context is GalaxyConfigurationContext || mirroring || observers.isEmpty()) return
            synchronized(observerLock) {
                pending?.let(handler::removeCallbacks)
                pending = Runnable { runCatching { GalaxyProfiles(context).refresh() }
                    .onSuccess { storageFailed = false; L7DebugLog.refreshConfiguration(context) }
                    .onFailure { storageFailed = true } }
                handler.postDelayed(pending!!, 100)
            }
        }
        private fun newId(): String = "p_" + ByteArray(12).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "${('g'.code + ((it.toInt() and 255) shr 4)).toChar()}${('g'.code + (it.toInt() and 15)).toChar()}" }
        internal fun write(target: AtomicFile, text: String) {
            target.baseFile.parentFile?.mkdirs()
            val stream = target.startWrite()
            try { stream.write(text.toByteArray(Charsets.UTF_8)); target.finishWrite(stream) }
            catch (error: Exception) { target.failWrite(stream); throw error }
        }
        internal fun put(edit: SharedPreferences.Editor, key: String, value: Any?) {
            when (value) {
                null -> edit.remove(key)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is String -> edit.putString(key, value)
                is Set<*> -> edit.putStringSet(key, value.map { it as String }.toSet())
                else -> error("CONFIG_VALUE_TYPE")
            }
        }
    }
}

internal class GalaxyProfileNameConflict : IllegalArgumentException("CONFIG_NAME_CONFLICT")

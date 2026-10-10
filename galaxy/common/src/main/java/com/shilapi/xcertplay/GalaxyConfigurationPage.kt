package com.shilapi.xcertplay

import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import com.shilapi.xcertplay.host.R

/** 只有当前配置；内置车型模板立即覆盖，后续参数编辑仍先写草稿。 */
internal class GalaxyConfigurationPage(private val activity: ComponentActivity,
    private val credentials: () -> Unit = {}, private val changed: () -> Unit) {
    private val repository = GalaxyProfiles(activity)
    private val state = ViewModelProvider(activity)[GalaxyConfigurationState::class.java]
    private var draft: GalaxyConfigurationContext? = null
    private val observers = mutableListOf<android.content.SharedPreferences.OnSharedPreferenceChangeListener>()
    val view: GalaxyConfigurationFrame = GalaxyConfigurationFrame(activity, ::applyTemplate, ::category, ::restore, ::commit)
    val dirty get() = state.dirty
    init {
        if (state.draft == null || !state.dirty) runCatching { repository.refresh() }.onSuccess { current ->
            if (state.draft?.id != current.id || state.draft?.revision != current.revision) state.load(current)
        }
        bind()
    }
    private fun text(id: Int) = activity.getString(id)
    private fun bind() {
        detach()
        val profile = state.draft
        if (profile == null) { view.feedback(text(R.string.profile_load_failed), true); view.commitButton.isEnabled = false; return }
        val context = GalaxyConfigurationContext(activity, profile, editable = true)
        draft = context
        fun update() {
            state.draft = context.profile.copy(configuration = context.configuration())
            updateStatus()
        }
        context.onChanged = ::update
        GalaxyConfigurationFields.names.forEach { space ->
            val observer = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> update() }
            observers += observer
            context.getSharedPreferences(space, 0).registerOnSharedPreferenceChangeListener(observer)
        }
        renderFields()
        updateStatus()
    }
    private fun updateStatus() {
        val profile = state.draft ?: return
        val active = runCatching { repository.active() }.getOrNull()
        val effective = CarPlayBackgroundSession.configuration
        val hint = when {
            dirty -> text(R.string.config_page_unsaved)
            effective != null && (effective.id != active?.id || effective.revision != active?.revision) ->
                text(R.string.template_pending_connection)
            else -> activity.getString(R.string.template_saved, L7AudioModelConfirmation.name(activity, L7AudioTemplates.model(activity)))
        }
        view.feedback(hint)
        view.resetButton.isEnabled = dirty
        view.commitButton.isEnabled = dirty
    }
    private fun renderFields() {
        val context = draft ?: return
        view.fields.removeAllViews()
        view.category(state.group)
        val groups = when (state.group) {
            0 -> listOf(R.string.l7_section_projection)
            1 -> listOf(R.string.automatic_connection, R.string.l7_start_wireless)
            2 -> listOf(R.string.l7_template_title)
            3 -> listOf(R.string.l7_section_projection)
            else -> listOf(R.string.l7_auth_title)
        }
        groups.forEach { group -> GalaxyProfileFieldsView.add(context, view.fields, group,
            if (state.group == 0) setOf("display_scale_percent", "display_fps") else null) }
        if (state.group == 0) {
            val presets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
            L7Components.choice(view.fields, text(R.string.music_buffer), listOf(text(R.string.s_300_ms_default),
                text(R.string.s_500_ms), text(R.string.s_1000_ms_most_stable)),
                presets.indexOf(AirPlayPersistence.loadMediaBufferMillis(context))) {
                AirPlayPersistence.saveMediaBufferMillis(context, presets[it])
            }
            view.fields.addView(L7Components.switchRow(context, text(R.string.l7_bt_media_auto), "",
                AirPlayPersistence.loadBluetoothMediaExclusive(context)) {
                AirPlayPersistence.saveBluetoothMediaExclusive(context, it)
            })
            GalaxyProfileFieldsView.add(context, view.fields, R.string.automatic_connection, setOf("wireless_enabled"))
            view.fields.addView(L7Components.note(activity, text(R.string.config_page_next_connection)))
        }
        if (state.group == 4 && state.draft?.configuration?.audioRecovery?.isNotEmpty() == true)
            view.fields.addView(L7Components.note(activity, text(R.string.profile_audio_recovery), true))
        if (state.group == 4) view.fields.addView(L7Components.actionRow(activity,
            text(R.string.config_page_credentials), text(R.string.config_page_credentials_hint)) { requestLeave(credentials) })
        view.scroll.post { view.scroll.scrollTo(0, state.scroll[state.group] ?: 0) }
    }
    private fun category(index: Int) {
        state.scroll[state.group] = view.scroll.scrollY
        state.group = index
        renderFields()
    }
    private fun restore() {
        if (!dirty) return
        L7Dialogs.builder(activity).setTitle(R.string.profile_discard_title)
            .setMessage(R.string.profile_discard_message)
            .setNegativeButton(R.string.profile_keep_editing, null)
            .setPositiveButton(R.string.profile_discard) { _, _ -> state.original?.let(state::load); bind() }.show()
    }
    fun requestLeave(action: () -> Unit) {
        if (!dirty) { action(); return }
        L7Dialogs.builder(activity).setTitle(R.string.profile_discard_title)
            .setMessage(R.string.profile_discard_message)
            .setNegativeButton(R.string.profile_keep_editing, null)
            .setPositiveButton(R.string.profile_discard) { _, _ -> state.original?.let(state::load); bind(); action() }.show()
    }
    private fun applyTemplate(model: String) {
        val result = runCatching { repository.applyTemplate(model) }
        if (result.isFailure) { view.feedback(text(R.string.profile_save_failed), true); return }
        state.load(result.getOrThrow())
        bind()
        changed()
    }
    private fun commit() {
        val profile = state.draft ?: return
        val result = runCatching { repository.save(profile) }
        if (result.isFailure) { view.feedback(text(R.string.profile_save_failed), true); return }
        state.load(result.getOrThrow())
        bind()
        changed()
    }
    private fun detach() {
        draft?.let { context ->
            context.onChanged = null
            GalaxyConfigurationFields.names.forEachIndexed { index, space ->
                observers.getOrNull(index)?.let { context.getSharedPreferences(space, 0).unregisterOnSharedPreferenceChangeListener(it) }
            }
        }
        observers.clear()
    }
    fun close() { state.scroll[state.group] = view.scroll.scrollY; detach() }
}

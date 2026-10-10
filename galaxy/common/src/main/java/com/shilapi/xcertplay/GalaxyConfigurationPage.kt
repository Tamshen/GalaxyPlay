package com.shilapi.xcertplay

import android.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import com.shilapi.xcertplay.host.R

/** 只有当前配置；车型弹窗确认后覆盖，后续参数编辑仍先写草稿。 */
internal class GalaxyConfigurationPage(private val activity: ComponentActivity,
    private val credentials: () -> Unit = {}, private val changed: () -> Unit) {
    private val repository = GalaxyProfiles(activity)
    private val state = ViewModelProvider(activity)[GalaxyConfigurationState::class.java]
    private var draft: GalaxyConfigurationContext? = null
    private val observers = mutableListOf<android.content.SharedPreferences.OnSharedPreferenceChangeListener>()
    private var templateDialog: AlertDialog? = null
    private var resetDialog: AlertDialog? = null
    private var closed = false
    val view: GalaxyConfigurationFrame = GalaxyConfigurationFrame(activity, ::chooseTemplate, ::category, ::resetCurrent, ::restore, ::commit)
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
        val model = L7AudioTemplates.model(activity)
        val name = GalaxyVehicleTemplates.find(active?.template ?: model.id)?.let { text(it.title) }
            ?: L7AudioModelConfirmation.name(activity, model)
        view.vehicleButton.text = activity.getString(R.string.template_current, name)
        val hint = when {
            dirty -> text(R.string.config_page_unsaved)
            effective != null && (effective.id != active?.id || effective.revision != active?.revision) ->
                text(R.string.template_pending_connection)
            else -> activity.getString(R.string.template_saved, name)
        }
        view.feedback(hint)
        view.resetButton.isEnabled = dirty
        view.commitButton.isEnabled = dirty
    }
    private fun renderFields() {
        val context = draft ?: return
        view.fields.removeAllViews()
        view.category(state.group)
        GalaxyConfigurationSections.add(context, view.fields, state.group,
            credentials = { requestLeave(credentials) })
        if (state.group == 4 && state.draft?.configuration?.audioRecovery?.isNotEmpty() == true)
            view.fields.addView(L7Components.note(activity, text(R.string.profile_audio_recovery), true))
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
    private fun resetCurrent() {
        if (closed || resetDialog?.isShowing == true || templateDialog?.isShowing == true) return
        val profile = state.original ?: return
        val model = L7AudioTemplates.model(activity)
        val name = GalaxyVehicleTemplates.find(profile.template)?.let { text(it.title) }
            ?: L7AudioModelConfirmation.name(activity, model)
        var applied = false
        val dialog = L7Dialogs.builder(activity).setTitle(R.string.config_reset_current)
            .setMessage(activity.getString(R.string.config_reset_message, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.config_reset_confirm, null).create()
        resetDialog = dialog
        dialog.setOnDismissListener { if (resetDialog === dialog) resetDialog = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (closed || applied) return@setOnClickListener
                applied = true
                dialog.dismiss()
                val result = runCatching { repository.resetCurrent(profile) }
                if (result.isFailure) { view.feedback(text(R.string.profile_save_failed), true); return@setOnClickListener }
                state.load(result.getOrThrow())
                bind()
                changed()
            }
        }
        dialog.show()
    }
    private fun chooseTemplate() {
        if (closed || templateDialog?.isShowing == true || resetDialog?.isShowing == true) return
        val entries = GalaxyVehicleTemplates.entries
        var pending = entries.indexOfFirst { it.id == (state.original?.template?.takeUnless { id -> id == "unconfirmed" } ?: L7AudioTemplates.model(activity).id) }
        var applied = false
        val dialog = L7Dialogs.builder(activity).setTitle(R.string.template_choose)
            .setMessage(R.string.template_overwrite)
            .setSingleChoiceItems(entries.map { text(it.title) }.toTypedArray(), pending) { dialog, index ->
                pending = index
                (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
            }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.template_apply, null).create()
        templateDialog = dialog
        dialog.setOnDismissListener { if (templateDialog === dialog) templateDialog = null }
        dialog.setOnShowListener {
            val apply = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            apply.isEnabled = pending >= 0
            apply.setOnClickListener {
                if (closed || applied || pending !in entries.indices) return@setOnClickListener
                applied = true
                dialog.dismiss()
                applyTemplate(entries[pending].id)
            }
            L7Components.styleDialog(dialog)
        }
        dialog.show()
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
    fun close() {
        closed = true
        templateDialog?.dismiss()
        templateDialog = null
        resetDialog?.dismiss()
        resetDialog = null
        state.scroll[state.group] = view.scroll.scrollY
        detach()
    }
}

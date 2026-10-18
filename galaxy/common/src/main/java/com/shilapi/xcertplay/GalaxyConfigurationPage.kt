package com.shilapi.xcertplay

import android.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import com.shilapi.xcertplay.host.R

/** 内置配置只作新文件起点；用户文件明确切换，参数修改先留草稿。 */
internal class GalaxyConfigurationPage(private val activity: ComponentActivity,
    private val changed: () -> Unit) {
    private val repository = GalaxyProfiles(activity)
    private val state = ViewModelProvider(activity)[GalaxyConfigurationState::class.java]
    private var draft: GalaxyConfigurationContext? = null
    private val observers = mutableListOf<android.content.SharedPreferences.OnSharedPreferenceChangeListener>()
    private var templateDialog: AlertDialog? = null
    private var resetDialog: AlertDialog? = null
    private var saveDialog: AlertDialog? = null
    private var closed = false
    private val sharing = lazy { GalaxyConfigurationSharing(activity, { state.original }, { state.draft }, importer = ::importShared) {
        state.load(it); bind(); changed()
    } }
    val view: GalaxyConfigurationFrame = GalaxyConfigurationFrame(activity, ::chooseTemplate, ::category, ::resetCurrent, ::restore, ::commit,
        share = { if (!closed) sharing.value.share() }, paste = { if (!closed) sharing.value.paste() })
    val dirty get() = state.dirty
    init {
        if (state.draft == null || (!state.dirty && state.draft?.revision != 0)) runCatching { repository.refresh() }.onSuccess { current ->
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
        val name = GalaxyConfigurationNames.display(activity, profile.name)
        val creating = profile.revision == 0
        view.vehicleButton.text = activity.getString(R.string.template_current, name)
        view.commitButton.setText(if (creating) R.string.config_file_create else R.string.save)
        val hint = when {
            creating -> text(if (state.sourceTemplate != null) R.string.config_file_builtin_hint else R.string.config_file_new_draft_hint)
            dirty -> text(R.string.config_page_unsaved)
            effective != null && (effective.id != active?.id || effective.revision != active?.revision) ->
                text(R.string.template_pending_connection)
            else -> activity.getString(R.string.template_saved, name)
        }
        view.feedback(hint)
        view.resetButton.isEnabled = dirty
        view.commitButton.isEnabled = true
    }
    private fun renderFields() {
        val context = draft ?: return
        view.fields.removeAllViews()
        view.category(state.group)
        if (state.group == 0) GalaxyConfigurationNameField.add(context, view.fields, context.profile.name)
        GalaxyConfigurationSections.add(context, view.fields, state.group)
        if (state.group == 3 && state.draft?.configuration?.audioRecovery?.isNotEmpty() == true)
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
            .setPositiveButton(R.string.profile_discard) { _, _ -> state.original?.let { state.load(it, state.selected ?: it, state.sourceTemplate) }; bind() }.show()
    }
    fun requestLeave(action: () -> Unit) {
        if (!dirty) { action(); return }
        L7Dialogs.builder(activity).setTitle(R.string.profile_discard_title)
            .setMessage(R.string.profile_discard_message)
            .setNegativeButton(R.string.profile_keep_editing, null)
            .setPositiveButton(R.string.profile_discard) { _, _ -> state.original?.let { state.load(it, state.selected ?: it, state.sourceTemplate) }; bind(); action() }.show()
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
                val result = runCatching {
                    check(state.original == profile)
                    if (profile.revision == 0) {
                        repository.requireCurrent(state.selected!!)
                        profile.copy(configuration = state.sourceTemplate?.let { GalaxyVehicleTemplates.find(it)!!.create(activity) }
                            ?: profile.configuration)
                    }
                    else repository.resetCurrent(profile)
                }
                if (result.isFailure) { view.feedback(text(R.string.profile_save_failed), true); return@setOnClickListener }
                state.load(result.getOrThrow(), if (profile.revision == 0) state.selected!! else result.getOrThrow(), state.sourceTemplate)
                bind()
                if (profile.revision > 0) changed()
            }
        }
        dialog.show()
    }
    private fun chooseTemplate() {
        if (closed || templateDialog?.isShowing == true || resetDialog?.isShowing == true || saveDialog?.isShowing == true) return
        val expected = runCatching { repository.refresh() }.getOrElse {
            view.feedback(text(R.string.profile_load_failed), true); return
        }
        val current = state.draft ?: return
        val catalog = runCatching { repository.catalog() }.getOrElse {
            view.feedback(text(R.string.profile_load_failed), true); return
        }
        templateDialog = GalaxyConfigurationPicker.show(activity, catalog, current, state.sourceTemplate) { choice ->
            if (closed) false else {
                val result = runCatching {
                    if (choice.copy) {
                        repository.requireCurrent(expected)
                        repository.draft("current", current.name).copy(configuration = current.configuration,
                            templateId = current.templateId)
                    } else if (choice.template != null) {
                        repository.requireCurrent(expected)
                        repository.draft(choice.template.id, text(choice.template.title))
                    } else repository.selectCurrent(expected, choice.profile!!.id)
                }
                result.onSuccess {
                    state.load(it, if (it.revision == 0) expected else it, choice.template?.id)
                    bind()
                    if (it.revision > 0) changed()
                }.onFailure { view.feedback(text(R.string.profile_save_failed), true) }
                result.isSuccess
            }
        }.also { dialog -> dialog.setOnDismissListener { if (templateDialog === dialog) templateDialog = null } }
    }
    private fun importShared(expected: GalaxyProfile, value: GalaxyConfigurationShare): GalaxyProfile {
        check(!closed && state.original == expected)
        return if (expected.revision == 0) {
            repository.createAndSelect(state.selected!!, expected.copy(name = value.name,
                configuration = value.configuration, templateId = value.template))
        } else repository.importShared(expected, value)
    }
    private fun commit() {
        if (closed || saveDialog?.isShowing == true || templateDialog?.isShowing == true || resetDialog?.isShowing == true) return
        val profile = state.draft ?: return
        var failure = R.string.profile_save_failed
        saveDialog = GalaxyConfigurationNameField.save(activity,
            GalaxyConfigurationNames.forSave(activity, profile, repository.list().map { it.name }),
            creating = profile.revision == 0, failure = { failure }) { name ->
            if (closed || state.draft != profile) false else {
                val result = runCatching {
                    val value = profile.copy(name = name)
                    if (profile.revision == 0) repository.createAndSelect(state.selected!!, value)
                    else repository.saveCurrent(value)
                }
                result.onSuccess {
                    state.load(it); bind(); changed()
                }.onFailure { failure = if (it is GalaxyProfileNameConflict) R.string.profile_name_conflict else R.string.profile_save_failed }
                result.isSuccess
            }
        }.also { dialog -> dialog.setOnDismissListener { if (saveDialog === dialog) saveDialog = null } }
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
        if (sharing.isInitialized()) sharing.value.close()
        templateDialog?.dismiss()
        templateDialog = null
        saveDialog?.dismiss(); saveDialog = null
        resetDialog?.dismiss()
        resetDialog = null
        state.scroll[state.group] = view.scroll.scrollY
        detach()
    }
}

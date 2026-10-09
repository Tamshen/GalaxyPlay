package com.shilapi.xcertplay

import android.app.AlertDialog
import android.text.InputFilter
import android.text.TextWatcher
import android.text.Editable
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.shilapi.xcertplay.host.R

/** 仅在内存保留草稿，窗口重建不写文件，也不把认证输入放进状态 Bundle。 */
internal class GalaxyProfileEditorState : ViewModel() {
    var original: GalaxyProfile? = null
    var draft: GalaxyProfile? = null
    var group = 0
}

internal object GalaxyProfileEditor {
    fun restore(activity: ComponentActivity, changed: () -> Unit = {}) {
        val state = ViewModelProvider(activity)[GalaxyProfileEditorState::class.java]
        state.draft?.let { show(activity, it, changed) }
    }
    fun show(activity: ComponentActivity, profile: GalaxyProfile, changed: () -> Unit) {
        val state = ViewModelProvider(activity)[GalaxyProfileEditorState::class.java]
        if (state.draft == null) { state.original = profile; state.draft = profile; state.group = 0 }
        val draft = GalaxyConfigurationContext(activity, state.draft!!, editable = true)
        val parent = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val name = EditText(activity).apply {
            hint = activity.getString(R.string.profile_name); setText(state.draft!!.name)
            filters = arrayOf(InputFilter.LengthFilter(40)); setSingleLine()
            setTextColor(activity.getColor(R.color.product_ui_text))
        }
        val feedback = L7Typography.text(activity, "", L7Typography.Role.FEEDBACK)
        feedback.accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        val fields = LinearLayout(draft).apply { orientation = LinearLayout.VERTICAL }
        fun updateState() { state.draft = draft.profile.copy(name = name.text.toString(), configuration = draft.configuration()) }
        draft.onChanged = ::updateState
        val listeners = GalaxyConfigurationFields.names.map { space ->
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateState() }.also {
                draft.getSharedPreferences(space, 0).registerOnSharedPreferenceChangeListener(it)
            }
        }
        name.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateState() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        parent.addView(L7Typography.text(activity, activity.getString(R.string.profile_name), L7Typography.Role.LABEL))
        parent.addView(name)
        lateinit var groupRow: L7SettingRow
        fun render() {
            fields.removeAllViews()
            val group = GalaxyProfileFieldsView.groups[state.group]
            groupRow.setValue(activity.getString(group))
            GalaxyProfileFieldsView.add(draft, fields, group)
        }
        groupRow = L7Components.valueRow(activity, activity.getString(R.string.profile_category),
            activity.getString(GalaxyProfileFieldsView.groups[state.group])) {
            L7Components.select(activity, activity.getString(R.string.profile_category),
                GalaxyProfileFieldsView.groups.map(activity::getString), state.group,
                activity.getString(R.string.profile_view_category)) { state.group = it; render() }
        }
        parent.addView(groupRow); parent.addView(feedback); parent.addView(fields)
        render()
        lateinit var dialog: AlertDialog
        fun discard() {
            updateState()
            if (state.draft?.json()?.toString() == state.original?.json()?.toString()) {
                state.draft = null; state.original = null; dialog.dismiss(); return
            }
            L7Dialogs.builder(activity).setTitle(R.string.profile_discard_title).setMessage(R.string.profile_discard_message)
                .setNegativeButton(R.string.profile_keep_editing, null)
                .setPositiveButton(R.string.profile_discard) { _, _ -> state.draft = null; state.original = null; dialog.dismiss() }.show()
        }
        dialog = L7Dialogs.builder(activity).setTitle(R.string.profile_edit).setMessage(R.string.profile_draft_hint)
            .setView(parent).setOnCloseRequest(::discard)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { discard() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                updateState()
                if (name.text.isBlank()) { name.error = activity.getString(R.string.profile_name_required); name.requestFocus(); return@setOnClickListener }
                val result = runCatching { GalaxyProfiles(activity).save(state.draft!!) }
                if (result.isFailure) {
                    if (result.exceptionOrNull() is GalaxyProfileNameConflict) {
                        name.error = activity.getString(R.string.profile_name_conflict); name.requestFocus()
                        return@setOnClickListener
                    }
                    feedback.text = activity.getString(R.string.profile_save_failed)
                    L7Ui.text(feedback, R.color.product_ui_danger)
                    return@setOnClickListener
                }
                state.draft = null; state.original = null; dialog.dismiss(); changed()
                val active = runCatching { GalaxyProfiles(activity).active().id == result.getOrThrow().id }.getOrDefault(false)
                android.widget.Toast.makeText(activity, if (active) R.string.profile_saved_active else R.string.profile_saved,
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
        val lifecycle = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { if (state.draft != null) updateState(); dialog.dismiss() }
        }
        activity.lifecycle.addObserver(lifecycle)
        dialog.setOnDismissListener {
            draft.onChanged = null
            GalaxyConfigurationFields.names.forEachIndexed { index, space ->
                draft.getSharedPreferences(space, 0).unregisterOnSharedPreferenceChangeListener(listeners[index])
            }
            activity.lifecycle.removeObserver(lifecycle)
        }
        dialog.show()
    }
}

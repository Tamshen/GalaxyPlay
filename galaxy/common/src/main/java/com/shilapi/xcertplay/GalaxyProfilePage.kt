package com.shilapi.xcertplay

import android.content.Context
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.host.R

/** 先显示保存与实际生效的差异，再提供新建、编辑和明确切换。 */
internal object GalaxyProfilePage {
    fun add(context: Context, parent: LinearLayout, refresh: () -> Unit) {
        val activity = context as? ComponentActivity ?: return
        val repository = GalaxyProfiles(context)
        val saved = runCatching { repository.refresh() }.getOrNull()
        if (saved == null) {
            parent.addView(L7SettingRow(context, context.getString(R.string.profile_title)).apply {
                setFeedback(context.getString(R.string.profile_load_failed), error = true)
            }); return
        }
        val effective = CarPlayBackgroundSession.configuration
        fun label(profile: GalaxyProfile) = context.getString(R.string.profile_revision, profile.name, profile.revision)
        L7SettingsSection.add(parent, context.getString(R.string.profile_title),
            description = context.getString(R.string.profile_flow), footer = context.getString(R.string.profile_apply_hint)) { card ->
            card.addView(L7SettingRow(context, context.getString(R.string.profile_saved_current)).apply {
                setValue(label(saved))
                if (GalaxyProfiles.storageFailed) setFeedback(context.getString(R.string.profile_save_failed), true)
                else if (saved.configuration.audioRecovery.isNotEmpty())
                    setFeedback(context.getString(R.string.profile_audio_recovery), true)
            })
            card.addView(L7SettingRow(context, context.getString(R.string.profile_effective)).apply {
                setValue(effective?.let(::label) ?: context.getString(R.string.profile_no_connection))
                if (effective != null && (effective.id != saved.id || effective.revision != saved.revision))
                    setFeedback(context.getString(R.string.profile_pending))
            })
            card.addView(L7Components.actionRow(context, context.getString(R.string.profile_edit),
                context.getString(R.string.profile_edit_current)) { GalaxyProfileEditor.show(activity, saved, refresh) })
            card.addView(L7Components.actionRow(context, context.getString(R.string.profile_switch),
                context.getString(R.string.profile_switch_hint)) { choose(activity, repository, saved, refresh) })
            card.addView(L7Components.actionRow(context, context.getString(R.string.profile_new),
                context.getString(R.string.profile_new_hint)) { create(activity, repository, refresh) })
        }
    }
    private fun choose(activity: ComponentActivity, repository: GalaxyProfiles, current: GalaxyProfile, refresh: () -> Unit) {
        val result = runCatching { repository.catalog() }
        if (result.isFailure) { error(activity); return }
        val catalog = result.getOrThrow()
        val profiles = catalog.profiles
        val builder = L7Dialogs.builder(activity).setTitle(R.string.profile_files)
        if (catalog.unavailable > 0) builder.setMessage(activity.getString(R.string.profile_unavailable_files, catalog.unavailable))
        builder.setItems(profiles.map {
            activity.getString(R.string.profile_revision, it.name, it.revision) + if (it.id == current.id)
                " · ${activity.getString(R.string.profile_saved_current)}" else ""
        }.toTypedArray()) { _, index ->
            val selected = profiles[index]
            L7Dialogs.builder(activity).setTitle(selected.name).setMessage(R.string.profile_switch_hint)
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.profile_edit) { _, _ -> GalaxyProfileEditor.show(activity, selected, refresh) }
                .setPositiveButton(R.string.profile_use) { _, _ ->
                    if (runCatching { repository.select(selected.id) }.isSuccess) {
                        refresh()
                        android.widget.Toast.makeText(activity, R.string.profile_switched, android.widget.Toast.LENGTH_LONG).show()
                    } else error(activity)
                }.show()
        }.setNegativeButton(R.string.cancel, null).show()
    }
    private fun create(activity: ComponentActivity, repository: GalaxyProfiles, refresh: () -> Unit) {
        val options = listOf("l7", "l6", "custom", "current")
        var chosen = 0
        L7Dialogs.builder(activity).setTitle(R.string.profile_new).setMessage(R.string.profile_new_hint)
            .setSingleChoiceItems(listOf(R.string.profile_default_l7, R.string.profile_default_l6,
                R.string.profile_default_custom, R.string.profile_copy_current).map(activity::getString).toTypedArray(), 0) { _, index -> chosen = index }
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.profile_next) { _, _ ->
                val draft = runCatching { repository.draft(options[chosen], activity.getString(R.string.profile_new_name)) }.getOrNull()
                if (draft == null) error(activity) else GalaxyProfileEditor.show(activity, draft, refresh)
            }.show()
    }
    private fun error(context: Context) = android.widget.Toast.makeText(context,
        R.string.profile_load_failed, android.widget.Toast.LENGTH_LONG).show()
}

package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import com.shilapi.xcertplay.host.R

/** 内置项只有默认参数，用户项引用已有文件；选择本身不创建文件。 */
internal object GalaxyConfigurationPicker {
    data class Choice(val template: GalaxyVehicleTemplates.Entry? = null, val profile: GalaxyProfile? = null,
                      val copy: Boolean = false)

    fun show(activity: Activity, catalog: GalaxyProfiles.Catalog, current: GalaxyProfile, sourceTemplate: String?,
             apply: (Choice) -> Boolean): AlertDialog {
        val choices = GalaxyVehicleTemplates.entries.map { Choice(template = it) } +
            catalog.profiles.map { Choice(profile = it) }
        val labels = choices.map { choice ->
            if (choice.template != null) activity.getString(R.string.config_file_builtin, activity.getString(choice.template.title))
            else activity.getString(R.string.config_file_user, GalaxyConfigurationNames.display(activity, choice.profile!!.name))
        }
        var pending = choices.indexOfFirst {
            if (current.revision == 0) sourceTemplate != null && it.template?.id == sourceTemplate else it.profile?.id == current.id
        }
        var applied = false
        val hint = activity.getString(R.string.template_overwrite) + if (catalog.unavailable > 0)
            "\n" + activity.getString(R.string.profile_unavailable_files, catalog.unavailable) else ""
        fun label() = if (choices.getOrNull(pending)?.template != null) R.string.config_file_from_builtin else R.string.profile_use
        val dialog = L7Dialogs.builder(activity).setTitle(R.string.template_choose).setMessage(hint).setFixedHeight()
            .setSingleChoiceItems(labels.toTypedArray(), pending) { value, index ->
                pending = index
                (value as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).apply {
                    isEnabled = true; setText(label())
                }
            }.setNegativeButton(R.string.cancel, null).setNeutralButton(R.string.config_file_copy, null)
            .setPositiveButton(label(), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (!applied && apply(Choice(profile = current, copy = true))) { applied = true; dialog.dismiss() }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = pending >= 0
                setOnClickListener {
                    if (!applied && pending in choices.indices && apply(choices[pending])) {
                        applied = true; dialog.dismiss()
                    }
                }
            }
            L7Components.styleDialog(dialog)
        }
        dialog.show()
        return dialog
    }
}

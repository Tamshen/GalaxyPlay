package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 名称和图标属于应用设置，确认后独立保存，下次连接使用同一份协议外观。 */
internal object GalaxyCarPlayAppearancePage {
    fun add(context: Context, parent: LinearLayout, chooseIcon: () -> Unit) {
        L7SettingsSection.add(parent, context.getString(R.string.galaxy_carplay_return_title),
            footer = context.getString(R.string.galaxy_carplay_return_hint)) { card ->
            lateinit var name: L7SettingRow
            name = L7Components.valueRow(context, context.getString(R.string.galaxy_carplay_return_name),
                GalaxyCarPlayAppearance.label(context)) {
                editName(context) { name.setValue(GalaxyCarPlayAppearance.label(context)) }
            }
            card.addView(name)
            val icon = GalaxyCarPlayAppearance.icon(context)
            val preview = ImageView(context).apply {
                contentDescription = context.getString(R.string.galaxy_carplay_return_preview)
                setImageBitmap(BitmapFactory.decodeByteArray(icon.data, 0, icon.data.size))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            card.addView(preview, LinearLayout.LayoutParams(L7Components.dp(context, 96), L7Components.dp(context, 96)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                topMargin = L7Components.dp(context, 16); bottomMargin = L7Components.dp(context, 16)
            })
            val iconRow = L7Components.actionRow(context, context.getString(R.string.galaxy_carplay_return_import),
                context.getString(R.string.galaxy_carplay_return_crop_hint), click = chooseIcon).apply {
                setValue(context.getString(if (GalaxyCarPlayAppearance.customIcon(context) == null)
                    R.string.galaxy_carplay_return_icon_default else R.string.galaxy_carplay_return_icon_custom))
            }
            card.addView(iconRow)
            card.addView(L7Components.actionRow(context, context.getString(R.string.galaxy_carplay_return_reset_icon)) {
                L7Dialogs.builder(context).setTitle(R.string.galaxy_carplay_return_reset_icon)
                    .setMessage(R.string.galaxy_carplay_return_reset_hint)
                    .setNegativeButton(R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ ->
                        GalaxyCarPlayAppearance.resetIcon(context)
                        val default = GalaxyCarPlayAppearance.icon(context)
                        preview.setImageBitmap(BitmapFactory.decodeByteArray(default.data, 0, default.data.size))
                        iconRow.setValue(context.getString(R.string.galaxy_carplay_return_icon_default))
                    }.show()
            })
        }
    }

    private fun editName(context: Context, saved: () -> Unit) {
        val input = EditText(context).apply {
            setSingleLine(); setText(GalaxyCarPlayAppearance.label(context))
            hint = context.getString(R.string.galaxy_carplay_return_name)
        }
        val dialog = L7Dialogs.builder(context).setTitle(R.string.galaxy_carplay_return_name)
            .setMessage(R.string.galaxy_carplay_return_name_hint).setView(input)
            .setNegativeButton(R.string.cancel, null).setNeutralButton(R.string.galaxy_carplay_return_reset_name) { _, _ ->
                GalaxyCarPlayAppearance.resetLabel(context); saved()
            }.setPositiveButton(R.string.save_details, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val candidate = input.text.toString().trim()
                if (!GalaxyCarPlayAppearance.validLabel(candidate)) {
                    input.error = context.getString(R.string.galaxy_carplay_return_name_invalid)
                } else {
                    runCatching { GalaxyCarPlayAppearance.saveLabel(context, candidate) }.onSuccess {
                        saved(); dialog.dismiss()
                    }.onFailure { input.error = context.getString(R.string.galaxy_carplay_return_save_failed) }
                }
            }
        }
        dialog.show()
    }
}

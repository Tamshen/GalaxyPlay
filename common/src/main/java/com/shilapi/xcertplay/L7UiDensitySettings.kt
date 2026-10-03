package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 大小档位与自定义共用一个值，只有明确保存才更新。 */
internal object L7UiDensitySettings {
    fun add(activity: Activity, parent: LinearLayout) {
        val current = L7UiDensity.value(activity)
        val names = listOf(R.string.l7_ui_small, R.string.l7_ui_medium, R.string.l7_ui_large)
            .map(activity::getString)
        val labels = names.zip(L7UiDensity.presets) { name, dpi -> "$name · $dpi DPI" }
        val selected = L7UiDensity.presets.indexOf(current)
        parent.addView(L7Components.valueRow(activity, activity.getString(R.string.l7_ui_size),
            labels.getOrNull(selected) ?: activity.getString(R.string.l7_ui_custom_value, current)) {
            L7Components.select(activity, activity.getString(R.string.l7_ui_size), labels,
                selected, activity.getString(R.string.save)) { index ->
                apply(activity, L7UiDensity.presets[index])
            }
        })
        parent.addView(L7Components.actionRow(activity, activity.getString(R.string.l7_ui_custom),
            activity.getString(R.string.l7_ui_density_range)) { custom(activity) })
    }

    private fun custom(activity: Activity) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(L7UiDensity.value(activity).toString())
            selectAll()
            hint = activity.getString(R.string.l7_ui_density_range)
        }
        val dialog = L7Dialogs.builder(activity).setTitle(R.string.l7_ui_custom)
            .setMessage(R.string.l7_ui_density_range).setView(input)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val dpi = input.text.toString().toIntOrNull()
                if (dpi == null || dpi !in L7UiDensity.MIN..L7UiDensity.MAX) {
                    input.error = activity.getString(R.string.l7_ui_density_range)
                } else {
                    dialog.dismiss()
                    apply(activity, dpi)
                }
            }
        }
        dialog.show()
    }

    private fun apply(activity: Activity, dpi: Int) {
        if (dpi == L7UiDensity.value(activity)) return
        L7UiDensity.save(activity, dpi)
        // 只重建设置 Activity；投屏宿主在返回时更新覆盖控件，不重建视频 Surface。
        activity.recreate()
    }
}

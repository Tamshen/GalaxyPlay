package com.shilapi.xcertplay

import android.content.Context
import android.widget.LinearLayout
import android.widget.Toast
import com.shilapi.xcertplay.host.R

/** 车型选择独立于音频参数；沿用确认与下次连接应用契约。 */
internal object L7VehicleSettings {
    fun page(context: Context, parent: LinearLayout, profileChanged: (() -> Unit)? = null) {
        val model = L7AudioTemplates.model(context)
        val modelNames = L7AudioTemplates.Model.entries.map { L7AudioModelConfirmation.name(context, it) }
        fun text(id: Int) = context.getString(id)
        fun refresh() { parent.removeAllViews(); page(context, parent, profileChanged) }
        GalaxyProfilePage.add(context, parent, profileChanged ?: ::refresh)
        L7SettingsSection.add(parent, description = text(R.string.l7_vehicle_settings_note)) { card ->
            card.addView(L7Components.valueRow(context, text(R.string.l7_template_model), modelNames[model.ordinal]) {
                L7Components.select(context, text(R.string.l7_template_model), modelNames, model.ordinal,
                    text(R.string.l7_save_next_connection)) { selected ->
                    change(context) {
                        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.entries[selected])
                        L7AudioModelConfirmation.markReviewed(context)
                        refresh()
                    }
                }
            })
            card.addView(L7SettingRow(context, text(R.string.l7_vehicle_profile),
                text(if (model == L7AudioTemplates.Model.CUSTOM) R.string.l7_vehicle_custom_note
                    else R.string.l7_vehicle_preset_note)).apply {
                setValue(text(L7AudioSettings.modeName(L7AudioTemplates.mode(context))))
            })
            val detected = L7AudioModelDetector.detect()
            card.addView(L7Components.valueRow(context, text(R.string.l7_template_detect),
                detected?.let { L7AudioModelConfirmation.name(context, it) } ?: text(R.string.l7_template_detect_unknown)) {
                if (detected == null) Toast.makeText(context,
                    R.string.l7_template_detect_unknown, Toast.LENGTH_LONG).show()
                else L7AudioModelConfirmation.confirm(context, detected, ::refresh)
            })
        }
    }

    private fun change(context: Context, action: () -> Unit) {
        if (runCatching(action).isFailure)
            Toast.makeText(context, R.string.l7_template_save_failed, Toast.LENGTH_LONG).show()
    }
}

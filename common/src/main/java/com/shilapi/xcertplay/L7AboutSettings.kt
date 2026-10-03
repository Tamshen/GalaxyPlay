package com.shilapi.xcertplay

import android.app.Activity
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 版本用只读值，协议用动作入口，许可说明位于分组外部。 */
internal object L7AboutSettings {
    fun add(activity: Activity, parent: LinearLayout, version: String) {
        fun text(id: Int) = activity.getString(id)
        L7SettingsSection.add(parent, text(R.string.l7_section_product)) { card ->
            card.addView(L7SettingRow(activity, text(R.string.diplay), text(R.string.carplay_at_home_in_your_car)))
            card.addView(L7SettingRow(activity, text(R.string.l7_app_version)).apply { setValue(version) })
        }
        L7SettingsSection.add(parent, text(R.string.l7_section_agreement)) { card ->
            card.addView(L7Components.actionRow(activity, text(R.string.l7_agreement_entry),
                text(R.string.l7_agreement_entry_hint)) { L7Agreement.showDetails(activity) })
        }
        L7SettingsSection.add(parent, text(R.string.l7_section_sources),
            footer = text(R.string.receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay) + "\n\n" +
                text(R.string.an_independent_carplay_receiver_for_android_head_units_wir)) { card ->
            card.addView(L7SettingRow(activity, text(R.string.made_possible_by_open_source)).apply {
                setValue(text(R.string.l7_core_source_info))
            })
        }
    }
}

package com.shilapi.xcertplay

import android.widget.LinearLayout

/** header / body / footer 的唯一组合入口；Card 只承载条目。 */
internal object L7SettingsSection {
    fun add(parent: LinearLayout, title: String = "", description: String = "", footer: String = "",
            build: (L7SettingsCard) -> Unit): L7SettingsCard {
        val context = parent.context
        val section = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (title.isNotEmpty()) section.addView(L7Components.sectionTitle(context, title), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = L7Components.dp(context, if (description.isEmpty()) 12 else 4)
        })
        if (description.isNotEmpty()) section.addView(L7Typography.text(context, description, L7Typography.Role.DESCRIPTION),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = L7Components.dp(context, 12) })
        val card = L7SettingsCard(context)
        build(card)
        section.addView(card)
        if (footer.isNotEmpty()) section.addView(L7Typography.text(context, footer, L7Typography.Role.FEEDBACK),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = L7Components.dp(context, 12) })
        parent.addView(section, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = L7Components.dp(context, 24) })
        return card
    }

    /** 独立动作区自己承担内边距，避免改变同卡中条目的文字起点。 */
    fun actions(card: LinearLayout, build: (LinearLayout) -> Unit) {
        card.addView(LinearLayout(card.context).apply {
            orientation = LinearLayout.VERTICAL
            val inset = L7Components.dp(context, 20)
            setPadding(inset, L7Components.dp(context, 16), inset, L7Components.dp(context, 16))
            build(this)
        }, LinearLayout.LayoutParams(-1, -2))
    }
}

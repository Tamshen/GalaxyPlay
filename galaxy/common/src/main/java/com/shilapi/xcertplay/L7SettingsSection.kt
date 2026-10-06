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

    /** 动作按钮独立成组，与卡片外缘对齐；不再用白卡包裹并叠加内边距。 */
    fun actions(parent: LinearLayout, footer: String = "", build: (LinearLayout) -> Unit) {
        parent.addView(LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            build(this)
            if (footer.isNotEmpty()) addView(L7Typography.text(context, footer, L7Typography.Role.FEEDBACK),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = L7Components.dp(context, 12) })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = L7Components.dp(parent.context, 24) })
    }
}

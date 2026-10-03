package com.shilapi.xcertplay

/** 详细功能统一归属设置，旧入口仍可跳转到对应分类。 */
internal object L7Routes {
    val settings = setOf("settings", "settings-auth", "settings-connection", "settings-display",
        "settings-audio", "settings-general", "settings-permissions",
        "settings-diagnostics", "settings-about")
    private val aliases = mapOf("connection" to "settings-connection", "diagnostics" to "settings-diagnostics",
        "settings-debug" to "settings-diagnostics", "about" to "settings-about")
    private val roots = setOf("home", "wireless-recovery")
    fun normalize(page: String): String = aliases[page] ?: if (page in settings || page in roots) page else "home"
    fun navigation(page: String) = normalize(page).let { if (it in settings) "settings" else it }
    fun back(page: String) = normalize(page).let { if (it in settings && it != "settings") "settings" else "home" }
    fun destination(current: String, selected: String, previousSettings: String): String =
        if (selected != "settings") normalize(selected)
        else if (normalize(current) in settings) "settings"
        else normalize(previousSettings).takeIf { it in settings } ?: "settings"
}

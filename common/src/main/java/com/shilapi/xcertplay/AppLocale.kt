// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Activity
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.content.Context
import android.content.res.Configuration
import com.shilapi.xcertplay.host.R
import java.util.Locale

/** 仅提供中英文；Android 13 起使用系统应用语言，旧系统使用持久化配置。 */
object AppLocale {
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val SIMPLIFIED_CHINESE = "zh"
    val ALL = listOf(SYSTEM, ENGLISH, SIMPLIFIED_CHINESE)

    private const val PREFS = "diplay"
    private const val KEY_LANGUAGE = "app_language"

    private const val KEY_MIGRATED = "app_language_platform_migrated"

    fun preference(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            return if (locales.isEmpty) SYSTEM else locales[0].language.takeIf { it in ALL } ?: SYSTEM
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, SYSTEM)?.takeIf { it in ALL } ?: SYSTEM
    }

    fun save(context: Context, language: String) {
        require(language in ALL)
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                locale(language)?.let { LocaleList(it) } ?: LocaleList.getEmptyLocaleList()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_MIGRATED, true).remove(KEY_LANGUAGE).apply()
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LANGUAGE, language).apply()
        }
    }

    /** Android 13 起以系统应用语言为准，已移除的旧语言回到跟随系统。 */
    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val manager = context.getSystemService(LocaleManager::class.java)
            val current = manager.applicationLocales
            val removedLanguage = !current.isEmpty && current[0].language !in ALL
            if (removedLanguage) manager.applicationLocales = LocaleList.getEmptyLocaleList()
            if (!prefs.getBoolean(KEY_MIGRATED, false)) {
                val previous = locale(prefs.getString(KEY_LANGUAGE, SYSTEM) ?: SYSTEM)
                // 已有系统选择优先，清理旧语言时也不恢复更早的应用偏好。
                if (!removedLanguage && manager.applicationLocales.isEmpty && previous != null) {
                    manager.applicationLocales = LocaleList(previous)
                }
                prefs.edit().putBoolean(KEY_MIGRATED, true).remove(KEY_LANGUAGE).apply()
            }
            return context
        }
        val locale = locale(preference(context)) ?: return context
        // 只覆盖语言，昼夜、密度和字体继续随系统更新，避免长期运行的日志服务固定旧主题。
        val configuration = Configuration().apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(configuration)
    }

    fun showPicker(activity: Activity) {
        var selected = ALL.indexOf(preference(activity)).coerceAtLeast(0)
        if (activity.resources.getBoolean(R.bool.config_l7_product_ui)) {
            L7Components.select(activity, activity.getString(R.string.language_app_language),
                ALL.map { displayName(activity, it) }, selected, activity.getString(R.string.language_apply)) { index ->
                save(activity, ALL[index])
                if (Build.VERSION.SDK_INT < 33) activity.recreate()
            }
            return
        }
        L7Dialogs.builder(activity)
            .setTitle(R.string.language_app_language)
            .setSingleChoiceItems(ALL.map { displayName(activity, it) }.toTypedArray(), selected) { _, index ->
                selected = index
            }
            .setPositiveButton(R.string.language_apply) { _, _ ->
                val next = ALL[selected]
                if (next != preference(activity)) {
                    save(activity, next)
                    // Android 13 起由 LocaleManager 自动重建页面。
                    if (Build.VERSION.SDK_INT < 33) activity.recreate()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /** 语言名称使用各自原文，只有“跟随系统”随当前语言翻译。 */
    fun displayName(context: Context, language: String): String = when (language) {
        SYSTEM -> context.getString(R.string.language_system_default)
        ENGLISH -> "English"
        SIMPLIFIED_CHINESE -> "简体中文"
        else -> language
    }

    private fun locale(language: String): Locale? = when (language) {
        ENGLISH -> Locale.ENGLISH
        SIMPLIFIED_CHINESE -> Locale.SIMPLIFIED_CHINESE
        else -> null
    }
}

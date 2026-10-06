package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** 关于页区分接收核心与车机适配参考，完整声明和许可原文支持离线查看。 */
internal object L7OpenSourceSettings {
    fun add(parent: LinearLayout) {
        val context = parent.context
        L7SettingsSection.add(parent, context.getString(R.string.l7_sources_receiver)) { card ->
            card.addView(L7Components.actionRow(context, context.getString(R.string.l7_source_diplay_name),
                context.getString(R.string.l7_source_diplay_description)) {
                showProject(context, R.string.l7_source_diplay_name, R.string.l7_source_version_diplay,
                    R.string.l7_source_diplay_url, R.string.l7_source_diplay_details)
            }.apply {
                setValue(context.getString(R.string.l7_source_version_diplay))
            })
            card.addView(L7Components.actionRow(context, context.getString(R.string.l7_source_geely_name),
                context.getString(R.string.l7_source_geely_description)) {
                showProject(context, R.string.l7_source_geely_name, R.string.l7_source_geely_version,
                    R.string.l7_source_geely_url, R.string.l7_source_geely_details)
            }.apply {
                setValue(context.getString(R.string.l7_source_geely_version))
            })
            card.addView(L7Components.actionRow(context, context.getString(R.string.l7_licenses_title),
                context.getString(R.string.l7_licenses_hint)) { showLicenses(context) })
        }
    }

    private fun showProject(context: Context, name: Int, version: Int, url: Int, details: Int) {
        val projectUrl = context.getString(url)
        val document = context.getString(details, projectUrl)
        val text = L7Typography.text(context, document, L7Typography.Role.DESCRIPTION).apply {
            setTextIsSelectable(true)
        }
        L7Dialogs.builder(context).setTitle("${context.getString(name)} · ${context.getString(version)}").setView(text)
            .setNeutralButton(R.string.l7_source_open_project) { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(projectUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }.onFailure {
                    Toast.makeText(context, R.string.l7_source_browser_unavailable, Toast.LENGTH_LONG).show()
                }
            }
            .setPositiveButton(R.string.close, null).show()
    }

    private fun showLicenses(context: Context) {
        val document = runCatching {
            fun read(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }
            val noticePath = if (context.resources.configuration.locales[0].language == "en")
                "galaxyplay-third-party-notices.en.txt" else "third-party/NOTICE.md"
            val notice = read(noticePath)
                .replace(Regex("(?m)^<a id=\"[^\"]+\"></a>\\s*$"), "")
                .replace(Regex("(?m)^#+\\s+"), "")
                .replace(Regex("\\[([^]]+)]\\(([^)]+)\\)"), "$1（$2）")
                .replace("`", "")
            val licenses = context.assets.list("third-party/licenses").orEmpty()
                .filter { it.endsWith(".txt") }.sorted()
            buildString {
                append(notice)
                licenses.forEach { name ->
                    // 原文不翻译、不删节，正文与仓库中的许可文件同步打包。
                    append("\n\n────────\n").append(name).append("\n\n")
                    append(read("third-party/licenses/$name"))
                }
            }
        }.getOrElse { context.getString(R.string.l7_licenses_load_failed) }
        val text = L7Typography.text(context, document, L7Typography.Role.DESCRIPTION).apply {
            setTextIsSelectable(true)
        }
        L7Dialogs.builder(context).setTitle(R.string.l7_licenses_title).setView(text)
            .setPositiveButton(R.string.close, null).show()
    }
}

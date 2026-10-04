package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.shilapi.xcertplay.host.R
import java.security.MessageDigest

/** 同意只对当前正文有效，独立于应用版本；不记录设备或手机标识。 */
internal object L7Agreement {
    const val VERSION = "2026-10-03"
    private const val PREFERENCES = "l7_agreement"
    private var cachedDocument: String? = null
    private var cachedDigest: String? = null
    @Volatile private var revoked = false

    @Synchronized fun document(context: Context): String = cachedDocument ?: context.assets
        .open("l7-first-use-agreement.md").bufferedReader().use { it.readText() }
        .also { cachedDocument = it }

    /** 英文仅切换展示译文，同意状态继续绑定同一份中文协议原文。 */
    fun displayDocument(context: Context): String = if (context.resources.configuration.locales[0].language == "en") {
        context.assets.open("l7-first-use-agreement.en.md").bufferedReader().use { it.readText() }
    } else document(context)

    @Synchronized fun digest(context: Context): String = cachedDigest ?: MessageDigest.getInstance("SHA-256")
        .digest(document(context).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        .also { cachedDigest = it }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun accepted(context: Context): Boolean = !revoked && runCatching {
        preferences(context).getString("accepted_digest", null) == digest(context)
    }.getOrDefault(false)

    fun canUse(context: Context): Boolean =
        !context.resources.getBoolean(R.bool.config_l7_product_ui) || accepted(context)

    fun accept(context: Context): Boolean {
        val saved = preferences(context).edit().putString("accepted_digest", digest(context))
            .putString("accepted_version", VERSION).putLong("accepted_at", System.currentTimeMillis()).commit()
        if (saved) revoked = false
        return saved
    }

    fun revoke(context: Context): Boolean {
        // 先关闭内存闸门，阻止停止会话期间迟到的授权或重连回调。
        revoked = true
        RemoteLogUpload.cancel()
        return preferences(context).edit().clear().commit()
    }

    fun require(activity: Activity): Boolean {
        if (L7AppExit.exiting || activity.isFinishing || activity.isDestroyed) return false
        if (canUse(activity)) return true
        showGate(activity)
        return false
    }

    fun showGate(activity: Activity) {
        activity.startActivity(Intent(activity, L7AgreementActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        activity.finish()
    }

    fun showDetails(activity: Activity) {
        activity.startActivity(Intent(activity, L7AgreementActivity::class.java))
    }
}

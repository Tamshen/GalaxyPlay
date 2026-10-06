package com.shilapi.xcertplay

import android.content.Context

/** 核心只保留启动入口；产品认证缓存和自动连接保护由 Galaxy 持有。 */
internal object GalaxyStartupPolicy {
    private val authentication = GalaxyAuthenticationBootstrap()

    fun ensure(context: Context) = authentication.ensure(context)
    fun reload(context: Context) = authentication.reload(context)
    fun saveAutoConnect(context: Context, enabled: Boolean) = L7StartupGuard.setEnabled(context, enabled)
}

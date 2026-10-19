package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import com.shilapi.xcertplay.hud.AmapNavigationProtocol
import com.shilapi.xcertplay.hud.CarPlayNavigationSnapshot
import java.io.Closeable

internal interface L7NavigationPort : Closeable {
    fun initialize()
    fun start()
    fun guidance(value: CarPlayNavigationSnapshot)
    fun stop()
    fun invalidate() {}
}

/** 按高德车机标准广播发布；发送返回不代表系统接收或 HUD 显示成功。 */
internal class GalaxyAmapNavigation(context: Context) : L7NavigationPort {
    private val app = context.applicationContext
    @Volatile private var valid = true

    override fun initialize() = Unit
    override fun start() = synchronized(owners) {
        if (!valid) return@synchronized
        owner = this
        send(AmapNavigationProtocol.state(AmapNavigationProtocol.STARTED))
    }
    override fun guidance(value: CarPlayNavigationSnapshot) = synchronized(owners) {
        if (valid && owner === this && value.active) send(AmapNavigationProtocol.guidance(value))
    }
    override fun stop() = synchronized(owners) {
        // 新会话已开始后，旧会话的迟到清理不能结束新导航。
        if (owner === this) {
            send(AmapNavigationProtocol.state(AmapNavigationProtocol.STOPPED))
            owner = null
        }
    }
    override fun invalidate() { valid = false }
    override fun close() = synchronized(owners) {
        invalidate()
        if (owner === this) owner = null
    }
    private fun send(fields: Map<String, Any>) {
        val intent = Intent(AmapNavigationProtocol.ACTION)
        fields.forEach { (key, value) ->
            when (value) {
                is Int -> intent.putExtra(key, value)
                is String -> intent.putExtra(key, value)
                else -> error("AMAP_FIELD_TYPE")
            }
        }
        app.sendBroadcast(intent)
    }
    private companion object {
        val owners = Any()
        var owner: GalaxyAmapNavigation? = null
    }
}

package com.shilapi.xcertplay

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.io.Closeable

internal data class BluetoothMediaState(val connected: Boolean, val playing: Boolean?)

/** 只操作 A2DP Sink；不连接手机、不修改连接策略，也不涉及 HFP 或蓝牙总开关。 */
internal interface L7BluetoothMediaPort : Closeable {
    fun open(ready: () -> Unit, changed: () -> Unit): Boolean
    fun state(address: String): BluetoothMediaState
    fun disconnect(address: String): Boolean
}

/** Android 11 隐藏接口按运行时能力调用；权限和方法拒绝交给上层降级，不绕过限制。 */
internal class AndroidBluetoothMediaPort(private val context: Context) : L7BluetoothMediaPort {
    private var adapter: BluetoothAdapter? = null
    private var profileId: Int? = null
    private var proxy: BluetoothProfile? = null
    private var receiver: BroadcastReceiver? = null
    @Volatile private var closed = false

    override fun open(ready: () -> Unit, changed: () -> Unit): Boolean {
        // 服务与权限查询也可能抛异常，全部放到 guard 管理的 open 路径中降级。
        val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        adapter = bluetooth
        if (!bluetooth.isEnabled) return false
        // 动态取得平台定义，隐藏 API 不可见时不猜测其他 profile。
        val id = BluetoothProfile::class.java.getField("A2DP_SINK").getInt(null)
        profileId = id
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, incoming: BluetoothProfile) {
                if (closed || profile != id) {
                    bluetooth.closeProfileProxy(profile, incoming)
                    return
                }
                proxy = incoming
                ready()
            }
            override fun onServiceDisconnected(profile: Int) {
                if (profile == id && !closed) { proxy = null; changed() }
            }
        }
        val filter = IntentFilter(CONNECTION_CHANGED).apply { addAction(PLAYING_CHANGED) }
        val events = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // 广播只触发重新查询，不能凭外部广播内容认定设备或执行断开。
                if (!closed) changed()
            }
        }
        ContextCompat.registerReceiver(context, events, filter, ContextCompat.RECEIVER_EXPORTED)
        receiver = events
        return bluetooth.getProfileProxy(context, listener, id)
    }

    override fun state(address: String): BluetoothMediaState {
        val current = proxy ?: error("profile_unavailable")
        val device = target(current, address) ?: return BluetoothMediaState(false, null)
        val playing = runCatching {
            current.javaClass.getMethod("isAudioPlaying", BluetoothDevice::class.java)
                .invoke(current, device) as? Boolean
        }.getOrNull()
        return BluetoothMediaState(true, playing)
    }

    override fun disconnect(address: String): Boolean {
        val current = proxy ?: return false
        val device = target(current, address) ?: return false
        return current.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
            .invoke(current, device) as? Boolean == true
    }

    private fun target(profile: BluetoothProfile, address: String): BluetoothDevice? =
        profile.connectedDevices.singleOrNull { it.address.equals(address, ignoreCase = true) }

    override fun close() {
        closed = true
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        val current = proxy
        proxy = null
        val id = profileId
        if (current != null && id != null) runCatching { adapter?.closeProfileProxy(id, current) }
    }

    private companion object {
        const val CONNECTION_CHANGED = "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
        const val PLAYING_CHANGED = "android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED"
    }
}

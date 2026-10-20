package com.shilapi.xcertplay.network

import android.content.Context
import java.util.concurrent.TimeUnit

/** 只使用已有的本机 root 维护通道；不启用 ADB，不尝试提权，不输出 shell 原文。 */
internal object GalaxyScanServiceClient {
    fun request(context: Context, action: GalaxyScanAction): GalaxyScanState = try {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        GalaxyP2pLoopback.open(deadline, { false }).use {
            GalaxyScanServiceProtocol.parse(it.run(GalaxyScanServiceProtocol.command(context.packageCodePath, action)))
        }
    } catch (_: Exception) { GalaxyScanState.UNKNOWN }
}

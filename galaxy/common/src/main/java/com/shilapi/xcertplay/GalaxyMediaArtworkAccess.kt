package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.net.Uri

/** 原厂媒体服务转交的是 URI，读取授权不会随 Binder 字段自动转交给 Flyme 图片加载器。 */
internal object GalaxyMediaArtworkAccess {
    private data class Grants(val packages: MutableSet<String> = hashSetOf(), val callers: MutableSet<Int> = hashSetOf())
    private val granted = LinkedHashMap<Uri, Grants>(16, 0.75f, true)
    // 大卡片与系统底栏分别读取封面，媒体服务的授权不会自动传递给它们。
    private val readers = listOf(
        L7VendorServiceProbe.PACKAGE,
        "com.ecarx.sdk.openapi",
        "com.flyme.auto.mediacontrol",
        "com.android.systemui"
    )

    @Synchronized fun grant(context: Context, artwork: Uri, transferId: Int?, report: (String) -> Unit = L7DebugLog::record) {
        // 仅接受本应用封面 provider，不替外部内容转授权；清理时由 L7MediaArtwork 统一撤销。
        if (artwork.scheme != "content" || artwork.authority != "${context.packageName}.media-artwork") return
        for (name in readers) {
            if (granted[artwork]?.packages?.contains(name) == true) continue
            try {
                context.packageManager.getApplicationInfo(name, 0)
                context.grantUriPermission(name, artwork, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                entry(artwork).packages.add(name)
                report("MediaCenter: artworkGrant transferId=${transferId ?: "none"} " +
                    "coverKey=${L7MediaArtworkProvider.diagnosticKey(artwork)} result=GRANTED target=$name")
            } catch (error: Exception) {
                report("MediaCenter: artworkGrant transferId=${transferId ?: "none"} " +
                    "result=FAILED target=$name exceptionType=${error.javaClass.simpleName}")
            }
        }
    }

    /** 固定读取方和 Binder 读取方共享成功记录，失败保留下一次请求的重试机会。 */
    @Synchronized fun grantCaller(context: Context, artwork: Uri, caller: Int): Int {
        if (artwork.scheme != "content" || artwork.authority != "${context.packageName}.media-artwork") return 0
        if (granted[artwork]?.callers?.contains(caller) == true) return 0
        var failures = 0
        try {
            val packages = context.packageManager.getPackagesForUid(caller) ?: return 0
            for (name in packages) {
                if (name == context.packageName || granted[artwork]?.packages?.contains(name) == true) continue
                try {
                    context.grantUriPermission(name, artwork, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    entry(artwork).packages.add(name)
                } catch (_: Exception) { failures++ }
            }
            if (failures == 0 && packages.isNotEmpty()) entry(artwork).callers.add(caller)
        } catch (_: Exception) { failures++ }
        return failures
    }

    /** 撤销和缓存失效在同一把锁下，不能把已撤销的权限继续判为成功。 */
    @Synchronized fun revoke(context: Context, artwork: Uri) {
        try { context.revokeUriPermission(artwork, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        finally { granted.remove(artwork) }
    }

    private fun entry(uri: Uri): Grants {
        val value = granted.getOrPut(uri) { Grants() }
        // 正常仅四张封面；额外限制异常生命周期中的进程级缓存。
        while (granted.size > 64) granted.remove(granted.keys.first())
        return value
    }
}

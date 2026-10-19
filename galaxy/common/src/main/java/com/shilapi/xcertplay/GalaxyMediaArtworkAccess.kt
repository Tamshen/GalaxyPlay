package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.net.Uri

/** 原厂媒体服务转交的是 URI，读取授权不会随 Binder 字段自动转交给 Flyme 图片加载器。 */
internal object GalaxyMediaArtworkAccess {
    // 大卡片与系统底栏分别读取封面，媒体服务的授权不会自动传递给它们。
    private val readers = listOf(
        L7VendorServiceProbe.PACKAGE,
        "com.ecarx.sdk.openapi",
        "com.flyme.auto.mediacontrol",
        "com.android.systemui"
    )

    fun grant(context: Context, artwork: Uri, transferId: Int?, report: (String) -> Unit = L7DebugLog::record) {
        // 仅接受本应用封面 provider，不替外部内容转授权；清理时由 L7MediaArtwork 统一撤销。
        if (artwork.scheme != "content" || artwork.authority != "${context.packageName}.media-artwork") return
        for (name in readers) {
            try {
                context.packageManager.getApplicationInfo(name, 0)
                context.grantUriPermission(name, artwork, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                report("MediaCenter: artworkGrant transferId=${transferId ?: "none"} " +
                    "coverKey=${L7MediaArtworkProvider.diagnosticKey(artwork)} result=GRANTED target=$name")
            } catch (error: Exception) {
                report("MediaCenter: artworkGrant transferId=${transferId ?: "none"} " +
                    "result=FAILED target=$name exceptionType=${error.javaClass.simpleName}")
            }
        }
    }
}

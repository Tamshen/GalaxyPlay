package com.shilapi.xcertplay

import android.graphics.Bitmap
import io.nayuki.qrcodegen.QrCode

/** 只生成二维码；保留整数像素、白底和完整静区供手机拍照识别。 */
internal object GalaxyConfigurationQr {
    const val MAX_CODE_LENGTH = 2000
    fun encode(code: String): Bitmap {
        require(code.length <= MAX_CODE_LENGTH)
        val qr = QrCode.encodeText(code, QrCode.Ecc.MEDIUM)
        val modules = qr.size + 8
        val scale = (768 / modules).coerceAtLeast(1)
        val size = modules * scale
        val pixels = IntArray(size * size) { index ->
            if (qr.getModule(index % size / scale - 4, index / size / scale - 4))
                android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}

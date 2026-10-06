package com.shilapi.xcertplay.media

import android.view.Surface

/** 仅由独立调试进程调用 NDK；不接管正式 CarPlay 解码器。 */
object GalaxyNativeCodecProbe {
    interface Listener {
        fun stage(value: Int)
        fun decoder(name: String)
    }
    init { System.loadLibrary("galaxy_codec_probe") }
    external fun decode(name: String, mime: String, surface: Surface, csd: Array<ByteArray>,
        packets: Array<ByteArray>, times: LongArray, listener: Listener): LongArray
}

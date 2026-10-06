package com.shilapi.xcertplay

import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider

/** 保留 FileProvider 的私有目录与授权校验，只记录文件打开结果，不读取图片或调用者标识。 */
class L7MediaArtworkProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val key = diagnosticKey(uri)
        val own = Binder.getCallingUid() == android.os.Process.myUid()
        return try {
            (super.openFile(uri, mode) ?: throw java.io.FileNotFoundException()).also {
                L7DebugLog.record("MediaCenter: artworkRead coverKey=$key result=OPENED callerOwn=$own readOnly=${mode == "r"}")
            }
        } catch (error: Exception) {
            L7DebugLog.record("MediaCenter: artworkRead coverKey=$key result=FAILED callerOwn=$own exceptionType=${error.javaClass.simpleName}")
            throw error
        }
    }

    internal companion object {
        fun diagnosticKey(uri: Uri): String = uri.lastPathSegment
            ?.takeIf { it.matches(Regex("[0-9a-f]{8}-[0-9a-f-]{27}\\.jpg")) }
            ?.take(8) ?: "unknown"
    }
}

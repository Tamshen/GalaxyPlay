package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.AtomicFile
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.host.R
import java.io.ByteArrayOutputStream
import java.io.File

/** CarPlay 返回入口的外观独立保存，沿用原名称键与图标文件，不改变配对身份。 */
internal object GalaxyCarPlayAppearance {
    const val DEFAULT_LABEL = "回到车机"
    const val CUSTOM_LABEL = "galaxy_oem_label_custom"
    private const val MAX_ICON_BYTES = 5 * 1024 * 1024
    private val iconLock = Any()
    private fun preferences(context: Context) = context.getSharedPreferences("xcertplay_airplay", 0)
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "airplay-icon.png"))

    fun label(context: Context): String {
        val prefs = preferences(context)
        val stored = prefs.getString("oem_label", null)?.trim()
        // 博越参考的旧默认值处理：未显式自定义的 BYD 随升级替换，其余用户名称保留。
        return stored?.takeIf { validLabel(it) &&
            (prefs.getBoolean(CUSTOM_LABEL, false) || !it.equals("BYD", ignoreCase = true)) }
            ?: context.getString(R.string.galaxy_carplay_return_default)
    }

    fun validLabel(value: String) = value.isNotBlank() && value.codePointCount(0, value.length) <= 32 &&
        value.none { Character.isISOControl(it) }

    fun saveLabel(context: Context, value: String) {
        val candidate = value.trim()
        require(validLabel(candidate))
        check(preferences(context).edit().putString("oem_label", candidate)
            .putBoolean(CUSTOM_LABEL, true).commit())
    }

    fun resetLabel(context: Context) {
        check(preferences(context).edit().remove("oem_label").remove(CUSTOM_LABEL).commit())
    }

    fun icon(context: Context): AirPlayIcon {
        customIcon(context)?.let { return it }
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val drawable = requireNotNull(context.getDrawable(R.drawable.ic_galaxy_carplay_return)).mutate()
        drawable.setBounds(0, 0, 256, 256)
        drawable.draw(Canvas(bitmap))
        val bytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        bitmap.recycle()
        return AirPlayIcon(256, 256, bytes)
    }

    fun customIcon(context: Context): AirPlayIcon? = synchronized(iconLock) { runCatching {
        val input = file(context).openRead()
        input.use { stream ->
            require(stream.channel.size() <= MAX_ICON_BYTES)
            decode(stream.readBytes())
        }
    }.getOrNull() }

    fun decode(bytes: ByteArray): AirPlayIcon? {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        if (bytes.size !in 8..MAX_ICON_BYTES || !bytes.take(8).toByteArray().contentEquals(png)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..1024 || bounds.outWidth != bounds.outHeight) return null
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        decoded.recycle()
        return AirPlayIcon(bounds.outWidth, bounds.outHeight, bytes)
    }

    fun saveIcon(context: Context, bytes: ByteArray) = synchronized(iconLock) {
        require(decode(bytes) != null)
        val target = file(context)
        val output = target.startWrite()
        try { output.write(bytes); target.finishWrite(output) }
        catch (error: Exception) { target.failWrite(output); throw error }
    }

    fun resetIcon(context: Context) = synchronized(iconLock) { file(context).delete() }
}

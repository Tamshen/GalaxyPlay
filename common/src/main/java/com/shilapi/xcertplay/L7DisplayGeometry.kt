package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Point
import android.view.WindowManager
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 按用户确认的面板规格换算毫米值；Android 逻辑 DPI 不参与物理尺寸计算。 */
internal object L7DisplayGeometry {
    private const val PANEL_WIDTH_PIXELS = 1440
    private const val PANEL_HEIGHT_PIXELS = 1920
    private const val DIAGONAL_INCHES = 13.2
    private val millimetersPerPixel = DIAGONAL_INCHES * 25.4 /
        hypot(PANEL_WIDTH_PIXELS.toDouble(), PANEL_HEIGHT_PIXELS.toDouble())

    val panelSize: AirPlayPhysicalSizeMm get() = resolve(
        PANEL_WIDTH_PIXELS, PANEL_HEIGHT_PIXELS, PANEL_WIDTH_PIXELS, PANEL_HEIGHT_PIXELS)

    @Suppress("DEPRECATION")
    fun forViewport(context: Context, width: Int, height: Int): AirPlayPhysicalSizeMm {
        val real = Point()
        runCatching { context.getSystemService(WindowManager::class.java)?.defaultDisplay?.getRealSize(real) }
        // 显示服务不可读时使用已确认的 L7 面板像素，不用历史最大窗口或用户毫米设置。
        if (real.x <= 0 || real.y <= 0) real.set(PANEL_WIDTH_PIXELS, PANEL_HEIGHT_PIXELS)
        return resolve(width, height, real.x, real.y)
    }

    fun resolve(width: Int, height: Int, displayWidth: Int, displayHeight: Int): AirPlayPhysicalSizeMm {
        require(width > 0 && height > 0 && displayWidth > 0 && displayHeight > 0)
        val landscape = displayWidth > displayHeight
        val panelWidth = if (landscape) PANEL_HEIGHT_PIXELS else PANEL_WIDTH_PIXELS
        val panelHeight = if (landscape) PANEL_WIDTH_PIXELS else PANEL_HEIGHT_PIXELS
        // 按实际容器换算系统遮挡后的区域；浮动侧栏和编码画布缩放不改变占屏面积。
        return AirPlayPhysicalSizeMm(
            (panelWidth * millimetersPerPixel * width.coerceAtMost(displayWidth) / displayWidth).roundToInt().coerceAtLeast(1),
            (panelHeight * millimetersPerPixel * height.coerceAtMost(displayHeight) / displayHeight).roundToInt().coerceAtLeast(1),
        )
    }
}

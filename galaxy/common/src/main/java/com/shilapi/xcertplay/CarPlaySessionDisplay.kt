package com.shilapi.xcertplay

/** 保存协商画布与启动窗口；产品临时扩缩窗只调整视口，旋转或显式画布布局变化才重建。 */
internal data class CarPlaySessionDisplay(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val hideTopBar: Boolean,
    val hideBottomBar: Boolean,
    // 窗口比较使用未缩放的尺寸，独立于投屏分辨率设置。
    val windowWidth: Int,
    val windowHeight: Int,
    val viewAreas: CarPlayViewAreas? = null,
)

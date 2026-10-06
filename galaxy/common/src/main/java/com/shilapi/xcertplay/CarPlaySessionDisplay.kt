package com.shilapi.xcertplay

/** 保存协商画布与启动窗口；环视缩窗不改变画布，真正旋转或扩窗才重建会话。 */
internal data class CarPlaySessionDisplay(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val hideTopBar: Boolean,
    val hideBottomBar: Boolean,
    // 窗口比较使用未缩放的尺寸，独立于投屏分辨率设置。
    val windowWidth: Int,
    val windowHeight: Int,
)

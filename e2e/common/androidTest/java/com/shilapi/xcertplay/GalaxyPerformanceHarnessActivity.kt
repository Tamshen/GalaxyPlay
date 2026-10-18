package com.shilapi.xcertplay

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.widget.FrameLayout
import android.widget.TextView

/** 独立测试 APK 的可见窗口，不向产品包加入测试入口或修改用户协议状态。 */
class GalaxyPerformanceHarnessActivity : Activity() {
    lateinit var content: FrameLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(savedInstanceState)
        GalaxyUiFrameMonitor.install(application)
        content = FrameLayout(this).apply { setBackgroundColor(Color.DKGRAY) }
        content.addView(TextView(this).apply { text = "Android performance checks"; setTextColor(Color.WHITE) })
        setContentView(content)
    }
}

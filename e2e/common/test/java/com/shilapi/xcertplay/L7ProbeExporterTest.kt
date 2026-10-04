package com.shilapi.xcertplay

import androidx.activity.ComponentActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class L7ProbeExporterTest {
    class Host : ComponentActivity() {
        // 与实际宿主一致：字段初始化时注册导出器，必须允许 Context 尚未绑定。
        internal val exporter = L7ProbeExporter(this)
    }
    @Test fun activityCanCreateExporterBeforeContextIsAttached() {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        assertNull(controller.get().exporter.pendingId)
        controller.pause().stop().destroy()
    }
}

package com.shilapi.xcertplay.hud

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BydClusterMapPauseTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val release = CountDownLatch(1)

    @After
    fun tearDown() {
        release.countDown()
        BydClusterMapPause.streamControl = null
        BydClusterMapPause.clusterMapShown = false
    }

    @Test
    fun legacySettingDoesNotStartBydAdbReadsOrBlockInitializationOnL7() {
        val reading = CountDownLatch(1)
        // 旧 BYD 开关为真也不能在 L7 上读取车辆接口。
        BydClusterMapPause.readMode = {
            reading.countDown()
            release.await()
            null
        }
        BydOutputSettings.setClusterStreamPause(context, true)
        BydClusterMapPause.clusterMapShown = true
        BydClusterMapPause.streamControl = {}
        BydClusterMapPause.initialize(context)
        assertFalse("L7 不应启动 BYD ADB 读取", reading.await(2, TimeUnit.SECONDS))

        val initialized = CountDownLatch(1)
        thread { BydClusterMapPause.initialize(context); initialized.countDown() }

        assertTrue("L7 初始化不应被 BYD 读取阻塞", initialized.await(1, TimeUnit.SECONDS))
    }
}

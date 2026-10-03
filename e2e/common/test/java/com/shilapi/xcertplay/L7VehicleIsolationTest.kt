package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.hud.BydOutputSettings
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class L7VehicleIsolationTest {
    @Test fun legacyBydSettingsCannotEnableVehicleCapabilitiesOnL7() {
        val context = RuntimeEnvironment.getApplication()
        // 覆盖安装可能保留上游开关，不能因此声明轮速或驻车视频能力。
        context.getSharedPreferences("diplay_byd_outputs", Context.MODE_PRIVATE).edit()
            .putBoolean("navigation_enabled", true)
            .putBoolean("cluster_stream_pause", true)
            .putBoolean("battery_to_iphone", true)
            .putBoolean("wheel_speed_to_iphone", true)
            .putBoolean("video_while_parked", true)
            .commit()
        AirPlayPersistence.saveClusterMapEnabled(context, true)

        assertFalse(BydOutputSettings.available(context))
        assertFalse(BydOutputSettings.enabled(context))
        assertFalse(BydOutputSettings.clusterStreamPause(context))
        assertFalse(BydOutputSettings.batteryToIphone(context))
        assertFalse(BydOutputSettings.wheelSpeedToIphone(context))
        assertFalse(BydOutputSettings.videoWhileParked(context))
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(context))
    }
}

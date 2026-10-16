package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Socket

/** 监听替换可在线程启动前关闭 socket；初始化失败不得逃出工作线程。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class AirPlayControlCancellationTest {
    @Test fun closedSocketBeforeControlStartsStillClosesOnce() {
        var ended = 0
        var released = 0
        val socket = Socket().apply { close() }
        val session = AirPlaySession(socket,
            AirPlayConfig("synthetic", "synthetic", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "synthetic"), PairingStore(), null,
            object : AirPlaySessionListener {
                override fun onSessionEnded(session: AirPlaySession) { ended++ }
            }, object : AirPlayMediaHandler {
                override fun onSessionClosed(session: AirPlaySession) { released++ }
            })
        session.javaClass.getDeclaredMethod("runControl").apply { isAccessible = true }.invoke(session)
        session.close()
        assertTrue(session.isClosed)
        assertEquals(1, ended)
        assertEquals(1, released)
    }
}

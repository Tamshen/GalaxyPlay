package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlaySession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class MediaCommandQueueTest {
    @Test fun stalledWriteRejectsExcessCommandsAndOldQueueCannotWriteIntoReplacement() {
        withController { controller, _ ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val old = mock(AirPlaySession::class.java); val next = mock(AirPlaySession::class.java)
            `when`(old.sendMediaChecked(anyInt())).thenAnswer {
                entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); true
            }
            ReflectionHelpers.setField(controller, "activeSession", old)
            val reasons = java.util.concurrent.CopyOnWriteArrayList<String>()
            try {
                assertTrue(controller.sendMediaButton(1, old))
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                val accepted = (1..1000).count { controller.sendMediaButton(3, old) { _, reason -> reasons.add(reason) } }
                assertTrue(accepted > 0); assertTrue(accepted < 1000)
                assertTrue(reasons.contains("QUEUE_FULL"))
                ReflectionHelpers.setField(controller, "activeSession", next)
                release.countDown()
                val executor = ReflectionHelpers.getField<ExecutorService>(controller, "touchExecutor")
                executor.submit { }.get(2, TimeUnit.SECONDS)
                verify(old, times(1)).sendMediaChecked(anyInt())
                verify(next, never()).sendMediaChecked(anyInt())
                assertTrue(reasons.contains("STALE_SESSION"))
                assertTrue(controller.sendMediaButton(3, next))
                executor.submit { }.get(2, TimeUnit.SECONDS)
                verify(next).sendMediaChecked(3)
            } finally { release.countDown() }
        }
    }
}

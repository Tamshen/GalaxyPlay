package com.shilapi.xcertplay

import android.content.Intent
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class VehicleSteeringObserverTest {
    private val context = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        VehicleSteeringInputLog.close()
        assertTrue(AsyncDiagnosticLog.awaitIdle(2000))
        // Robolectric 每个用例更换应用目录，重置仅属于前一个测试目录的文件目标。
        ReflectionHelpers.getStaticField<SessionLogFile?>(L7SteeringDiagnostics::class.java, "target")?.close()
        ReflectionHelpers.setStaticField(L7SteeringDiagnostics::class.java, "target", null)
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.L6)
        L7Agreement.accept(context)
        L7SteeringDiagnostics.initialize(context)
        L7SteeringDiagnostics.store.clear()
    }
    @After fun cleanup() { VehicleSteeringInputLog.close() }
    private fun send() {
        context.sendBroadcast(Intent(L7SteeringWheel.ACTION).putExtra("type", 2))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun disconnectedBroadcastHasOneObservationAndIsIncludedInPersistentLogs() {
        L7SteeringDiagnostics.initialize(context)
        SteeringListening.controller.start("l6")
        send()
        val events = L7SteeringDiagnostics.store.snapshot().events
        assertEquals(1, events.count { it.stage == "OBSERVE_ONLY" && it.source == "vehicle-broadcast" })
        assertTrue(events.none { it.stage == "VOICE_QUEUED" || it.stage == "CHANNEL_WRITTEN" })
        assertTrue(AsyncDiagnosticLog.awaitIdle(2000))
        val file = context.filesDir.resolve("logs/steering.log")
        assertTrue(file.exists())
        assertTrue(file.readText().contains("model=l6"))
        assertTrue(file.readText().contains("OBSERVE_ONLY"))
        assertTrue("steering.log" in SessionLogFile.REPORT_NAMES)
    }

    @Test fun revocationStopsReceiverAndReinitializationAfterConsentRestoresIt() {
        L7Agreement.revoke(context)
        send()
        assertTrue(L7SteeringDiagnostics.store.snapshot().events.isEmpty())
        L7Agreement.accept(context)
        L7SteeringDiagnostics.initialize(context)
        SteeringListening.controller.start("l6")
        send()
        assertEquals(1, L7SteeringDiagnostics.store.snapshot().events.count { it.stage == "OBSERVE_ONLY" })
    }

    @Test fun l7BroadcastIsNotObservedUntilListenerIsExplicitlyEnabled() {
        L7AudioTemplates.selectModel(context, L7AudioTemplates.Model.L7)
        send()
        assertTrue(L7SteeringDiagnostics.store.snapshot().events.isEmpty())
        SteeringListening.controller.start("l7")
        send()
        assertEquals(1, L7SteeringDiagnostics.store.snapshot().events.count { it.stage == "OBSERVE_ONLY" })
    }
}

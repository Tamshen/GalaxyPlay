package com.shilapi.xcertplay

import android.content.Intent
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class L7SteeringWheelTest {
    private val context = RuntimeEnvironment.getApplication()
    private var connected = true
    private var assistant = false
    private var presses = 0
    private val wheel = L7SteeringWheel(context, { connected }, { assistant }) { presses++; true }

    @Test fun longPressInvokesVoiceAndShortPressOnlyHandlesActiveAssistant() {
        wheel.start()
        try {
            send(4)
            assertEquals(0, presses)
            send(3)
            assertEquals(1, presses)
            assistant = true
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(301))
            send(4)
            assertEquals(2, presses)
        } finally { wheel.close() }
    }

    @Test fun duplicateAndUnmappedButtonsNeverGenerateExtraPhoneCommands() {
        wheel.start()
        try {
            send(1); send(2); send(87); send(88)
            assertEquals(0, presses)
            send(3); send(3)
            assertEquals(1, presses)
        } finally { wheel.close() }
    }

    @Test fun waitingSessionAndClosedReceiverCannotWakePhone() {
        wheel.start()
        connected = false
        send(3)
        assertEquals(0, presses)
        connected = true
        wheel.close()
        send(3)
        assertEquals(0, presses)
    }

    @Test fun broadcastAndStandardVoiceKeyShareDebounceWindow() {
        wheel.start()
        try {
            send(3)
            assertTrue(wheel.onVoiceKey())
            assertEquals(1, presses)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(301))
            assertTrue(wheel.onVoiceKey())
            assertEquals(2, presses)
        } finally { wheel.close() }
        assertFalse(wheel.onVoiceKey())
    }

    private fun send(type: Int) {
        context.sendBroadcast(Intent(L7SteeringWheel.ACTION).putExtra("type", type))
        shadowOf(Looper.getMainLooper()).idle()
    }
}

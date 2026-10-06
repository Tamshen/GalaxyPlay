package com.shilapi.xcertplay.media

import android.media.AudioManager
import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29, 30], manifest = org.robolectric.annotation.Config.NONE)
class TelephonyAudioModeTest {
    @Test fun failedRestoreRetriesAndLateOldSessionReleaseCannotRestoreNewMode() {
        val audio = mock(AudioManager::class.java)
        var current = AudioManager.MODE_NORMAL
        var reject = true
        `when`(audio.mode).thenAnswer { current }
        doAnswer { call ->
            val next = call.getArgument<Int>(0)
            if (!reject || next != AudioManager.MODE_NORMAL) current = next
            null
        }.`when`(audio).mode = anyInt()
        val old = TelephonyAudioMode(audio) {}; val next = TelephonyAudioMode(audio) {}
        val id = AudioStreamId(100, "telephony")
        old.acquire(id)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, current)
        assertFalse(old.release(id))
        next.acquire(id)
        reject = false
        old.close()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, current)
        assertTrue(next.release(id))
        assertEquals(AudioManager.MODE_NORMAL, current)
        next.close()
    }
    @Test fun foreignCallModeIsNeverOverwrittenAndReadExceptionRetainsRetry() {
        val audio = mock(AudioManager::class.java)
        `when`(audio.mode).thenReturn(AudioManager.MODE_IN_CALL)
        val mode = TelephonyAudioMode(audio) {}
        val id = AudioStreamId(100, "telephony")
        mode.acquire(id)
        assertFalse(mode.release(id))
        verify(audio, never()).mode = anyInt()
        `when`(audio.mode).thenThrow(SecurityException()).thenReturn(AudioManager.MODE_NORMAL)
        assertFalse(mode.release(id))
        assertTrue(mode.release(id))
        mode.close()
    }
}

package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 经 ActivityResultRegistry 的真实保存／重建／结果交付，检查没有再次申请或丢失结果。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class L7VpnConsentLifecycleTest {
    class Host : ComponentActivity() {
        var ready = 0
        var launches = 0
        var attempt: String? = null
        internal val failures = mutableListOf<L7VpnConsent.Failure>()
        private val launcher: ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            gate.returned(it.resultCode)
        }
        private val gate: L7VpnConsent = L7VpnConsent(
            { if (granted) null else Intent("test.vpn.consent") },
            { launches++; launcher.launch(it) }, { _, _, _ -> }, { ready++ }, failures::add,
        )

        override fun onCreate(state: Bundle?) {
            super.onCreate(state)
            if (wireless) return
            attempt = L7VpnConsent.savedAttempt(state, false) ?: "synthetic-attempt"
            gate.restoreWaiting(L7VpnConsent.savedAttempt(state, false))
            gate.request()
        }

        override fun onSaveInstanceState(state: Bundle) {
            gate.saveWaiting(state, if (wireless) null else attempt)
            super.onSaveInstanceState(state)
        }

        override fun onDestroy() { gate.dispose(); super.onDestroy() }
    }

    @Before fun reset() { granted = false; wireless = false }
    @After fun restore() { granted = false; wireless = false }

    @Test fun restoredRegistryDeliversOriginalGrantExactlyOnceWithoutAnotherLaunch() {
        val old = Robolectric.buildActivity(Host::class.java).setup()
        val request = shadowOf(old.get()).nextStartedActivityForResult
        val state = Bundle()
        old.pause().saveInstanceState(state).stop().destroy()
        val next = Robolectric.buildActivity(Host::class.java).create(state).start().resume()
        try {
            assertEquals(0, next.get().launches)
            assertEquals("synthetic-attempt", next.get().attempt)
            granted = true
            assertTrue(next.get().activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, null))
            next.get().activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, null)
            assertEquals(1, next.get().ready)
            assertEquals(0, old.get().ready)
            assertTrue(next.get().failures.isEmpty())
            val finished = Bundle(); next.pause().saveInstanceState(finished)
            assertNull(L7VpnConsent.savedAttempt(finished, false))
        } finally { next.stop().destroy() }
    }

    @Test fun switchingTransportWhileRecreatedRejectsOriginalWiredGrant() {
        val old = Robolectric.buildActivity(Host::class.java).setup()
        val request = shadowOf(old.get()).nextStartedActivityForResult
        val state = Bundle(); old.pause().saveInstanceState(state).stop().destroy()
        wireless = true; granted = true
        val next = Robolectric.buildActivity(Host::class.java).create(state).start().resume()
        try {
            next.get().activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, null)
            assertEquals(0, next.get().ready)
            assertEquals(0, next.get().launches)
        } finally { next.pause().stop().destroy() }
    }

    private companion object {
        var granted = false
        var wireless = false
    }
}

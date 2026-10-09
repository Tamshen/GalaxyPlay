package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.media.AndroidMediaSink
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
@LooperMode(LooperMode.Mode.PAUSED)
class GalaxySurfaceRetirementTest {
    private fun host() = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
    private fun invoke(host: CarPlayHostActivity, name: String, vararg args: Any?) {
        host.javaClass.declaredMethods.single { it.name == name }.apply { isAccessible = true }.invoke(host, *args)
    }
    private fun owners(host: CarPlayHostActivity) = ReflectionHelpers.getField<MutableMap<Surface, MutableMap<AndroidMediaSink, Long>>>(host, "surfaceOwners")
    private fun deferred(owner: AndroidMediaSink, callbacks: MutableList<() -> Unit>) {
        val placeholder = mock(Surface::class.java)
        doAnswer { callbacks += it.getArgument<() -> Unit>(1); null }.`when`(owner).detachSurface(any(Surface::class.java) ?: placeholder, any<() -> Unit>() ?: {})
    }

    @Test fun allOwnersMustAcknowledgeAndDuplicateOrLateCallbacksCannotDoubleRelease() {
        val host = host(); val surface = mock(Surface::class.java); val texture = mock(SurfaceTexture::class.java)
        val a = mock(AndroidMediaSink::class.java); val b = mock(AndroidMediaSink::class.java)
        val callbacks = mutableListOf<() -> Unit>(); deferred(a, callbacks); deferred(b, callbacks)
        owners(host)[surface] = mutableMapOf(a to 1L, b to 2L)
        invoke(host, "retireVideoSurface", surface, texture, true)
        invoke(host, "retireVideoSurface", surface, texture, true)
        assertEquals(2, callbacks.size)
        verify(surface, never()).release()
        callbacks[0](); callbacks[0](); shadowOf(Looper.getMainLooper()).idle()
        verify(surface, never()).release()
        callbacks[1](); shadowOf(Looper.getMainLooper()).idle()
        verify(surface, times(1)).release(); verify(texture, times(1)).release()
        callbacks[1](); shadowOf(Looper.getMainLooper()).idle()
        verify(surface, times(1)).release()
        assertTrue(owners(host).isEmpty())
        assertTrue(ReflectionHelpers.getField<Set<Surface>>(host, "retiringSurfaces").isEmpty())
    }

    @Test fun oldOwnerDetachCannotEraseAReplacementAttachment() {
        val host = host(); val surface = mock(Surface::class.java); val owner = mock(AndroidMediaSink::class.java)
        val callbacks = mutableListOf<() -> Unit>(); deferred(owner, callbacks)
        ReflectionHelpers.setField(host, "sink", owner)
        invoke(host, "attachSurface", surface)
        invoke(host, "detachVideoOwner", owner)
        invoke(host, "attachSurface", surface)
        val epoch = owners(host).getValue(surface).getValue(owner)
        callbacks.single()(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(epoch, owners(host).getValue(surface).getValue(owner))
    }

    @Test fun frameworkHolderSurfaceIsDetachedWithoutApplicationRelease() {
        val host = host(); val surface = mock(Surface::class.java); val owner = mock(AndroidMediaSink::class.java)
        val callbacks = mutableListOf<() -> Unit>(); deferred(owner, callbacks)
        owners(host)[surface] = mutableMapOf(owner to 1L)
        invoke(host, "retireVideoSurface", surface, null, false)
        verify(surface, never()).release()
        callbacks.single()(); shadowOf(Looper.getMainLooper()).idle()
        verify(surface, never()).release()
        assertTrue(ReflectionHelpers.getField<Set<Surface>>(host, "retiringSurfaces").isEmpty())
    }
}

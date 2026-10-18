package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import com.shilapi.xcertplay.host.R
import ecarx.xsf.mediacenter.session.AppSourceInfo
import ecarx.xsf.mediacenter.session.EasMediaController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class GalaxyOemSourceRegistrationTest {
    class Api(val controller: EasMediaController?) {
        fun getEasMediaController(): EasMediaController? = controller
    }
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun item(name: String, source: Int = 6) = AppSourceInfo.Builder().packageName(name)
        .appName("synthetic").iconPath("synthetic.icon").sourceTypeList(intArrayOf(source))
        .priorityLevel(5).supportMultipleOpen(true).build()
    private fun register(api: Any, source: Int = 6) =
        GalaxyOemSourceRegistration.ensure(context, api, api.javaClass, source) { true }

    @Test fun runtimeSdkReceivesOwnNameIconPackageAndBothCarplaySources() {
        val foreign = item("synthetic.foreign")
        val controller = EasMediaController().apply { configuration.add(foreign) }
        assertEquals("ADDED", register(Api(controller)))
        assertSame(foreign, controller.configuration.first())
        val own = controller.configuration.last()
        assertEquals(context.packageName, own.packageName)
        assertEquals(context.getString(R.string.app_name), own.appName)
        assertEquals("android.resource://${context.packageName}/${context.applicationInfo.icon}", own.iconPath)
        assertArrayEquals(intArrayOf(6, 13), own.sourceTypeList)
        assertEquals(2, own.priorityLevel)
        assertTrue(own.isRunning)
        assertEquals("PRESENT", register(Api(controller)))
        assertEquals(1, controller.writes)
    }

    @Test fun existingOwnEntryRefreshesBrandAndAccumulatesLocalWithoutLosingCapabilities() {
        val controller = EasMediaController().apply { configuration.add(item(context.packageName, 13)) }
        assertEquals("UPDATED", register(Api(controller)))
        assertEquals("UPDATED", register(Api(controller), 0))
        val own = controller.configuration.single()
        assertArrayEquals(intArrayOf(13, 6, 0), own.sourceTypeList)
        assertEquals(5, own.priorityLevel)
        assertTrue(own.isSupportMultipleOpen)
        assertEquals("PRESENT", register(Api(controller)))
        assertEquals(2, controller.writes)
    }

    @Test fun initiallyLocalChannelOnlyDeclaresItsVerifiedLocalSource() {
        val controller = EasMediaController().apply { configuration.add(item("synthetic.foreign")) }
        assertEquals("ADDED", register(Api(controller), 0))
        assertArrayEquals(intArrayOf(0), controller.configuration.last().sourceTypeList)
    }

    @Test fun missingOrRejectedDirectoryDoesNotThrowOrPretendToBeRegistered() {
        assertEquals("NO_CONTROLLER", register(Api(null)))
        assertEquals("UNAVAILABLE_METHOD", register(Any()))
        val controller = EasMediaController().apply { configuration.add(item("synthetic.foreign")); rejectWrite = true }
        assertEquals("UNAVAILABLE_CALL", register(Api(controller)))
        assertEquals(1, controller.configuration.size)
    }

    @Test fun nonserializableBitmapEntryIsNotWrittenBack() {
        val value = AppSourceInfo.Builder().packageName("synthetic.foreign").sourceTypeList(intArrayOf(6))
            .iconRes(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)).build()
        val controller = EasMediaController().apply { configuration.add(value) }
        assertEquals("CONFIG_UNAVAILABLE", register(Api(controller)))
        assertEquals(0, controller.writes)
    }

    @Test fun ignoredServiceWriteAndStaleOwnerRemainUnconfirmed() {
        val controller = EasMediaController().apply { configuration.add(item("synthetic.foreign")); ignoreWrite = true }
        val api = Api(controller)
        assertEquals("READBACK_MISMATCH", register(api))
        assertEquals("STALE", GalaxyOemSourceRegistration.ensure(context, api, Api::class.java, 6) { false })
        assertEquals(1, controller.writes)
    }
}

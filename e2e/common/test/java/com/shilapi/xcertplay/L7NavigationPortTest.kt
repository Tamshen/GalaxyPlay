package com.shilapi.xcertplay

import android.os.Binder
import android.os.IInterface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 使用实际 Android Binder 核对 SDK 服务身份读取，不加载厂商 SDK 或绑定实车服务。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30], manifest = Config.NONE)
class L7NavigationPortTest {
    class ServiceHolder(var connection: IInterface?) {
        fun service(): IInterface? = connection
    }

    private fun port(holder: ServiceHolder? = null): L7ReflectiveNavigation {
        val port = L7ReflectiveNavigation(RuntimeEnvironment.getApplication())
        if (holder != null) {
            L7ReflectiveNavigation::class.java.getDeclaredField("serviceInstance").apply {
                isAccessible = true; set(port, holder)
            }
            L7ReflectiveNavigation::class.java.getDeclaredField("serviceGetter").apply {
                isAccessible = true; set(port, ServiceHolder::class.java.getMethod("service"))
            }
        }
        return port
    }

    @Test fun absentServiceNeverReportsReady() {
        val port = port()
        assertNull(port.connectionToken())
        assertFalse(port.ready())
        assertNull(this.port(ServiceHolder(null)).connectionToken())
    }

    @Test fun serviceTokenIsTheCurrentLiveBinder() {
        val first = Binder()
        val holder = ServiceHolder(IInterface { first })
        val port = port(holder)
        assertSame(first, port.connectionToken())
        assertTrue(port.ready())
        val next = Binder()
        holder.connection = IInterface { next }
        assertSame(next, port.connectionToken())
        assertNotSame(first, port.connectionToken())
    }

    @Test fun deadBinderNeverReportsReadyAndCloseClearsService() {
        val dead = object : Binder() { override fun isBinderAlive() = false }
        val holder = ServiceHolder(IInterface { dead })
        val port = port(holder)
        assertNull(port.connectionToken())
        assertFalse(port.ready())
        holder.connection = IInterface { Binder() }
        assertNotNull(port.connectionToken())
        port.close()
        assertNull(port.connectionToken())
    }
}

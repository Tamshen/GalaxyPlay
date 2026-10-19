package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class L7VendorSdkTest {
    private val name = GalaxyOemMediaContractTest.Modern::class.java.name
    private val requested = mutableListOf<String>()
    private val providers = mutableMapOf<String, ClassLoader>()
    private fun loader(visible: Boolean) = object : ClassLoader(javaClass.classLoader) {
        override fun loadClass(className: String, resolve: Boolean): Class<*> {
            if (className == name && !visible) throw ClassNotFoundException(className)
            return super.loadClass(className, resolve)
        }
    }
    private fun context(framework: ClassLoader) = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getClassLoader() = framework
        override fun createPackageContext(packageName: String, flags: Int): Context {
            requested.add(packageName)
            assertEquals(Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY, flags)
            val candidate = providers[packageName] ?: throw PackageManager.NameNotFoundException(packageName)
            return object : ContextWrapper(this) { override fun getClassLoader() = candidate }
        }
    }
    @Test fun visibleFrameworkSdkHasPriorityAndDoesNotLoadMusicApplications() {
        val framework = loader(true)
        val selected = L7VendorSdk(context(framework)).load(name)
        assertEquals("APP_OR_FRAMEWORK", selected.source)
        assertSame(framework, selected.loader)
        assertTrue(requested.isEmpty())
    }

    @Test fun incompleteFrameworkSdkFallsBackToVerifiedHandCarSdk() {
        val bad = loader(true); val good = loader(true)
        providers["com.geely.linkhmi"] = good
        val checked = mutableListOf<ClassLoader>()
        val selected = L7VendorSdk(context(bad)).load(name) {
            checked.add(it)
            if (it === bad) throw NoSuchMethodException("不兼容音乐回调")
        }
        assertEquals("com.geely.linkhmi", selected.source)
        assertEquals(listOf(bad, good), checked)
        assertEquals(listOf("com.geely.linkhmi"), requested)
    }

    @Test fun allIncompatibleSdksReportContractMismatchInsteadOfUsingPartialClient() {
        providers["com.geely.linkhmi"] = loader(true)
        val failure = runCatching {
            L7VendorSdk(context(loader(false))).load(name) {
                throw IllegalArgumentException("SDK_METHOD_SIGNATURE_MISMATCH")
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test fun diagnosticsIdentifyRejectedCandidateAndVerifiedFallbackWithoutExceptionPayload() {
        val logs = mutableListOf<String>()
        val bad = loader(true); val good = loader(true)
        providers["com.geely.linkhmi"] = good
        GalaxySteeringSetupLog(logs::add).use { diagnostics ->
            val selected = L7VendorSdk(context(bad), diagnostics).load(name) {
                if (it === bad) throw ClassNotFoundException("private-payload-and-device-name")
            }
            assertSame(good, selected.loader)
        }
        assertTrue(logs.any { "result=SDK_REJECTED" in it && "provider=APP_OR_FRAMEWORK" in it && "exceptionType=ClassNotFoundException" in it })
        assertTrue(logs.last().contains("result=SDK_SELECTED provider=com.geely.linkhmi"))
        assertFalse(logs.any { "private-payload" in it })
    }
}

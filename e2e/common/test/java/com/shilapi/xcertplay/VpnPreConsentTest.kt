package com.shilapi.xcertplay

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 车机没有 VPN 授权对话框时，有线连接会永远停在授权这一步。VpnPreConsent 用车机自身已获批准的
 * adb 放行 ACTIVATE_VPN；本测试覆盖放行命令、放行可达性判定，以及"放行后系统是否真的不再要授权页"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 30])
class VpnPreConsentTest {
    private class FakeAdb(private val reachable: Boolean, private val output: String? = "") :
        VpnPreConsent.Adb {
        val commands = mutableListOf<String>()
        override fun connect() = reachable
        override fun shell(command: String): String? {
            commands += command
            return output
        }
    }

    /**
     * 放行命令必须作用于本应用自身的包名，否则放行的是别的应用。
     */
    @Test fun allowCommandTargetsOwnPackage() {
        val command = VpnPreConsent.allowCommand("com.ecarx.carplay")
        assertTrue(command.contains("appops set com.ecarx.carplay ACTIVATE_VPN allow"))
    }

    /**
     * adb 可达时执行放行命令，并作用于本应用包名。
     */
    @Test fun reachableAdbRunsTheAllowCommand() {
        val adb = FakeAdb(reachable = true)
        assertTrue(VpnPreConsent.grant(RuntimeEnvironment.getApplication(), adb))
        assertEquals(1, adb.commands.size)
        assertTrue(adb.commands.single().contains("com.ecarx.carplay"))
    }

    /**
     * adb 不可达时不执行任何命令，也不能声称已放行。
     */
    @Test fun unreachableAdbDoesNotRunAnyCommand() {
        val adb = FakeAdb(reachable = false)
        assertFalse(VpnPreConsent.grant(RuntimeEnvironment.getApplication(), adb))
        assertTrue(adb.commands.isEmpty())
    }

    /**
     * 放行成功且系统不再返回授权页时才可继续连接。
     */
    @Test fun grantedVpnIsAcceptedWithoutConsentScreen() {
        val gate = VpnPreConsentGate(preConsent = { true }, prepare = { null })
        assertTrue(gate.satisfied())
    }

    /**
     * adb 放行无效时 prepare 仍返回授权页，说明没有真正授权，不能继续连接。
     */
    @Test fun ungrantedVpnKeepsWaitingForConsent() {
        val gate = VpnPreConsentGate(preConsent = { true }, prepare = { Intent("test.consent") })
        assertFalse(gate.satisfied())
    }

    /**
     * adb 不可达时同样不能视为已授权。
     */
    @Test fun unreachableAdbDoesNotGrantVpn() {
        val gate = VpnPreConsentGate(preConsent = { false }, prepare = { null })
        assertFalse(gate.satisfied())
    }

    /**
     * 系统在复查时抛异常也不能判定为已授权。
     */
    @Test fun prepareFailureIsNotTreatedAsGranted() {
        val gate = VpnPreConsentGate(
            preConsent = { true },
            prepare = { throw SecurityException("OEM blocked VPN preparation") },
        )
        assertFalse(gate.satisfied())
    }
}

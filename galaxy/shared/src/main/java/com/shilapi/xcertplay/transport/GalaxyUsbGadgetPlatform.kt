package com.shilapi.xcertplay.transport

import android.content.Context
import com.shilapi.xcertplay.network.GalaxyP2pLoopback
import java.util.concurrent.atomic.AtomicBoolean

/** 实验后端：调用固件已有 init 规则。APVP 未适配时不发送猜测信号，必须观察真实 UDC 结果。 */
internal class GalaxyUsbGadgetPlatform(
    context: Context,
    private val cancelled: () -> Boolean,
    report: (String) -> Unit,
    private val commandOverride: ((String, Boolean) -> String)? = null,
    private val nodeAccessible: () -> Boolean = {
        java.io.File("/dev/ipodout0").let { it.canRead() && it.canWrite() }
    },
) : GalaxyUsbVerificationAttempt.Platform {
    private val report: (String) -> Unit = { message -> try { report(message) } catch (_: Exception) { } }
    private val prefs = GalaxyUsbVerificationPreferences.journal(context)
    private var ownsLease = false
    private var original = ""
    private var controller = ""
    private var originalCarplay = ""

    override fun prepare() {
        if (!lease.compareAndSet(false, true)) fail(GalaxyUsbVerificationFailure.Reason.RESTORE_REQUIRED)
        ownsLease = true
        if (prefs.contains("original")) {
            report("USB_VERIFY stage=PREVIOUS_RECOVERY result=BEGIN")
            restore()
            report("USB_VERIFY stage=PREVIOUS_RECOVERY result=COMPLETED")
        }
        withShell { shell ->
            if (shell("id -u") != "0") fail(GalaxyUsbVerificationFailure.Reason.CONTROL_NOT_ROOT)
            original = shell("getprop sys.usb.config")
            originalCarplay = shell("getprop carplay.config.usb")
            controller = shell("getprop sys.usb.controller")
            if (!GalaxyUsbGadgetRules.safeConfig(original) || original == GalaxyUsbGadgetRules.CONFIG ||
                originalCarplay !in setOf("", "0", "1")) fail(GalaxyUsbVerificationFailure.Reason.CONFIG_UNSUPPORTED)
            if (!GalaxyUsbGadgetRules.safeController(controller)) fail(GalaxyUsbVerificationFailure.Reason.CONTROLLER_UNAVAILABLE)
            if (shell("if [ -d /sys/class/udc/$controller ]; then printf PRESENT; else printf ABSENT; fi") != "PRESENT")
                fail(GalaxyUsbVerificationFailure.Reason.CONTROLLER_UNAVAILABLE)
            val state = shell("cat /sys/class/udc/$controller/state")
            // 不接管已在使用的 gadget（其他手机／维护电脑）。
            if (state != "not attached") fail(GalaxyUsbVerificationFailure.Reason.CONTROLLER_UNAVAILABLE)
            if (shell("if [ -c /dev/ipodout0 ]; then printf PRESENT; else printf ABSENT; fi") != "PRESENT")
                fail(GalaxyUsbVerificationFailure.Reason.IPOD_NODE_UNAVAILABLE)
            if (shell("getprop sys.usb.configfs") != "1") fail(GalaxyUsbVerificationFailure.Reason.CONFIG_UNSUPPORTED)
            if (!nodeAccessible()) fail(GalaxyUsbVerificationFailure.Reason.IPOD_NODE_UNAVAILABLE)
            val rules = shell(RULE_QUERY)
            val supported = GalaxyUsbGadgetRules.supports(rules)
            report("USB_VERIFY stage=DEPENDENCIES backend=OEM_INIT apvp=NOT_USED exactGadgetRule=$supported controllerIdle=true " +
                "originalConfig=$original ruleBytes=${rules.length} nodePresent=true")
            if (!supported) fail(GalaxyUsbVerificationFailure.Reason.GADGET_RULE_MISSING)
        }
    }

    override fun checkpoint() {
        if (!prefs.edit().putString("original", original).putString("controller", controller)
                .putString("carplay", originalCarplay).commit()) fail(GalaxyUsbVerificationFailure.Reason.RESTORE_REQUIRED)
    }

    override fun activate() = withShell { shell ->
        // 仅使用已核实的 USB 组合与 init 入口。设置返回成功不代表已配置。
        shell("setprop sys.usb.config '${GalaxyUsbGadgetRules.CONFIG}'")
        shell("setprop carplay.config.usb 1")
        Unit
    }

    override fun awaitConfigured(cancelled: () -> Boolean) {
        val deadline = System.nanoTime() + 12_000_000_000L
        var last = ""
        var configApplied = false
        while (System.nanoTime() < deadline) {
            if (cancelled()) fail(GalaxyUsbVerificationFailure.Reason.CANCELLED)
            val sample = withShell { shell ->
                val config = shell("getprop sys.usb.state")
                val state = shell("cat /sys/class/udc/$controller/state")
                configApplied = config == GalaxyUsbGadgetRules.CONFIG
                configApplied to (state == "configured")
            }
            val summary = "configApplied=${sample.first} udcConfigured=${sample.second}"
            if (last != summary) { report("USB_VERIFY stage=GADGET_READBACK $summary"); last = summary }
            if (sample.first && sample.second) return
            Thread.sleep(200)
        }
        fail(if (configApplied) GalaxyUsbVerificationFailure.Reason.GADGET_NOT_CONFIGURED else GalaxyUsbVerificationFailure.Reason.CONFIG_NOT_APPLIED)
    }

    override fun openStream(): BlockingDuplexByteStream = GalaxyUsbIpodStream.open(report)

    override fun restore() {
        // 系统恢复和记录提交均不继承取消，之后保留调用线程的中断状态。
        val interrupted = Thread.interrupted()
        try { restoreConfiguration() } finally { if (interrupted) Thread.currentThread().interrupt() }
    }

    private fun restoreConfiguration() {
        val saved = prefs.getString("original", null) ?: return
        val savedController = prefs.getString("controller", "").orEmpty()
        val savedCarplay = prefs.getString("carplay", "").orEmpty()
        if (!GalaxyUsbGadgetRules.safeConfig(saved) || !GalaxyUsbGadgetRules.safeController(savedController) ||
            savedCarplay !in setOf("", "0", "1")) fail(GalaxyUsbVerificationFailure.Reason.RESTORE_FAILED)
        // 独立恢复预算，不继承用户取消标记；不固定写 diag,adb 覆盖用户原配置。
        withShell(recovery = true) { shell ->
            if (shell("id -u") != "0") fail(GalaxyUsbVerificationFailure.Reason.RESTORE_FAILED)
            val current = shell("getprop sys.usb.config")
            if (current !in setOf(saved, GalaxyUsbGadgetRules.CONFIG)) fail(GalaxyUsbVerificationFailure.Reason.RESTORE_FAILED)
            if (shell("getprop sys.usb.controller") != savedController) fail(GalaxyUsbVerificationFailure.Reason.RESTORE_FAILED)
            shell("setprop carplay.config.usb '$savedCarplay'")
            shell("setprop sys.usb.config '$saved'")
            var restored = false
            repeat(10) {
                if (!restored) {
                    restored = shell("getprop sys.usb.state") == saved && shell("getprop carplay.config.usb") == savedCarplay &&
                        shell("cat /sys/class/udc/$savedController/state") == "not attached"
                    if (!restored) Thread.sleep(100)
                }
            }
            if (!restored) fail(GalaxyUsbVerificationFailure.Reason.RESTORE_FAILED)
        }
        if (!prefs.edit().clear().commit()) fail(GalaxyUsbVerificationFailure.Reason.RESTORE_FAILED)
    }

    override fun close() { if (ownsLease) { ownsLease = false; lease.set(false) } }

    private fun <T> withShell(recovery: Boolean = false, operation: ((String) -> String) -> T): T = try {
        if (commandOverride != null) operation { command -> commandOverride.invoke(command, recovery) }
        else GalaxyP2pLoopback.open(System.nanoTime() + 4_000_000_000L, { !recovery && cancelled() }).use { session ->
            operation { command -> session.run(command) }
        }
    } catch (e: GalaxyUsbVerificationFailure) { throw e }
      catch (_: Exception) { fail(if (recovery) GalaxyUsbVerificationFailure.Reason.RESTORE_FAILED else GalaxyUsbVerificationFailure.Reason.LOCAL_CONTROL_UNAVAILABLE) }

    private fun fail(reason: GalaxyUsbVerificationFailure.Reason): Nothing = throw GalaxyUsbVerificationFailure(reason)
    companion object {
        const val JOURNAL = GalaxyUsbVerificationPreferences.JOURNAL
        private val lease = AtomicBoolean(false)
        fun recover(context: Context, report: (String) -> Unit) {
            val platform = GalaxyUsbGadgetPlatform(context, { false }, report)
            if (!lease.compareAndSet(false, true)) throw GalaxyUsbVerificationFailure(GalaxyUsbVerificationFailure.Reason.RESTORE_REQUIRED)
            platform.ownsLease = true
            try {
                platform.report("USB_VERIFY stage=MANUAL_RESTORE result=BEGIN")
                platform.restore()
                platform.report("USB_VERIFY stage=MANUAL_RESTORE result=COMPLETED")
            } finally { platform.close() }
        }
        private const val RULE_QUERY = "for f in /init.usb*.rc /system/etc/init/hw/init.usb*.rc /vendor/etc/init/hw/init*usb*.rc /vendor/etc/init/init*usb*.rc /odm/etc/init/init*usb*.rc; do if [ -r \"\$f\" ]; then grep -A 60 '^on .*property:sys.usb.config=iap,ncm' \"\$f\" || true; fi; done"
    }
}

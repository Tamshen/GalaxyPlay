package com.shilapi.xcertplay.orchestration

import android.hardware.usb.UsbDevice
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.assertTrue
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy

/** 共享 Android USB 测试边界；不启动真实认证或手机协议。 */
internal fun withController(check: (CarPlayController, MutableList<CarPlayStatus>) -> Unit) {
    val statuses = mutableListOf<CarPlayStatus>()
    val config = CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
        identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3))
    val controller = CarPlayController(RuntimeEnvironment.getApplication(), config,
        AirPlayConfig("test", "00:00:00:00:00:01", "00:00:00:00:00:02", "1", AirPlayDisplayConfig(800, 480), port = 0),
        AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, { statuses += it })
    try { check(controller, statuses) } finally { controller.close(); assertTrue(controller.awaitClosed(4000)) }
}

internal fun gate(controller: CarPlayController): IphoneUsbPermissionGate = ReflectionHelpers.getField(controller, "iphonePermission")
internal fun phase(controller: CarPlayController): String = ReflectionHelpers.getField<Any>(controller, "phase").toString()
internal fun setPhase(controller: CarPlayController, phase: String) {
    val type = CarPlayController::class.java.declaredClasses.single { it.simpleName == "Phase" }
    ReflectionHelpers.setField(controller, "phase", type.enumConstants!!.single { it.toString() == phase })
}
internal fun device(name: String, vendor: Int = 0x05ac): UsbDevice {
    // API 差异与 Android 编译器生成的访问构造只在测试边界适配。
    val candidates = UsbDevice::class.java.declaredConstructors.filter {
        !it.isSynthetic && it.parameterTypes.firstOrNull() == String::class.java &&
            it.parameterTypes.any { parameter -> parameter.simpleName == "IUsbSerialReader" }
    }
    val constructor = (candidates.singleOrNull() ?: error(candidates.joinToString(" | ") { it.toGenericString() }))
        .apply { isAccessible = true }
    var strings = 0
    var ints = 0
    val values = constructor.parameterTypes.map { type ->
        when {
            type == String::class.java -> if (strings++ == 0) name else "test"
            type == Integer.TYPE -> when (ints++) { 0 -> vendor; 1 -> 0x1234; else -> 0 }
            type == java.lang.Boolean.TYPE -> false
            type.isArray -> java.lang.reflect.Array.newInstance(type.componentType!!, 0)
            type.isInterface -> Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> null }
            else -> error("Unexpected USB constructor parameter")
        }
    }.toTypedArray()
    return constructor.newInstance(*values) as UsbDevice
}

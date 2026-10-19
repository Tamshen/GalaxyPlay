package com.shilapi.xcertplay.orchestration

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.transport.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 原始 USB iAP2 + 系统 usb0 接入现有认证、AirPlay、媒体；不走 Lockdown、USBMUX 或 VPN。 */
internal class GalaxyUsbVerificationControlRunner(
    private val config: CarPlayRuntimeConfig,
    private val resources: WiredResources,
    private val current: () -> Boolean,
    private val report: (String) -> Unit,
) {
    fun run(
        context: Context, manager: UsbManager, device: UsbDevice,
        service: CarPlayVpnService, airPlay: AirPlayConfig, identity: AirPlayIdentity,
        pairings: PairingStore, mfi: MfiAuthenticator, media: AirPlayMediaHandler,
        listener: AirPlaySessionListener, active: () -> Boolean,
        status: (CarPlayStatus) -> Unit, failure: (Throwable) -> Unit,
        sessionOpened: (Iap2Session) -> Unit,
        onIncoming: (Iap2Frame) -> Unit, onArtwork: (Iap2ArtworkTransfer) -> Unit,
        location: Iap2LocationProvider?, vehicle: VehicleStatusProvider?,
    ) {
        val failed = AtomicBoolean(false)
        fun stop(error: Throwable) {
            if (!failed.compareAndSet(false, true)) return
            val reason = (error as? GalaxyUsbVerificationFailure)?.reason ?: GalaxyUsbVerificationFailure.Reason.LINK_FAILED
            emit("stage=FAILED reason=$reason")
            resources.close()
            failure(GalaxyUsbVerificationFailure(if (GalaxyUsbVerificationSession.needsRecovery(context))
                GalaxyUsbVerificationFailure.Reason.RESTORE_FAILED else reason))
        }
        try {
            // 先登记服务清理，关闭时先断 AirPlay，后恢复 gadget；所有迟到接入按 owner 拒绝。
            if (!resources.own("USB_VERIFY_LISTENER", AutoCloseable {
                    val workers = service.detachWired(resources)
                    if (!service.awaitDetached(3_000, workers)) throw GalaxyUsbVerificationFailure(GalaxyUsbVerificationFailure.Reason.RESTORE_FAILED)
                })) return
            val usb = GalaxyUsbVerificationSession(context, manager, device, { !current() }, report)
            if (!resources.own("USB_VERIFY", usb)) return
            resources.beginStartup()
            status(CarPlayStatus.SelectingConfiguration)
            val stream = usb.open()
            if (!current()) return
            status(CarPlayStatus.ConnectingControl)
            val channel = Iap2Session.open(stream, traceContext = "usb-usb", onTrace = report, onArtwork = onArtwork)
            if (!resources.own("USB_VERIFY_IAP2", channel)) return
            sessionOpened(channel)
            emit("stage=IAP2_LINK result=BEGIN")
            if (!channel.awaitReady(10_000)) throw GalaxyUsbVerificationFailure(GalaxyUsbVerificationFailure.Reason.LINK_FAILED)
            emit("stage=IAP2_LINK result=READY")
            if (!current()) return
            status(CarPlayStatus.AttachingNetwork)
            val network = usb.awaitNetwork()
            val attached = service.attachGadget(network.address, airPlay, identity, pairings, mfi,
                listener, media, resources, current) {
                if (current()) resources.startupElapsedMillis("FIRST_TCP")?.let { emit("stage=FIRST_TCP elapsedMs=$it") }
            }
            if (attached != CarPlayVpnService.AttachResult.Started)
                throw GalaxyUsbVerificationFailure(GalaxyUsbVerificationFailure.Reason.NETWORK_NOT_READY)
            emit("stage=AIRPLAY_LISTENER result=READY vpn=false")
            val watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it, "galaxy-usb-startup").apply { isDaemon = true } }
            if (!resources.own("USB_VERIFY_WATCHDOG", AutoCloseable { watchdog.shutdownNow() })) return
            watchdog.schedule({ if (current() && !active()) stop(GalaxyUsbVerificationFailure(GalaxyUsbVerificationFailure.Reason.SESSION_FAILED)) }, 60, TimeUnit.SECONDS)
            val endpoint = Iap2WiredCarPlayEndpoint(
                listOf(network.address.hostAddress!!.substringBefore('%')),
                service.wiredPort(resources) ?: throw GalaxyUsbVerificationFailure(GalaxyUsbVerificationFailure.Reason.NETWORK_NOT_READY),
                identity.publicKeyHex, airPlay.sourceVersion,
                network.mac.joinToString(":") { "%02x".format(it.toInt() and 255) },
            )
            status(CarPlayStatus.RunningControl)
            // 参考样本的 iap2_usb_host.cfg 声明 CarPlayInterface=1；其余身份与认证来自本应用。
            val result = Iap2WiredControlClient(channel, Iap2MfiAuthenticationClient(mfi)).run(
                config.identification.copy(carPlayUsbInterfaceNumber = 1), endpoint,
                config.availableCurrentMilliAmps, Iap2WiredControlClient.NO_TIMEOUT_MILLIS,
                locationProvider = location, vehicleStatusProvider = vehicle, onIncoming = onIncoming,
                onProgress = { message -> emit("stage=CONTROL $message") }, isCancelled = { !current() },
            )
            if (current()) {
                emit("stage=CONTROL_ENDED terminal=${result.terminal} phase=${result.stage}")
                stop(GalaxyUsbVerificationFailure(GalaxyUsbVerificationFailure.Reason.SESSION_FAILED))
            }
        } catch (error: Exception) {
            if (current()) stop(error) else resources.close()
        }
    }
    private fun emit(body: String) { try { report("USB_VERIFY $body") } catch (_: Exception) { } }
}

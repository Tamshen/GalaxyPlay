package com.shilapi.xcertplay.network

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import com.shilapi.xcertplay.airplay.isInternalAirPlayPeer
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the AirPlay TCP listener for both NCM/VPN and local-only Wi-Fi transports.
 *
 * The wired path also owns the Android VPN tunnel and NCM IPv6 bridge. VPN consent is requested
 * with [prepare] before binding.
 */
class CarPlayVpnService : VpnService() {
    inner class LocalBinder : Binder() {
        val service: CarPlayVpnService get() = this@CarPlayVpnService
    }

    sealed class AttachResult {
        data object Started : AttachResult()
        data object AlreadyStarted : AttachResult()
        data class Failed(val message: String) : AttachResult()
    }

    private data class AirPlayAttachment(
        val address: InetAddress,
        val config: AirPlayConfig,
        val identity: AirPlayIdentity,
        val pairings: PairingStore,
        val mfi: MfiAuthenticator?,
        val listener: AirPlaySessionListener,
        val media: AirPlayMediaHandler,
        val additionalAddresses: List<InetAddress> = emptyList(),
        val listenerIdentity: AirPlayListenerIdentity? = null,
        val onTcpAccepted: () -> Unit = {},
    )

    private val binder = LocalBinder()
    private val active = AtomicBoolean(false)
    private val acceptWorkers = java.util.concurrent.ConcurrentHashMap.newKeySet<Thread>()
    private val sessionsLock = Any()
    private val sessions = mutableSetOf<AirPlaySession>()
    @Volatile private var attachment: AirPlayAttachment? = null
    private var serverSocket: ServerSocket? = null
    private var additionalServers: List<ServerSocket> = emptyList()
    private var bridge: Ipv6NcmBridge? = null
    private var tun: ParcelFileDescriptor? = null
    private var attachGeneration = 0
    private var wiredOwner: Any? = null

    override fun onBind(intent: Intent?): IBinder = binder

    @Synchronized
    fun attach(
        ncm: NcmUsbBridge,
        linkLocal: String,
        hostMac: ByteArray,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
        owner: Any? = null,
        isCurrent: () -> Boolean = { true },
        onTcpAccepted: () -> Unit = {},
    ): AttachResult {
        if (!isCurrent()) return AttachResult.Failed("Wired attempt cancelled")
        if (active.get()) {
            Log.i(TAG, "replacing stale NCM/VPN attachment")
            releaseLocked()
        }
        if (!isCurrent()) return AttachResult.Failed("Wired attempt cancelled")
        active.set(true)
        wiredOwner = owner
        val generation = ++attachGeneration
        return try {
            val address = InetAddress.getByName(linkLocal)
            if (address !is Inet6Address || !address.isLinkLocalAddress) {
                throw IllegalArgumentException("linkLocal must be a link-local IPv6 literal")
            }
            require(hostMac.size == 6) { "hostMac must be 6 bytes" }

            val tunFd = Builder()
                .addAddress(linkLocal, LINK_PREFIX)
                .addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)
                .setSession(SESSION_NAME)
                .setMtu(TUN_MTU)
                .setBlocking(true)
                // 空白名单会影响其他应用，建立隧道前限定本包；拒绝授权时清理，不退回全局 VPN。
                .addAllowedApplication(packageName)
                .establish()
                ?: throw IOException("VpnService.establish returned null")
            tun = tunFd
            if (!isCurrent()) throw IOException("Wired attempt cancelled")

            val ipv6Bridge = Ipv6NcmBridge(ncm, tunFd, hostMac) { error ->
                onTransportError(generation, listener, error)
            }
            ipv6Bridge.start()
            bridge = ipv6Bridge

            startAirPlayServer(
                generation,
                AirPlayAttachment(address, config, identity, pairings, mfi, listener, media, onTcpAccepted = onTcpAccepted),
            )
            if (!isCurrent()) throw IOException("Wired attempt cancelled")
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Starts the AirPlay listener on the local-only Wi-Fi AP address without establishing a VPN or
     * NCM bridge.
     */
    @Synchronized
    fun attachWireless(
        bindAddress: InetAddress,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
        additionalBindAddresses: List<InetAddress> = emptyList(),
        listenerIdentity: AirPlayListenerIdentity? = null,
    ): AttachResult {
        if (active.get()) {
            Log.i(TAG, "replacing stale local-only Wi-Fi attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        return try {
            startAirPlayServer(
                generation,
                AirPlayAttachment(bindAddress, config, identity, pairings, mfi, listener, media,
                    additionalBindAddresses, listenerIdentity),
            )
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** 系统 gadget 已建立网络时仅监听其带 scope 地址，不创建 VPN 或接管现有会话。 */
    @Synchronized
    fun attachGadget(
        bindAddress: Inet6Address,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
        owner: Any,
        isCurrent: () -> Boolean,
        onTcpAccepted: () -> Unit,
    ): AttachResult {
        if (!isCurrent()) return AttachResult.Failed("USB_VERIFY_CANCELLED")
        if (active.get()) return AttachResult.AlreadyStarted
        if (!bindAddress.isLinkLocalAddress || bindAddress.scopeId <= 0)
            return AttachResult.Failed("USB_VERIFY_INVALID_NETWORK")
        active.set(true)
        wiredOwner = owner
        val generation = ++attachGeneration
        return try {
            startAirPlayServer(generation, AirPlayAttachment(bindAddress, config, identity, pairings,
                mfi, listener, media, onTcpAccepted = onTcpAccepted))
            if (!isCurrent()) throw IOException("USB_VERIFY_CANCELLED")
            AttachResult.Started
        } catch (_: Exception) {
            releaseLocked()
            AttachResult.Failed("USB_VERIFY_LISTENER_FAILED")
        }
    }

    /** Releases the active AirPlay listener and whichever VPN/NCM transport resources are active. */
    @Synchronized
    fun detach() {
        releaseLocked()
    }

    /** 旧任务清理只能释放自己的接入；不调用控制器锁，避免关闭与接入反向等待。 */
    @Synchronized
    fun detachWired(owner: Any): Array<Thread> {
        if (wiredOwner !== owner) return emptyArray()
        val workers = acceptWorkers.toTypedArray()
        releaseLocked()
        return workers
    }

    @Synchronized
    fun wiredPort(owner: Any): Int? = if (wiredOwner === owner) attachment?.config?.port else null

    @Synchronized
    fun hasWiredControl(owner: Any): Boolean = wiredOwner === owner && synchronized(sessionsLock) { sessions.isNotEmpty() }

    @Synchronized
    fun detachWireless(owner: AirPlayListenerIdentity) {
        if (attachment?.listenerIdentity === owner) releaseLocked()
    }

    /** 非主线程等实际 accept 退出；关闭请求与 worker 退出分别判定。 */
    fun awaitDetached(timeoutMillis: Long, workers: Array<out Thread> = acceptWorkers.toTypedArray()): Boolean {
        require(timeoutMillis >= 0)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        // Kotlin 小集合转换会先读 size 再取首项；并发退出可使该首项已经消失。
        // ConcurrentHashMap 的数组快照容忍并发删除，再按同一总预算等待。
        for (worker in workers) {
            if (worker === Thread.currentThread()) return false
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return workers.none { it.isAlive }
            try { worker.join((remaining / 1_000_000L).coerceAtLeast(1L)) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
        }
        return workers.none { it.isAlive }
    }

    fun isAttached(): Boolean = active.get() && attachment != null

    /** 返回实际监听端口，首选端口冲突时该值可能与配置不同。 */
    fun boundPort(): Int? = attachment?.config?.port

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun startAirPlayServer(
        generation: Int,
        replacement: AirPlayAttachment,
    ) {
        val servers = if (replacement.additionalAddresses.isEmpty()) {
            listOf(AirPlayPortSelector.bind(replacement.address, replacement.config.port) { busy, bound ->
                Log.w(TAG, "AirPlay port $busy is in use; listening on $bound instead")
            })
        } else {
            AirPlayPortSelector.bindAll(listOf(replacement.address) + replacement.additionalAddresses,
                replacement.config.port) { busy, bound ->
                Log.w(TAG, "AirPlay port $busy is in use; listening on $bound instead")
            }
        }
        val server = servers.first()
        attachment = replacement.copy(config = replacement.config.copy(port = server.localPort))
        serverSocket = server
        additionalServers = servers.drop(1)
        servers.forEach { bound ->
            runCatching { replacement.listener.onDebugLog(
                "airplay listener ready family=${if (bound.inetAddress is Inet6Address) "IPv6" else "IPv4"} port=${bound.localPort}",
            ) }
            Thread(
                { try { acceptLoop(generation, bound) } finally { acceptWorkers.remove(Thread.currentThread()) } },
                "airplay-accept",
            ).apply {
                isDaemon = true
                acceptWorkers.add(this)
                start()
            }
        }
    }

    private fun acceptLoop(
        generation: Int,
        server: ServerSocket,
    ) {
        try {
            while (active.get()) {
                val socket: Socket = server.accept()
                val acceptedAtNanos = System.nanoTime()
                Log.i(TAG, "airplay connection accepted from ${socket.remoteSocketAddress}")
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.setSoLinger(true, 0)
                val session = synchronized(this) {
                    if (!active.get() || generation != attachGeneration ||
                        (serverSocket !== server && additionalServers.none { it === server })) {
                        socket.close()
                        return
                    }
                    val current = attachment
                    if (current == null) {
                        socket.close()
                        return
                    }
                    val internalPeer = isInternalAirPlayPeer(socket.inetAddress, socket.localAddress)
                    runCatching { current.onTcpAccepted() }
                    current.listenerIdentity?.let { owner ->
                        current.listener.onTcpAccepted(AirPlayTcpAccepted(
                            owner, internalPeer, acceptedAtNanos,
                        ))
                    }
                    runCatching { current.listener.onDebugLog(
                        "airplay TCP accepted family=${if (socket.inetAddress is Inet6Address) "IPv6" else "IPv4"}",
                    ) }
                    AirPlaySession(
                        socket = socket,
                        config = current.config,
                        identity = current.identity,
                        pairings = current.pairings,
                        mfi = current.mfi,
                        listener = object : AirPlaySessionListener by current.listener {
                            override fun onSessionActive(session: AirPlaySession) {
                                if (current.listenerIdentity == null || !internalPeer) current.listener.onSessionActive(session)
                            }

                            override fun onRemoteControlMessage(
                                session: AirPlaySession,
                                streamId: Long,
                                message: Map<String, Any?>,
                            ) {
                                current.listener.onRemoteControlMessage(session, streamId, message)
                            }

                            override fun onVideoPlaybackUiRequested(session: AirPlaySession) {
                                current.listener.onVideoPlaybackUiRequested(session)
                            }

                            override fun onSessionEnded(session: AirPlaySession) {
                                removeSession(session)
                                if (current.listenerIdentity == null || !internalPeer) current.listener.onSessionEnded(session)
                            }
                        },
                        media = current.media,
                    ).also(::addSession)
                }
                session.start()
            }
        } catch (error: IOException) {
            if (active.get()) {
                attachment?.listener?.let { onTransportError(generation, it, error) }
            }
        }
    }

    private fun addSession(session: AirPlaySession) {
        synchronized(sessionsLock) { sessions.add(session) }
    }

    private fun removeSession(session: AirPlaySession?) {
        if (session == null) return
        synchronized(sessionsLock) { sessions.remove(session) }
    }

    private fun closeSessionsLocked() {
        val closing = com.shilapi.xcertplay.transport.CloseSequence("AirPlay sessions")
        val owned = synchronized(sessionsLock) { sessions.toList().also { sessions.clear() } }
        owned.forEach { session -> closing.step { session.close() } }
        closing.finish()
    }

    private fun onTransportError(
        generation: Int,
        listener: AirPlaySessionListener,
        error: Throwable,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        Log.e(TAG, "CarPlay transport stopped: $message", error)
        Thread(
            {
                val releasedGeneration = synchronized(this) {
                    if (generation != attachGeneration) return@Thread
                    releaseLocked()
                    attachGeneration
                }
                listener.onTransportError(message)
                synchronized(this) {
                    if (attachGeneration == releasedGeneration && !active.get()) stopSelf()
                }
            },
            "airplay-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Caller must hold this service's monitor. Closes only resources active for this attachment. */
    private fun releaseLocked() {
        attachGeneration += 1
        active.set(false)
        attachment = null
        wiredOwner = null
        val closing = com.shilapi.xcertplay.transport.CloseSequence("AirPlay transport")
        val primary = serverSocket
        val extra = additionalServers
        val oldBridge = bridge
        val oldTun = tun
        serverSocket = null
        additionalServers = emptyList()
        bridge = null
        tun = null
        // 某个监听／会话关闭失败也必须继续解除 NCM 和 TUN，失败仍向资源门禁传播。
        closing.step { primary?.close() }
        extra.forEach { server -> closing.step { server.close() } }
        closing.step { closeSessionsLocked() }
        closing.step { oldBridge?.close() }
        closing.step { oldTun?.close() }
        closing.finish()
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        private const val LINK_PREFIX = 64
        private const val LINK_LOCAL_ROUTE = "fe80::"
        private const val SESSION_NAME = "xcertplay CarPlay"
        private const val TUN_MTU = 1500

        /** Returns the VPN consent intent, or null when consent is already granted. */
        fun prepare(context: Context): Intent? = VpnService.prepare(context)
    }
}

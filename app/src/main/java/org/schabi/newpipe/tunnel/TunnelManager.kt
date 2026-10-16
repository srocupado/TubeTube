package org.schabi.newpipe.tunnel

import android.content.Context
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import java.net.Proxy
import java.net.ProxySelector
import java.net.URL
import java.net.URLConnection
import org.schabi.newpipe.DownloaderImpl
import org.schabi.newpipe.util.InfoCache

/**
 * Single source of truth for how TubeTube reaches the network.
 *
 * Fail closed: until the selected tunnel reports CONNECTED, [currentProxy] points every
 * connection at [Blackhole], so nothing can leave the device directly.
 */
object TunnelManager {
    private const val TAG = "TunnelManager"

    private lateinit var appContext: Context
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    @JvmStatic
    var currentStatus = TunnelStatus(TunnelMode.TOR, TunnelStatus.State.CONNECTING)
        private set

    @Volatile
    private var torHttpPort = -1

    /** Late callbacks from a controller of another mode are ignored. */
    @Volatile
    private var activeMode = TunnelMode.TOR

    private val statusSubject = BehaviorSubject.createDefault(currentStatus)

    /** Emits on the main thread. */
    @JvmStatic
    val status: Observable<TunnelStatus> get() = statusSubject.hide()

    private var tor: TorController? = null

    @Volatile
    private var wireGuard: WireGuardController? = null

    /**
     * Must run before anything touches the network: it installs the process-wide
     * [ProxySelector] that every HTTP stack in the app consults.
     */
    @JvmStatic
    fun init(context: Context, startTunnel: Boolean) {
        appContext = context.applicationContext
        ProxySelector.setDefault(TunnelProxySelector)
        if (startTunnel) {
            start()
        }
    }

    /** Re-reads the selected mode and (re)starts the matching tunnel. Main thread only. */
    @JvmStatic
    fun restart() {
        tor?.stop()
        tor = null
        wireGuard?.stop()
        wireGuard = null
        start()
    }

    private fun start() {
        activeMode = TunnelMode.fromPreferences(appContext)
        onStatus(TunnelStatus(activeMode, TunnelStatus.State.CONNECTING))
        when (activeMode) {
            TunnelMode.TOR -> tor = TorController(appContext, ::onTorStatus).also { it.start() }

            TunnelMode.VPN ->
                wireGuard =
                    WireGuardController(appContext, ::onStatus).also { it.start() }

            TunnelMode.DIRECT -> onStatus(TunnelStatus(TunnelMode.DIRECT, TunnelStatus.State.CONNECTED))
        }
    }

    @JvmStatic
    fun newTorIdentity() {
        tor?.newIdentity()
        dropConnectionsAndCachedStreams()
    }

    /**
     * In VPN mode, the VPN network while connected. Sockets bound to it fail if the VPN goes
     * away, instead of silently falling back to Wi-Fi or mobile data.
     */
    internal fun vpnNetworkIfConnected(): Network? {
        val status = currentStatus
        if (status.mode != TunnelMode.VPN || !status.isConnected) {
            return null
        }
        return wireGuard?.vpnNetwork
    }

    /**
     * Use instead of [URL.openConnection]: in VPN mode the connection is bound to the VPN
     * network; otherwise [TunnelProxySelector] routes it (Tor port or blackhole).
     */
    @JvmStatic
    fun openConnection(url: URL): URLConnection {
        if (currentStatus.mode == TunnelMode.VPN) {
            val network = vpnNetworkIfConnected() ?: throw TunnelUnavailableException(currentStatus)
            return network.openConnection(url)
        }
        return url.openConnection()
    }

    /** Tor's HTTP CONNECT port, or -1 unless Tor mode is connected. */
    internal fun torHttpPortIfConnected(): Int {
        val status = currentStatus
        return if (status.mode == TunnelMode.TOR && status.isConnected) torHttpPort else -1
    }

    /** The route for the next connection. Called by [TunnelProxySelector] on any thread. */
    fun currentProxy(): Proxy = routeFor(currentStatus, torHttpPort)

    /** Pure routing decision, see [currentProxy]. */
    internal fun routeFor(status: TunnelStatus, torPort: Int): Proxy {
        if (!status.isConnected) {
            return Blackhole.proxy
        }
        return when (status.mode) {
            TunnelMode.TOR -> if (torPort > 0) Loopback.httpProxy(torPort) else Blackhole.proxy

            // the OS routes this app's sockets into the WireGuard interface
            TunnelMode.VPN -> Proxy.NO_PROXY

            // opt-in: straight to the destination
            TunnelMode.DIRECT -> Proxy.NO_PROXY
        }
    }

    private fun onTorStatus(status: TunnelStatus, port: Int) {
        if (status.mode != activeMode) {
            return
        }
        if (status.isConnected) {
            torHttpPort = port
        }
        onStatus(status)
    }

    private fun onStatus(status: TunnelStatus) {
        if (status.mode != activeMode) {
            return
        }
        val previous = currentStatus
        currentStatus = status
        if (previous != status) {
            Log.i(TAG, "Tunnel status: $status")
        }
        if (previous.isConnected != status.isConnected || previous.mode != status.mode) {
            dropConnectionsAndCachedStreams()
        }
        mainHandler.post { statusSubject.onNext(status) }
    }

    /**
     * Pooled connections were made through the previous route, and YouTube stream URLs are
     * bound to the IP that fetched them: both must go when the route changes.
     */
    private fun dropConnectionsAndCachedStreams() {
        DownloaderImpl.getInstance()?.client?.connectionPool?.evictAll()
        InfoCache.getInstance().clearCache()
    }
}

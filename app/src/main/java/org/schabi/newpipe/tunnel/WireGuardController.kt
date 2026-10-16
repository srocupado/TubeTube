package org.schabi.newpipe.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.util.Log
import androidx.core.content.getSystemService
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Runs an embedded WireGuard tunnel that only this app is routed through (the rest of the
 * phone is untouched). The tunnel counts as connected only when WireGuard reports it is up AND
 * Android reports that our default network really is the VPN.
 */
internal class WireGuardController(
    private val context: Context,
    private val onStatus: (TunnelStatus) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "wireguard") }
    private val backend by lazy { GoBackend(context) }

    @Volatile
    private var tunnelUp = false

    @Volatile
    private var vpnIsDefaultNetwork = false

    /** The VPN network while it is our default network: sockets get bound to it. */
    @Volatile
    var vpnNetwork: Network? = null
        private set

    @Volatile
    private var stopped = false

    /** True until the first setState(UP) returns, so start-up is reported as CONNECTING. */
    @Volatile
    private var starting = false

    private val tunnel = object : Tunnel {
        override fun getName() = TUNNEL_NAME

        override fun onStateChange(newState: Tunnel.State) {
            tunnelUp = newState == Tunnel.State.UP
            publishCurrent()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            vpnIsDefaultNetwork = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            vpnNetwork = if (vpnIsDefaultNetwork) network else null
            publishCurrent()
        }

        override fun onLost(network: Network) {
            vpnIsDefaultNetwork = false
            vpnNetwork = null
            publishCurrent()
        }
    }

    fun start() {
        stopped = false
        if (!hasConfig(context)) {
            publish(TunnelStatus.State.NEEDS_SETUP, DETAIL_NO_CONFIG)
            return
        }
        if (VpnService.prepare(context) != null) {
            publish(TunnelStatus.State.NEEDS_SETUP, DETAIL_NO_PERMISSION)
            return
        }
        starting = true
        publish(TunnelStatus.State.CONNECTING)
        context.getSystemService<ConnectivityManager>()!!
            .registerDefaultNetworkCallback(networkCallback)
        executor.execute {
            try {
                val config = configFile(context).bufferedReader().use { Config.parse(it) }
                tunnelUp = backend.setState(tunnel, Tunnel.State.UP, config) == Tunnel.State.UP
                starting = false
                publishCurrent()
            } catch (e: Exception) {
                Log.e(TAG, "Could not bring the tunnel up", e)
                tunnelUp = false
                starting = false
                publish(TunnelStatus.State.ERROR, e.message.orEmpty())
            }
        }
    }

    fun stop() {
        stopped = true
        try {
            context.getSystemService<ConnectivityManager>()!!
                .unregisterNetworkCallback(networkCallback)
        } catch (e: IllegalArgumentException) {
            // callback was never registered (start() stopped early)
        }
        executor.execute {
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
            } catch (e: Exception) {
                Log.w(TAG, "Could not bring the tunnel down", e)
            }
        }
        executor.shutdown()
        tunnelUp = false
        vpnNetwork = null
    }

    private fun publishCurrent() {
        if (stopped) {
            return
        }
        if (tunnelUp && vpnIsDefaultNetwork) {
            publish(TunnelStatus.State.CONNECTED)
        } else if (tunnelUp || starting) {
            publish(TunnelStatus.State.CONNECTING)
        } else {
            publish(TunnelStatus.State.ERROR, DETAIL_DOWN)
        }
    }

    private fun publish(state: TunnelStatus.State, detail: String = "") {
        onStatus(TunnelStatus(TunnelMode.VPN, state, detail))
    }

    companion object {
        private const val TAG = "WireGuardController"
        private const val TUNNEL_NAME = "tubetube"
        const val DETAIL_NO_CONFIG = "no_config"
        const val DETAIL_NO_PERMISSION = "no_permission"
        const val DETAIL_DOWN = "down"

        private fun configFile(context: Context) = File(context.noBackupFilesDir, "wireguard.conf")

        fun hasConfig(context: Context) = configFile(context).isFile

        fun deleteConfig(context: Context) {
            configFile(context).delete()
        }

        /**
         * Validates a wg-quick config (as exported by Mullvad, IVPN, Proton...), hardens it and
         * stores it in private, never-backed-up storage.
         *
         * @throws BadConfigException if the config is invalid
         */
        @Throws(IOException::class, BadConfigException::class)
        fun importConfig(context: Context, wgQuickText: String) {
            val hardened = harden(wgQuickText, context.packageName)
            hardened.reader().buffered().use { Config.parse(it) } // validate
            configFile(context).writeText(hardened)
        }

        /**
         * - routes only this app through the tunnel (any app list in the file is replaced);
         * - forces a full tunnel for IPv4 and IPv6, so nothing is split out of it (IPv6 without
         *   an address in the tunnel is simply unreachable, never sent outside);
         * - adds a DNS server if there is none, so lookups also travel inside the tunnel.
         */
        internal fun harden(text: String, packageName: String): String {
            val out = StringBuilder()
            var section = ""
            var hasDns = false
            var peerCount = 0
            var fullTunnelWritten = false

            fun closeSection() {
                if (section == "interface") {
                    out.append(interfaceTail(hasDns, packageName))
                } else if (section == "peer" && peerCount == 1 && !fullTunnelWritten) {
                    out.append(FULL_TUNNEL)
                    fullTunnelWritten = true
                }
            }

            for (rawLine in text.lines()) {
                val line = rawLine.trim()
                if (line.startsWith("[")) {
                    closeSection()
                    section = line.removePrefix("[").removeSuffix("]").trim().lowercase()
                    if (section == "peer") {
                        peerCount++
                    }
                    out.append(line).append('\n')
                    continue
                }
                val key = line.substringBefore('=').trim().lowercase()
                when {
                    section == "interface" &&
                        (key == "includedapplications" || key == "excludedapplications") -> {
                        // dropped: replaced by our own app below
                    }

                    section == "interface" && key == "dns" -> {
                        hasDns = true
                        out.append(line).append('\n')
                    }

                    section == "peer" && key == "allowedips" -> {
                        // the first peer gets the full tunnel, other peers get no routes
                        if (peerCount == 1 && !fullTunnelWritten) {
                            out.append(FULL_TUNNEL)
                            fullTunnelWritten = true
                        }
                    }

                    else -> out.append(line).append('\n')
                }
            }
            closeSection()
            return out.toString()
        }

        private fun interfaceTail(hasDns: Boolean, packageName: String): String {
            val dns = if (hasDns) "" else "DNS = $FALLBACK_DNS\n"
            return dns + "IncludedApplications = $packageName\n"
        }

        private const val FULL_TUNNEL = "AllowedIPs = 0.0.0.0/0, ::/0\n"

        /** Quad9: no logging of client IPs; queries still travel inside the tunnel. */
        private const val FALLBACK_DNS = "9.9.9.9, 2620:fe::fe"
    }
}

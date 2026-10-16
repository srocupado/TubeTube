package org.schabi.newpipe.tunnel

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import okhttp3.Dns

/**
 * OkHttp sockets: in VPN mode bound to the VPN network (so a dropped VPN fails the request
 * instead of falling back to Wi-Fi or mobile data); in Tor mode plain sockets, which
 * [TunnelProxySelector] only ever points at the local Tor port or [Blackhole].
 */
object TunnelSocketFactory : SocketFactory() {
    private fun delegate(): SocketFactory {
        if (TunnelManager.currentStatus.mode == TunnelMode.VPN) {
            val network = TunnelManager.vpnNetworkIfConnected()
                ?: throw TunnelUnavailableException(TunnelManager.currentStatus)
            return network.socketFactory
        }
        return getDefault()
    }

    override fun createSocket(): Socket = delegate().createSocket()

    override fun createSocket(host: String?, port: Int): Socket = delegate().createSocket(host, port)

    override fun createSocket(
        host: String?,
        port: Int,
        localHost: InetAddress?,
        localPort: Int
    ): Socket = delegate().createSocket(host, port, localHost, localPort)

    override fun createSocket(host: InetAddress?, port: Int): Socket = delegate().createSocket(host, port)

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int
    ): Socket = delegate().createSocket(address, port, localAddress, localPort)
}

/**
 * OkHttp DNS: in VPN mode resolved on the VPN network (inside the tunnel). In Tor mode OkHttp
 * only resolves the literal loopback proxy address: host names are resolved by Tor.
 */
object TunnelDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (TunnelManager.currentStatus.mode == TunnelMode.VPN) {
            val network = TunnelManager.vpnNetworkIfConnected()
                ?: throw TunnelUnavailableException(TunnelManager.currentStatus)
            return network.getAllByName(hostname).toList()
        }
        return Dns.SYSTEM.lookup(hostname)
    }
}

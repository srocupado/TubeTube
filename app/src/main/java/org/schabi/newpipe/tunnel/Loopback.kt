package org.schabi.newpipe.tunnel

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * IPv4 loopback, spelled out. [InetAddress.getLoopbackAddress] returns `::1` on Android, while
 * Tor listens on 127.0.0.1 only: connecting to `[::1]:port` is refused.
 */
internal object Loopback {
    val IPV4: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))

    fun httpProxy(port: Int) = Proxy(Proxy.Type.HTTP, InetSocketAddress(IPV4, port))
}

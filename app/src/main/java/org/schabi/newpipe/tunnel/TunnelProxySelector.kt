package org.schabi.newpipe.tunnel

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Installed as the process-wide default [ProxySelector], so that OkHttp and every
 * [java.net.HttpURLConnection] (player, ExoPlayer, download manager) asks it for a route.
 *
 * It never returns an empty list: OkHttp and HttpURLConnection fall back to a direct connection
 * only in that case.
 */
object TunnelProxySelector : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> {
        if (isLoopback(uri?.host)) {
            return listOf(Proxy.NO_PROXY)
        }
        return listOf(TunnelManager.currentProxy())
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        // nothing to do: there is no alternative route by design
    }

    /** Pure string check: resolving the host here would itself leak a DNS query. */
    private fun isLoopback(host: String?): Boolean {
        if (host == null) {
            return false
        }
        val h = host.removePrefix("[").removeSuffix("]").lowercase()
        return h == "localhost" || h == "::1" || h.startsWith("127.")
    }
}

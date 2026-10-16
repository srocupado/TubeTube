package org.schabi.newpipe.tunnel

import java.net.InetSocketAddress
import java.net.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelRoutingTest {
    private fun status(mode: TunnelMode, state: TunnelStatus.State) = TunnelStatus(mode, state)

    @Test
    fun torModeUsesTheLocalTorPortOnlyWhenConnected() {
        val route = TunnelManager.routeFor(status(TunnelMode.TOR, TunnelStatus.State.CONNECTED), 9080)
        assertEquals(Proxy.Type.HTTP, route.type())
        val address = route.address() as InetSocketAddress
        assertEquals("127.0.0.1", address.address.hostAddress)
        assertEquals(9080, address.port)

        // connected but no port known yet: still blocked
        assertEquals(
            Blackhole.proxy,
            TunnelManager.routeFor(status(TunnelMode.TOR, TunnelStatus.State.CONNECTED), -1)
        )
    }

    @Test
    fun tunnelModesAreBlockedUntilConnected() {
        for (mode in listOf(TunnelMode.TOR, TunnelMode.VPN)) {
            for (state in TunnelStatus.State.entries.filter { it != TunnelStatus.State.CONNECTED }) {
                assertEquals("$mode/$state", Blackhole.proxy, TunnelManager.routeFor(status(mode, state), 9080))
            }
        }
    }

    @Test
    fun vpnAndDirectModesConnectWithoutAProxy() {
        assertEquals(
            Proxy.NO_PROXY,
            TunnelManager.routeFor(status(TunnelMode.VPN, TunnelStatus.State.CONNECTED), -1)
        )
        assertEquals(
            Proxy.NO_PROXY,
            TunnelManager.routeFor(status(TunnelMode.DIRECT, TunnelStatus.State.CONNECTED), -1)
        )
    }

    @Test
    fun directModeIsOptInNotTheDefault() {
        assertEquals(TunnelMode.TOR, TunnelMode.entries.first())
        assertEquals("direct", TunnelMode.DIRECT.prefValue)
    }
}

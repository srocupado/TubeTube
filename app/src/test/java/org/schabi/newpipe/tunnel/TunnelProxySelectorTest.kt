package org.schabi.newpipe.tunnel

import java.net.Proxy
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TunnelProxySelectorTest {
    @Test
    fun loopbackIsReachedDirectly() {
        for (url in listOf("http://127.0.0.1:9050/", "http://localhost/", "http://[::1]:80/")) {
            assertEquals(url, listOf(Proxy.NO_PROXY), TunnelProxySelector.select(URI(url)))
        }
    }

    @Test
    fun remoteHostsAreBlackholedWhileTheTunnelIsDown() {
        // TunnelManager starts as CONNECTING: nothing may go out directly
        val proxies = TunnelProxySelector.select(URI("https://www.youtube.com/watch?v=x"))
        assertEquals(1, proxies.size)
        assertNotEquals(Proxy.NO_PROXY, proxies[0])
        assertEquals(Blackhole.proxy, proxies[0])
    }
}

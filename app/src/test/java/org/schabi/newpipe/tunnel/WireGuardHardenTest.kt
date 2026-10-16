package org.schabi.newpipe.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardHardenTest {
    private val pkg = "io.github.srocupado.tubetube"

    private fun harden(text: String) = WireGuardController.harden(text, pkg)

    private fun linesWith(text: String, key: String) = text.lines().filter { it.substringBefore('=').trim().equals(key, ignoreCase = true) }

    @Test
    fun mullvadStyleConfigIsRestrictedToThisApp() {
        val out = harden(
            """
            [Interface]
            PrivateKey = aaaa
            Address = 10.64.1.2/32,fc00:bbbb::2/128
            DNS = 10.64.0.1

            [Peer]
            PublicKey = bbbb
            AllowedIPs = 0.0.0.0/0,::0/0
            Endpoint = 1.2.3.4:51820
            """.trimIndent()
        )
        assertEquals(listOf("IncludedApplications = $pkg"), linesWith(out, "IncludedApplications"))
        assertEquals(listOf("DNS = 10.64.0.1"), linesWith(out, "DNS"))
        assertEquals(listOf("AllowedIPs = 0.0.0.0/0, ::/0"), linesWith(out, "AllowedIPs"))
        assertTrue(out.contains("Endpoint = 1.2.3.4:51820"))
    }

    @Test
    fun existingAppListsAreReplaced() {
        val out = harden(
            """
            [Interface]
            PrivateKey = aaaa
            Address = 10.0.0.2/32
            ExcludedApplications = $pkg
            IncludedApplications = com.other.app
            [Peer]
            PublicKey = bbbb
            AllowedIPs = 0.0.0.0/0
            """.trimIndent()
        )
        assertTrue(linesWith(out, "ExcludedApplications").isEmpty())
        assertEquals(listOf("IncludedApplications = $pkg"), linesWith(out, "IncludedApplications"))
    }

    @Test
    fun splitTunnelBecomesFullTunnelIncludingIpv6() {
        val out = harden(
            """
            [Interface]
            PrivateKey = aaaa
            Address = 10.0.0.2/32
            [Peer]
            PublicKey = bbbb
            AllowedIPs = 10.0.0.0/8
            AllowedIPs = 192.168.0.0/16
            """.trimIndent()
        )
        assertEquals(listOf("AllowedIPs = 0.0.0.0/0, ::/0"), linesWith(out, "AllowedIPs"))
    }

    @Test
    fun peerWithoutAllowedIpsGetsFullTunnel() {
        val out = harden(
            """
            [Interface]
            PrivateKey = aaaa
            Address = 10.0.0.2/32
            [Peer]
            PublicKey = bbbb
            Endpoint = 1.2.3.4:51820
            """.trimIndent()
        )
        assertEquals(listOf("AllowedIPs = 0.0.0.0/0, ::/0"), linesWith(out, "AllowedIPs"))
    }

    @Test
    fun onlyTheFirstPeerGetsRoutes() {
        val out = harden(
            """
            [Interface]
            PrivateKey = aaaa
            Address = 10.0.0.2/32
            [Peer]
            PublicKey = first
            AllowedIPs = 0.0.0.0/0
            [Peer]
            PublicKey = second
            AllowedIPs = 0.0.0.0/0
            """.trimIndent()
        )
        assertEquals(1, linesWith(out, "AllowedIPs").size)
        val firstPeer = out.substringAfter("PublicKey = first").substringBefore("[Peer]")
        assertTrue(firstPeer.contains("AllowedIPs"))
    }

    @Test
    fun missingDnsIsAdded() {
        val out = harden(
            """
            [Interface]
            PrivateKey = aaaa
            Address = 10.0.0.2/32
            [Peer]
            PublicKey = bbbb
            AllowedIPs = 0.0.0.0/0
            """.trimIndent()
        )
        val dns = linesWith(out, "DNS")
        assertEquals(1, dns.size)
        assertFalse(dns[0].substringAfter('=').isBlank())
        // DNS and the app list stay inside [Interface]
        val iface = out.substringBefore("[Peer]")
        assertTrue(iface.contains("DNS =") && iface.contains("IncludedApplications ="))
    }
}

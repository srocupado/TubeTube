package org.schabi.newpipe.tunnel

import android.content.Context
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R

/**
 * How TubeTube reaches the network. In TOR and VPN modes every request leaves the device
 * through the tunnel, or it does not leave at all. DIRECT is an explicit opt-out.
 */
enum class TunnelMode(val prefValue: String) {
    /** Embedded Tor client. Maximum anonymity, slower. */
    TOR("tor"),

    /** Embedded WireGuard tunnel restricted to this app. Fast, trusts the VPN provider. */
    VPN("vpn"),

    /** No tunnel: fastest, but YouTube/Google see the device's IP address. Opt-in only. */
    DIRECT("direct");

    companion object {
        @JvmStatic
        fun fromPreferences(context: Context): TunnelMode {
            val value = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(context.getString(R.string.tunnel_mode_key), TOR.prefValue)
            return entries.firstOrNull { it.prefValue == value } ?: TOR
        }
    }
}

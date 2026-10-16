package org.schabi.newpipe.tunnel

import android.content.Context
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R

/**
 * How TubeTube reaches the network. There is intentionally no "direct" mode: every request
 * leaves the device through one of these tunnels, or it does not leave at all.
 */
enum class TunnelMode(val prefValue: String) {
    /** Embedded Tor client. Maximum anonymity, slower. */
    TOR("tor"),

    /** Embedded WireGuard tunnel restricted to this app. Fast, trusts the VPN provider. */
    VPN("vpn");

    companion object {
        @JvmStatic
        fun fromPreferences(context: Context): TunnelMode {
            val value = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(context.getString(R.string.tunnel_mode_key), TOR.prefValue)
            return entries.firstOrNull { it.prefValue == value } ?: TOR
        }
    }
}

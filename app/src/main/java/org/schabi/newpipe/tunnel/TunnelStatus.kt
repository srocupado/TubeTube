package org.schabi.newpipe.tunnel

/** Snapshot of the tunnel, published by [TunnelManager.status]. */
data class TunnelStatus(
    val mode: TunnelMode,
    val state: State,
    /** Human readable detail (bootstrap progress, error message...), may be empty. */
    val detail: String = ""
) {
    enum class State {
        /** Tunnel is starting; all traffic is blocked meanwhile. */
        CONNECTING,

        /** Tunnel is up; traffic flows through it. */
        CONNECTED,

        /** Tunnel needs user action (VPN permission, missing WireGuard config). Blocked. */
        NEEDS_SETUP,

        /** Tunnel failed or dropped. Blocked. */
        ERROR
    }

    val isConnected: Boolean get() = state == State.CONNECTED
}

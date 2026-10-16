package org.schabi.newpipe.tunnel

import java.io.IOException

/**
 * Thrown instead of performing a request when the privacy tunnel is not connected.
 * It is an [IOException] so that every caller already treats it as a network error.
 */
class TunnelUnavailableException(status: TunnelStatus) :
    IOException("Privacy tunnel not connected (${status.mode}: ${status.state})")

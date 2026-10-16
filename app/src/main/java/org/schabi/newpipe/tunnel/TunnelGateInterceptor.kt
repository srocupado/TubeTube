package org.schabi.newpipe.tunnel

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Refuses OkHttp calls while the tunnel is down, with a clear error instead of a refused
 * connection. [TunnelProxySelector] blocks them anyway; this just fails earlier and louder.
 */
object TunnelGateInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val status = TunnelManager.currentStatus
        if (!status.isConnected) {
            throw TunnelUnavailableException(status)
        }
        return chain.proceed(chain.request())
    }
}

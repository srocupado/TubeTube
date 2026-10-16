package org.schabi.newpipe.tunnel

import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor

/**
 * WebViews have their own (Chromium) network stack, which ignores [TunnelProxySelector].
 * Any WebView that loads remote content must be opened through [protect].
 *
 * - VPN mode: the OS already routes this app's sockets, WebView included, into the tunnel.
 * - Tor mode: all WebViews of the app are pointed at Tor's HTTP CONNECT port with
 *   [ProxyController]. Without that feature (very old WebView), remote content is refused.
 */
object WebViewTunnel {
    private const val TAG = "WebViewTunnel"
    private val mainExecutor = Executor { it.run() }

    /**
     * Runs [onReady] once remote content loaded by WebViews will go through the tunnel, or
     * [onRefused] if that cannot be guaranteed right now. Call on the main thread.
     */
    @JvmStatic
    fun protect(onReady: Runnable, onRefused: Runnable) {
        val status = TunnelManager.currentStatus
        if (!status.isConnected) {
            onRefused.run()
            return
        }
        val supported = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        when (status.mode) {
            TunnelMode.VPN -> if (supported) {
                // drop a Tor override left over from a previous mode
                ProxyController.getInstance().clearProxyOverride(mainExecutor, onReady)
            } else {
                onReady.run()
            }

            TunnelMode.TOR -> {
                val port = TunnelManager.torHttpPortIfConnected()
                if (!supported || port <= 0) {
                    Log.w(TAG, "Cannot route WebViews through Tor (supported=$supported)")
                    onRefused.run()
                    return
                }
                val config = ProxyConfig.Builder()
                    .addProxyRule("127.0.0.1:$port")
                    .build()
                ProxyController.getInstance().setProxyOverride(config, mainExecutor, onReady)
            }
        }
    }
}

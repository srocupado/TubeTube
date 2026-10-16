package org.schabi.newpipe.tunnel

import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * A loopback "proxy" that accepts connections and closes them immediately.
 *
 * While the tunnel is down every request is routed here, so it fails right away instead of
 * leaking out of the device or hanging until a timeout.
 */
internal object Blackhole {
    private const val TAG = "TunnelBlackhole"

    @Volatile
    private var serverSocket: ServerSocket? = null

    val proxy: Proxy by lazy {
        Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getLoopbackAddress(), start()))
    }

    private fun start(): Int {
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        serverSocket = socket
        thread(name = "tunnel-blackhole", isDaemon = true) {
            while (!socket.isClosed) {
                try {
                    socket.accept().close()
                } catch (e: IOException) {
                    Log.w(TAG, "accept failed", e)
                }
            }
        }
        return socket.localPort
    }
}

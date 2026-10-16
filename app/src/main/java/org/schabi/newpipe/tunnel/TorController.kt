package org.schabi.newpipe.tunnel

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.concurrent.Executors
import org.torproject.jni.TorService

/**
 * Runs the embedded Tor client ([TorService], in this process) and reports its HTTP tunnel port.
 * All app traffic is sent to that port as HTTP CONNECT, so host names are resolved by Tor and
 * never by the local DNS resolver.
 */
internal class TorController(
    private val context: Context,
    private val onStatus: (TunnelStatus, Int) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "tor-controller") }

    // Kept as IBinder: tor-android ships Java 21 class files and kapt (Java 17 here) cannot
    // read TorService if it appears in a signature. Function bodies are fine.
    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var torIsOn = false

    @Volatile
    private var stopped = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            resolvePort()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            if (!stopped) {
                publish(TunnelStatus.State.ERROR, "Tor service died")
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                TorService.ACTION_STATUS -> {
                    when (intent.getStringExtra(TorService.EXTRA_STATUS)) {
                        // ON is only broadcast once the first circuit is established
                        TorService.STATUS_ON -> {
                            torIsOn = true
                            resolvePort()
                        }

                        TorService.STATUS_STARTING -> {
                            torIsOn = false
                            publish(TunnelStatus.State.CONNECTING)
                        }

                        else -> {
                            torIsOn = false
                            if (!stopped) {
                                publish(TunnelStatus.State.CONNECTING)
                            }
                        }
                    }
                }

                TorService.ACTION_ERROR -> publish(
                    TunnelStatus.State.ERROR,
                    intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                )
            }
        }
    }

    fun start() {
        stopped = false
        publish(TunnelStatus.State.CONNECTING)
        TorService.getTorrc(context).writeText(TORRC)
        val filter = IntentFilter().apply {
            addAction(TorService.ACTION_STATUS)
            addAction(TorService.ACTION_ERROR)
        }
        LocalBroadcastManager.getInstance(context).registerReceiver(receiver, filter)
        context.bindService(
            Intent(context, TorService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    fun stop() {
        stopped = true
        LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver)
        try {
            context.unbindService(connection)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Tor service was not bound", e)
        }
        context.stopService(Intent(context, TorService::class.java))
        binder = null
        torIsOn = false
        executor.shutdown()
    }

    /**
     * Asks Tor for new circuits (and therefore new exit IPs) for future connections.
     */
    fun newIdentity() {
        runAsync {
            try {
                val service = (binder as? TorService.LocalBinder)?.service
                service?.torControlConnection?.signal("NEWNYM")
            } catch (e: Exception) {
                Log.w(TAG, "NEWNYM failed", e)
            }
        }
    }

    private fun resolvePort() {
        runAsync {
            val svc = (binder as? TorService.LocalBinder)?.service
            if (stopped || svc == null || !torIsOn) {
                return@runAsync
            }
            // the port is read via the control connection, which blocks: not on the main thread
            val port = try {
                svc.httpTunnelPort
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the HTTP tunnel port", e)
                -1
            }
            if (port > 0) {
                onStatus(TunnelStatus(TunnelMode.TOR, TunnelStatus.State.CONNECTED), port)
            } else {
                publish(TunnelStatus.State.ERROR, "Tor has no HTTP tunnel port")
            }
        }
    }

    private fun runAsync(task: () -> Unit) {
        if (!executor.isShutdown) {
            executor.execute(task)
        }
    }

    private fun publish(state: TunnelStatus.State, detail: String = "") {
        onStatus(TunnelStatus(TunnelMode.TOR, state, detail), -1)
    }

    companion object {
        private const val TAG = "TorController"

        /**
         * Rewritten on every start. Tuned for streaming video over Tor:
         * - Conflux joins two circuit legs ending at the same exit: more bandwidth, same exit IP.
         * - YouTube stream URLs are bound to the IP that requested them, so circuits are kept
         *   for 30 minutes instead of 10: a video does not switch exit (and fail) mid-playback.
         */
        private val TORRC = """
            SOCKSPort auto
            HTTPTunnelPort auto
            ConfluxEnabled 1
            MaxCircuitDirtiness 1800
            KeepalivePeriod 60
            ClientUseIPv6 1
            AvoidDiskWrites 1
        """.trimIndent() + "\n"
    }
}

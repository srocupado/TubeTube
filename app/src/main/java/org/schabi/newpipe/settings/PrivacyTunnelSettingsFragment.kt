package org.schabi.newpipe.settings

import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.IOException
import org.schabi.newpipe.R
import org.schabi.newpipe.tunnel.TunnelManager
import org.schabi.newpipe.tunnel.TunnelMode
import org.schabi.newpipe.tunnel.TunnelStatus
import org.schabi.newpipe.tunnel.WireGuardController

class PrivacyTunnelSettingsFragment : BasePreferenceFragment(), OnSharedPreferenceChangeListener {

    private val disposables = CompositeDisposable()

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::importWireGuardConfig)
        }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val context = context ?: return@registerForActivityResult
            if (VpnService.prepare(context) == null) {
                TunnelManager.restart()
            } else {
                Toast.makeText(context, R.string.tunnel_vpn_permission_denied, Toast.LENGTH_LONG)
                    .show()
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResourceRegistry()

        requirePreference<Preference>(R.string.tunnel_new_identity_key).setOnPreferenceClickListener {
            TunnelManager.newTorIdentity()
            Toast.makeText(it.context, R.string.tunnel_new_identity_done, Toast.LENGTH_SHORT).show()
            true
        }
        requirePreference<Preference>(R.string.tunnel_wireguard_import_key).setOnPreferenceClickListener {
            // providers export .conf files with all sorts of MIME types
            importLauncher.launch(arrayOf("*/*"))
            true
        }
        requirePreference<Preference>(R.string.tunnel_vpn_permission_key).setOnPreferenceClickListener {
            restartTunnelAskingVpnPermission()
            true
        }
        requirePreference<Preference>(R.string.tunnel_wireguard_delete_key).setOnPreferenceClickListener {
            WireGuardController.deleteConfig(it.context)
            Toast.makeText(it.context, R.string.tunnel_wireguard_delete_done, Toast.LENGTH_SHORT)
                .show()
            if (TunnelMode.fromPreferences(it.context) == TunnelMode.VPN) {
                TunnelManager.restart()
            }
            updatePreferences(TunnelManager.currentStatus)
            true
        }
    }

    override fun onStart() {
        super.onStart()
        defaultPreferences.registerOnSharedPreferenceChangeListener(this)
        disposables.add(
            TunnelManager.status
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(::updatePreferences)
        )
    }

    override fun onStop() {
        defaultPreferences.unregisterOnSharedPreferenceChangeListener(this)
        disposables.clear()
        super.onStop()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == getString(R.string.tunnel_mode_key)) {
            restartTunnelAskingVpnPermission()
        }
    }

    /**
     * Restarts the tunnel; in VPN mode also asks for the VPN permission when it is missing
     * (the tunnel is restarted again once it is granted).
     */
    private fun restartTunnelAskingVpnPermission() {
        val context = context ?: return
        TunnelManager.restart()
        if (TunnelMode.fromPreferences(context) == TunnelMode.VPN &&
            WireGuardController.hasConfig(context)
        ) {
            VpnService.prepare(context)?.let { vpnPermissionLauncher.launch(it) }
        }
    }

    private fun importWireGuardConfig(uri: Uri) {
        val context = requireContext().applicationContext
        disposables.add(
            Single.fromCallable {
                val text = context.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    if (bytes.size > MAX_CONFIG_BYTES) {
                        throw IOException("file too large")
                    }
                    String(bytes, Charsets.UTF_8)
                } ?: throw IOException("cannot open file")
                WireGuardController.importConfig(context, text)
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    {
                        Toast.makeText(
                            context,
                            R.string.tunnel_wireguard_import_done,
                            Toast.LENGTH_SHORT
                        ).show()
                        if (TunnelMode.fromPreferences(context) == TunnelMode.VPN) {
                            restartTunnelAskingVpnPermission()
                        }
                        updatePreferences(TunnelManager.currentStatus)
                    },
                    { e ->
                        Toast.makeText(
                            context,
                            context.getString(R.string.tunnel_wireguard_import_failed, e.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
        )
    }

    private fun updatePreferences(status: TunnelStatus) {
        val context = context ?: return
        val mode = TunnelMode.fromPreferences(context)
        val hasConfig = WireGuardController.hasConfig(context)

        requirePreference<Preference>(R.string.tunnel_status_key).summary = when (status.state) {
            TunnelStatus.State.CONNECTING -> getString(R.string.tunnel_status_connecting)

            TunnelStatus.State.CONNECTED ->
                if (status.mode == TunnelMode.DIRECT) {
                    getString(R.string.tunnel_status_direct)
                } else {
                    getString(R.string.tunnel_status_connected)
                }

            TunnelStatus.State.ERROR -> getString(R.string.tunnel_status_error, status.detail)

            TunnelStatus.State.NEEDS_SETUP ->
                if (status.detail == WireGuardController.DETAIL_NO_PERMISSION) {
                    getString(R.string.tunnel_status_needs_permission)
                } else {
                    getString(R.string.tunnel_status_needs_config)
                }
        }

        requirePreference<Preference>(R.string.tunnel_new_identity_key).isVisible =
            mode == TunnelMode.TOR
        requirePreference<Preference>(R.string.tunnel_wireguard_import_key).summary = getString(
            if (hasConfig) {
                R.string.tunnel_wireguard_import_summary_present
            } else {
                R.string.tunnel_wireguard_import_summary
            }
        )
        requirePreference<Preference>(R.string.tunnel_vpn_permission_key).isVisible =
            hasConfig && VpnService.prepare(context) != null
        requirePreference<Preference>(R.string.tunnel_wireguard_delete_key).isVisible = hasConfig
    }

    companion object {
        private const val MAX_CONFIG_BYTES = 64 * 1024
    }
}

package id.altair.musevpn

import android.content.Context
import android.content.Intent
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Satu pintu untuk backend WireGuard. State dibagikan lewat StateFlow supaya
 * UI bisa langsung mengunci browser ketika tunnel turun (kill switch).
 */
object TunnelManager {

    private var backend: Backend? = null
    private var tunnel: Tunnel? = null

    private val _state = MutableStateFlow(Tunnel.State.DOWN)
    val state: StateFlow<Tunnel.State> = _state

    @Synchronized
    fun init(context: Context) {
        val app = context.applicationContext
        if (backend == null) backend = GoBackend(app)
        if (tunnel == null) {
            tunnel = object : Tunnel {
                override fun getName(): String = "MuseVpnAppOnly"
                override fun onStateChange(newState: Tunnel.State) {
                    _state.value = newState
                }
            }
        }
    }

    fun prepareIntent(context: Context): Intent? = GoBackend.VpnService.prepare(context)

    /** Blocking: panggil dari Dispatchers.IO. */
    @Throws(Exception::class)
    fun connect(context: Context, config: Config) {
        init(context)
        backend!!.setState(tunnel!!, Tunnel.State.UP, config)
        _state.value = Tunnel.State.UP
    }

    fun disconnect() {
        val b = backend ?: return
        val t = tunnel ?: return
        try {
            b.setState(t, Tunnel.State.DOWN, null)
        } catch (_: Exception) {
        }
        _state.value = Tunnel.State.DOWN
    }
}

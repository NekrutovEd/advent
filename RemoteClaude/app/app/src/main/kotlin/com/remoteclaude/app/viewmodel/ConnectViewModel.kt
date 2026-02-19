package com.remoteclaude.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remoteclaude.app.data.mdns.DiscoveredServer
import com.remoteclaude.app.data.mdns.MdnsDiscovery
import com.remoteclaude.app.data.mdns.UdpBroadcastDiscovery
import com.remoteclaude.app.data.net.WifiNetworkProvider
import com.remoteclaude.app.data.ws.WsClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val TAG = "RC_DEBUG"

class ConnectViewModel(application: Application) : AndroidViewModel(application) {

    private val wifiNetwork = WifiNetworkProvider.getWifiNetwork(application)
    private val wifiSocketFactory = wifiNetwork?.socketFactory

    private val mdns = MdnsDiscovery(application)
    private val udp = UdpBroadcastDiscovery(wifiNetwork)
    val wsClient = WsClient(wifiSocketFactory)

    val servers: StateFlow<List<DiscoveredServer>> = combine(mdns.servers, udp.servers) { m, u ->
        (m + u).distinctBy { it.host }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _navigateToTerminal = MutableSharedFlow<Unit>()
    val navigateToTerminal: SharedFlow<Unit> = _navigateToTerminal

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var connectJob: Job? = null

    init {
        Log.d(TAG, "ConnectVM: init, wifiNetwork=$wifiNetwork")
        mdns.startDiscovery(viewModelScope)
        udp.start(viewModelScope)
        viewModelScope.launch {
            wsClient.connectionState.collect { state ->
                Log.d(TAG, "ConnectVM: connectionState changed to ${state::class.simpleName}")
                when (state) {
                    is WsClient.ConnectionState.Connected -> {
                        Log.d(TAG, "ConnectVM: Connected -> navigating to terminal")
                        _navigateToTerminal.emit(Unit)
                    }
                    is WsClient.ConnectionState.Error -> {
                        Log.e(TAG, "ConnectVM: Error: ${state.message}")
                        _error.value = state.message
                    }
                    is WsClient.ConnectionState.Reconnecting -> {
                        Log.d(TAG, "ConnectVM: Reconnecting attempt=${state.attempt}")
                    }
                    else -> {}
                }
            }
        }
    }

    fun connect(server: DiscoveredServer) = connect(server.host, server.port)

    fun connect(host: String, port: Int) {
        Log.d(TAG, "ConnectVM: connect($host, $port)")
        _error.value = null
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            wsClient.connectWithRetry(host, port)
        }
    }

    fun cancelReconnect() {
        Log.d(TAG, "ConnectVM: cancelReconnect()")
        connectJob?.cancel()
        connectJob = null
    }

    override fun onCleared() {
        mdns.stopDiscovery()
        udp.stop()
        wsClient.close()
    }
}

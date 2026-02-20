package com.remoteclaude.app.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

private const val TAG = "RC_DEBUG"
private const val DEBOUNCE_MS = 500L

class NetworkMonitor(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _networkChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val networkChanged: SharedFlow<Unit> = _networkChanged

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var debounceJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (callback != null) return

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "NetworkMonitor: onAvailable $network")
                emitDebounced(scope)
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "NetworkMonitor: onLost $network")
                emitDebounced(scope)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                Log.d(TAG, "NetworkMonitor: onCapabilitiesChanged $network")
                emitDebounced(scope)
            }
        }

        callback = cb
        cm.registerDefaultNetworkCallback(cb)
        Log.d(TAG, "NetworkMonitor: started")
    }

    fun stop() {
        callback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        callback = null
        debounceJob?.cancel()
        debounceJob = null
        Log.d(TAG, "NetworkMonitor: stopped")
    }

    private fun emitDebounced(scope: CoroutineScope) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            Log.d(TAG, "NetworkMonitor: emitting networkChanged")
            _networkChanged.emit(Unit)
        }
    }
}

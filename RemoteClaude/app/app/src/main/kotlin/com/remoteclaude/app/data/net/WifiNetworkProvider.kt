package com.remoteclaude.app.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import javax.net.SocketFactory

private const val TAG = "RC_DEBUG"

/**
 * Finds the WiFi [Network] so that sockets can be bound to it directly,
 * bypassing any active VPN tunnel.
 */
object WifiNetworkProvider {

    fun getWifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        // Use NetworkRequest to find WiFi transport (non-deprecated approach)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        var result: Network? = null
        try {
            // registerNetworkCallback is async, but activeNetwork check is immediate
            val activeNetwork = cm.activeNetwork
            val activeCaps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
            if (activeCaps != null &&
                activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                Log.d(TAG, "WifiNetworkProvider: active network is WiFi=$activeNetwork")
                return activeNetwork
            }
            // Active network is VPN or cellular — search via callback synchronously
            val latch = java.util.concurrent.CountDownLatch(1)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = cm.getNetworkCapabilities(network)
                    if (caps != null &&
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    ) {
                        result = network
                        latch.countDown()
                    }
                }
            }
            cm.registerNetworkCallback(request, callback)
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            cm.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "WifiNetworkProvider: error finding WiFi network: ${e.message}")
        }
        if (result != null) {
            Log.d(TAG, "WifiNetworkProvider: found WiFi network=$result")
        } else {
            Log.w(TAG, "WifiNetworkProvider: no WiFi network found, falling back to default")
        }
        return result
    }

    fun getSocketFactory(context: Context): SocketFactory {
        val wifi = getWifiNetwork(context)
        return wifi?.socketFactory ?: SocketFactory.getDefault()
    }
}

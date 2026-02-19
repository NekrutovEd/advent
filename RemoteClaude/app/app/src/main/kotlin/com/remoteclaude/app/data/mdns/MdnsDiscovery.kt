package com.remoteclaude.app.data.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "RemoteClaude"
private const val EXPIRY_CHECK_INTERVAL_MS = 30_000L
private const val EXPIRY_TTL_MS = 60_000L

data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int,
) {
    val wsUrl get() = "ws://$host:$port/app"
    val displayName get() = name.removePrefix("RemoteClaude@").take(30)
}

class MdnsDiscovery(context: Context) {

    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private var expiryJob: Job? = null

    fun startDiscovery(scope: CoroutineScope) {
        if (discoveryListener != null) {
            Log.d(TAG, "mDNS discovery already running, skipping")
            return
        }

        // Acquire multicast lock — without it Android Wi-Fi drops mDNS packets on some devices
        multicastLock = wifiManager.createMulticastLock("RemoteClaude-mDNS").apply {
            setReferenceCounted(false)
            acquire()
            Log.d(TAG, "mDNS: multicast lock acquired")
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "mDNS: discovery started for $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "mDNS: discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "mDNS: START DISCOVERY FAILED for $serviceType, errorCode=$errorCode")
                discoveryListener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS: stop discovery failed for $serviceType, errorCode=$errorCode")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "mDNS: service found: name=${serviceInfo.serviceName} type=${serviceInfo.serviceType}")
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "mDNS: RESOLVE FAILED for ${info.serviceName}, errorCode=$errorCode")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress
                        Log.d(TAG, "mDNS: resolved ${info.serviceName} -> host=$host port=${info.port}")
                        if (host == null) {
                            Log.w(TAG, "mDNS: host is null for ${info.serviceName}, skipping")
                            return
                        }
                        lastSeen[host] = System.currentTimeMillis()
                        val server = DiscoveredServer(
                            name = info.serviceName,
                            host = host,
                            port = info.port,
                        )
                        _servers.value = (_servers.value + server).distinctBy { it.host }
                        Log.d(TAG, "mDNS: server list updated -> ${_servers.value.map { it.wsUrl }}")
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "mDNS: service lost: ${serviceInfo.serviceName}")
                val removed = _servers.value.filter { it.name == serviceInfo.serviceName }
                removed.forEach { lastSeen.remove(it.host) }
                _servers.value = _servers.value.filter { it.name != serviceInfo.serviceName }
            }
        }

        discoveryListener = listener
        Log.d(TAG, "mDNS: starting discovery for _claudeserver._tcp")
        nsdManager.discoverServices("_claudeserver._tcp", NsdManager.PROTOCOL_DNS_SD, listener)

        expiryJob = scope.launch {
            while (true) {
                delay(EXPIRY_CHECK_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val expired = lastSeen.entries.filter { now - it.value > EXPIRY_TTL_MS }.map { it.key }
                if (expired.isNotEmpty()) {
                    Log.d(TAG, "mDNS: expiring servers: $expired")
                    expired.forEach { lastSeen.remove(it) }
                    _servers.value = _servers.value.filter { it.host !in expired }
                }
            }
        }
    }

    fun stopDiscovery() {
        Log.d(TAG, "mDNS: stopping discovery")
        expiryJob?.cancel()
        expiryJob = null
        multicastLock?.release()
        multicastLock = null
        discoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        discoveryListener = null
    }
}

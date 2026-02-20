package com.remoteclaude.app.data.mdns

import android.net.Network
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

private const val TAG = "RemoteClaude"
private const val UDP_PORT = 19872
private const val PROBE_PORT = 19873
private const val PROBE_INTERVAL_MS = 5_000L
private const val EXPIRY_MS = 15_000L

/**
 * UDP broadcast discovery with passive listening + active probing.
 * Works well without VPN. With VPN active, Network.bindSocket() fails
 * with EPERM — sockets still work without WiFi binding but may not
 * receive broadcasts. SubnetScanner provides TCP-based fallback.
 */
class UdpBroadcastDiscovery(private var wifiNetwork: Network? = null) {

    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers

    private var listenJob: Job? = null
    private var probeJob: Job? = null

    // Track last-seen timestamp per host for expiry
    private val lastSeen = mutableMapOf<String, Long>()

    fun start(scope: CoroutineScope) {
        startPassiveListener(scope)
        startActiveProber(scope)
    }

    fun stop() {
        listenJob?.cancel()
        listenJob = null
        probeJob?.cancel()
        probeJob = null
    }

    fun restart(scope: CoroutineScope, newWifiNetwork: Network?) {
        Log.d(TAG, "UDP: restarting with newWifiNetwork=$newWifiNetwork")
        stop()
        wifiNetwork = newWifiNetwork
        _servers.value = emptyList()
        lastSeen.clear()
        start(scope)
    }

    /** Passive: listen for server broadcast packets on port 19872 */
    private fun startPassiveListener(scope: CoroutineScope) {
        if (listenJob != null) return
        listenJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "UDP passive: starting listener on port $UDP_PORT")
            try {
                val socket = DatagramSocket(UDP_PORT).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 5000
                }
                val network = wifiNetwork
                if (network != null) {
                    try {
                        network.bindSocket(socket)
                        Log.d(TAG, "UDP passive: socket bound to WiFi network")
                    } catch (e: Exception) {
                        Log.w(TAG, "UDP passive: bindSocket failed (VPN active?): ${e.message}")
                    }
                }
                val buf = ByteArray(512)
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        parsePacket(message, "passive")
                    } catch (_: SocketTimeoutException) {
                        // Normal
                    }
                    expireStaleEntries()
                }
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "UDP passive: listener failed: ${e.message}")
            }
        }
    }

    /**
     * Active: periodically send probe packets to broadcast:19873.
     * Server responds with unicast "RC|name|ip|port" back to us.
     */
    private fun startActiveProber(scope: CoroutineScope) {
        if (probeJob != null) return
        probeJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "UDP probe: starting active prober")
            try {
                val socket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 3000
                }
                val network = wifiNetwork
                if (network != null) {
                    try {
                        network.bindSocket(socket)
                        Log.d(TAG, "UDP probe: socket bound to WiFi network")
                    } catch (e: Exception) {
                        Log.w(TAG, "UDP probe: bindSocket failed (VPN active?): ${e.message}")
                    }
                }

                val probeData = "RC_PROBE".toByteArray(Charsets.UTF_8)
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val buf = ByteArray(512)

                while (isActive) {
                    try {
                        val probe = DatagramPacket(probeData, probeData.size, broadcastAddr, PROBE_PORT)
                        socket.send(probe)
                        Log.d(TAG, "UDP probe: sent to 255.255.255.255:$PROBE_PORT")
                    } catch (e: Exception) {
                        Log.w(TAG, "UDP probe: send failed: ${e.message}")
                    }

                    val deadline = System.currentTimeMillis() + PROBE_INTERVAL_MS
                    while (isActive && System.currentTimeMillis() < deadline) {
                        try {
                            val packet = DatagramPacket(buf, buf.size)
                            socket.receive(packet)
                            val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            parsePacket(message, "probe")
                        } catch (_: SocketTimeoutException) {
                            // Normal
                        }
                    }
                }
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "UDP probe: failed: ${e.message}")
            }
        }
    }

    private fun parsePacket(message: String, source: String) {
        val parts = message.split("|")
        if (parts.size != 4 || parts[0] != "RC") return
        val name = parts[1]
        val host = parts[2]
        val port = parts[3].toIntOrNull() ?: return

        Log.d(TAG, "UDP $source: received $name at $host:$port")

        lastSeen[host] = System.currentTimeMillis()
        val server = DiscoveredServer(name = name, host = host, port = port)
        val current = _servers.value.toMutableList()
        val idx = current.indexOfFirst { it.host == host }
        if (idx >= 0) current[idx] = server else current.add(server)
        _servers.value = current
    }

    private fun expireStaleEntries() {
        val now = System.currentTimeMillis()
        val expired = lastSeen.filter { now - it.value > EXPIRY_MS }.keys
        if (expired.isEmpty()) return
        expired.forEach { lastSeen.remove(it) }
        _servers.value = _servers.value.filter { it.host !in expired }
        if (expired.isNotEmpty()) Log.d(TAG, "UDP: expired servers: $expired")
    }
}

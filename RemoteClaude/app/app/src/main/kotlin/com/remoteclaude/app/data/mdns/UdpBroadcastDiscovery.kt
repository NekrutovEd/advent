package com.remoteclaude.app.data.mdns

import android.net.Network
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

private const val TAG = "RemoteClaude"
private const val UDP_PORT = 19872
private const val EXPIRY_MS = 15_000L

class UdpBroadcastDiscovery(private val wifiNetwork: Network? = null) {

    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers

    private var job: Job? = null

    // Track last-seen timestamp per host for expiry
    private val lastSeen = mutableMapOf<String, Long>()

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "UDP: starting listener on port $UDP_PORT")
            try {
                val socket = DatagramSocket(UDP_PORT).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 5000
                }
                // Bind socket to WiFi network to bypass VPN
                if (wifiNetwork != null) {
                    wifiNetwork.bindSocket(socket)
                    Log.d(TAG, "UDP: socket bound to WiFi network")
                }
                val buf = ByteArray(512)
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        parsePacket(message)
                    } catch (_: SocketTimeoutException) {
                        // Normal — check expiry
                    }
                    expireStaleEntries()
                }
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "UDP: listener failed: ${e.message}")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun parsePacket(message: String) {
        // Format: RC|instanceName|ip|port
        val parts = message.split("|")
        if (parts.size != 4 || parts[0] != "RC") return
        val name = parts[1]
        val host = parts[2]
        val port = parts[3].toIntOrNull() ?: return

        Log.d(TAG, "UDP: received $name at $host:$port")

        lastSeen[host] = System.currentTimeMillis()
        val server = DiscoveredServer(name = name, host = host, port = port)
        val current = _servers.value.toMutableList()
        val idx = current.indexOfFirst { it.host == host }
        if (idx >= 0) {
            current[idx] = server
        } else {
            current.add(server)
        }
        _servers.value = current
    }

    private fun expireStaleEntries() {
        val now = System.currentTimeMillis()
        val expired = lastSeen.filter { now - it.value > EXPIRY_MS }.keys
        if (expired.isEmpty()) return
        expired.forEach { lastSeen.remove(it) }
        _servers.value = _servers.value.filter { it.host !in expired }
        if (expired.isNotEmpty()) {
            Log.d(TAG, "UDP: expired servers: $expired")
        }
    }
}

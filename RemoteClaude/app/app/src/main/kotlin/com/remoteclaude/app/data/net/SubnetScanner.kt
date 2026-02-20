package com.remoteclaude.app.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.util.Log
import com.remoteclaude.app.data.mdns.DiscoveredServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import javax.net.SocketFactory

private const val TAG = "RemoteClaude"
private const val SERVER_PORT = 8765
private const val CONNECT_TIMEOUT_MS = 400
private const val CONCURRENCY = 50

class SubnetScanner(private val context: Context) {

    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private var job: Job? = null

    /** Run a single scan. Called manually from UI. */
    fun scanOnce(scope: CoroutineScope) {
        if (job != null) return // already scanning
        val wifiNetwork = WifiNetworkProvider.getWifiNetwork(context)
        val factory = wifiNetwork?.socketFactory ?: SocketFactory.getDefault()
        job = scope.launch(Dispatchers.IO) {
            _scanning.value = true
            try {
                scan(factory)
            } catch (e: Exception) {
                Log.w(TAG, "SubnetScanner: scan error: ${e.message}")
            } finally {
                _scanning.value = false
                job = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _scanning.value = false
    }

    private suspend fun scan(socketFactory: SocketFactory) {
        val targets = getSubnetAddresses() ?: run {
            Log.w(TAG, "SubnetScanner: could not determine subnet addresses")
            return
        }
        Log.d(TAG, "SubnetScanner: scanning ${targets.size} addresses on port $SERVER_PORT")

        val found = mutableListOf<DiscoveredServer>()
        coroutineScope {
            targets.chunked(CONCURRENCY).forEach { chunk ->
                val results = chunk.map { ip ->
                    async { probeHost(socketFactory, ip) }
                }.awaitAll()
                found.addAll(results.filterNotNull())
            }
        }

        if (found.isNotEmpty()) {
            Log.d(TAG, "SubnetScanner: found ${found.size} server(s)")
        } else {
            Log.d(TAG, "SubnetScanner: no servers found")
        }
        _servers.value = found
    }

    private fun probeHost(socketFactory: SocketFactory, ip: String): DiscoveredServer? {
        return try {
            val socket = socketFactory.createSocket()
            socket.connect(InetSocketAddress(ip, SERVER_PORT), CONNECT_TIMEOUT_MS)
            try {
                socket.soTimeout = 1000
                socket.getOutputStream().write(
                    "GET /discover HTTP/1.1\r\nHost: $ip\r\nConnection: close\r\n\r\n".toByteArray()
                )
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val response = StringBuilder()
                var line = reader.readLine()
                while (line != null) {
                    response.appendLine(line)
                    line = reader.readLine()
                }
                socket.close()

                val body = response.toString()
                val jsonStart = body.indexOf('{')
                if (jsonStart >= 0) {
                    val json = JSONObject(body.substring(jsonStart))
                    val name = json.optString("name", "RemoteClaude")
                    val port = json.optInt("port", SERVER_PORT)
                    Log.d(TAG, "SubnetScanner: found $name at $ip:$port")
                    DiscoveredServer(name = name, host = ip, port = port)
                } else null
            } catch (e: Exception) {
                socket.close()
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getSubnetAddresses(): List<String>? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val wifiNetwork = WifiNetworkProvider.getWifiNetwork(context) ?: return null
        val linkProps = cm.getLinkProperties(wifiNetwork) ?: return null

        val v4addr = linkProps.linkAddresses
            .firstOrNull { it.address is Inet4Address }
            ?: return null

        return computeSubnetIps(v4addr)
    }

    private fun computeSubnetIps(linkAddr: LinkAddress): List<String> {
        val addr = linkAddr.address as Inet4Address
        val bytes = addr.address
        val prefixLen = linkAddr.prefixLength
        val ipInt = (bytes[0].toInt() and 0xFF shl 24) or
                (bytes[1].toInt() and 0xFF shl 16) or
                (bytes[2].toInt() and 0xFF shl 8) or
                (bytes[3].toInt() and 0xFF)
        val mask = if (prefixLen == 0) 0 else (-1 shl (32 - prefixLen))
        val network = ipInt and mask
        val hostCount = (1 shl (32 - prefixLen)) - 2
        if (hostCount <= 0 || hostCount > 1024) return emptyList()

        val myIp = "${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}.${bytes[2].toInt() and 0xFF}.${bytes[3].toInt() and 0xFF}"
        val ips = mutableListOf<String>()
        for (i in 1..hostCount) {
            val hostIp = network + i
            val ip = "${(hostIp shr 24) and 0xFF}.${(hostIp shr 16) and 0xFF}.${(hostIp shr 8) and 0xFF}.${hostIp and 0xFF}"
            if (ip != myIp) ips.add(ip)
        }
        return ips
    }
}

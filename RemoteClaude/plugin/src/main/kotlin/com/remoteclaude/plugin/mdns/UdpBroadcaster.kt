package com.remoteclaude.plugin.mdns

import com.intellij.openapi.diagnostic.Logger
import java.net.*
import kotlin.concurrent.thread

class UdpBroadcaster {

    private val LOG = Logger.getInstance(UdpBroadcaster::class.java)

    private val BROADCAST_ADDRESS = InetAddress.getByName("255.255.255.255")
    private val BROADCAST_PORT = 19872

    @Volatile private var running = false
    private var broadcastThread: Thread? = null

    fun start(port: Int) {
        running = true
        broadcastThread = thread(name = "RemoteClaude-UDP", isDaemon = true) {
            try {
                val localAddr = getLocalAddress()
                if (localAddr == null) {
                    LOG.warn("RemoteClaude UDP: could not determine local address, broadcaster not started")
                    return@thread
                }

                val hostname = InetAddress.getLocalHost().hostName
                    .replace(Regex("[^a-zA-Z0-9-]"), "-")
                val instanceName = "RemoteClaude@$hostname"
                val payload = "RC|$instanceName|${localAddr.hostAddress}|$port"
                val data = payload.toByteArray(Charsets.UTF_8)

                LOG.info("RemoteClaude UDP: broadcasting '$payload' to $BROADCAST_ADDRESS:$BROADCAST_PORT")

                val socket = DatagramSocket().apply { broadcast = true }

                // Initial burst: 3 packets 1s apart
                repeat(3) { i ->
                    if (!running) return@thread
                    try {
                        socket.send(DatagramPacket(data, data.size, BROADCAST_ADDRESS, BROADCAST_PORT))
                        LOG.info("RemoteClaude UDP: initial broadcast ${i + 1}/3")
                    } catch (e: Exception) {
                        LOG.warn("RemoteClaude UDP: failed to send initial broadcast: ${e.message}")
                    }
                    if (running && i < 2) Thread.sleep(1000)
                }

                // Periodic broadcast every 5 seconds
                while (running) {
                    Thread.sleep(5000)
                    if (!running) break
                    try {
                        socket.send(DatagramPacket(data, data.size, BROADCAST_ADDRESS, BROADCAST_PORT))
                    } catch (e: Exception) {
                        LOG.warn("RemoteClaude UDP: failed to send broadcast: ${e.message}")
                    }
                }

                socket.close()
                LOG.info("RemoteClaude UDP: broadcast loop exited")
            } catch (e: Exception) {
                LOG.warn("RemoteClaude UDP: broadcaster thread failed: ${e.message}")
            }
        }
    }

    fun stop() {
        LOG.info("RemoteClaude UDP: stopping broadcaster")
        running = false
        broadcastThread?.interrupt()
        broadcastThread = null
    }

    private fun getLocalAddress(): Inet4Address? {
        val candidate = try {
            DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName("8.8.8.8"), 53)
                socket.localAddress as? Inet4Address
            }
        } catch (_: Exception) { null }

        if (candidate != null) {
            val iface = NetworkInterface.getByInetAddress(candidate)
            if (iface != null && !isVirtualAdapter(iface)) {
                return candidate
            }
        }

        return NetworkInterface.getNetworkInterfaces()?.asSequence()
            ?.filter { it.isUp && !it.isLoopback && !it.isVirtual && !isVirtualAdapter(it) }
            ?.sortedWith(compareBy { scoreInterface(it) })
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull()
    }

    private fun isVirtualAdapter(iface: NetworkInterface): Boolean {
        val name = (iface.displayName + " " + iface.name).lowercase()
        val virtualKeywords = listOf("tap", "vpn", "virtual", "hyper-v", "vmware", "vbox", "wsl", "loopback", "tunnel", "tun", "pptp", "l2tp", "wireguard")
        return virtualKeywords.any { name.contains(it) }
    }

    private fun scoreInterface(iface: NetworkInterface): Int {
        val name = (iface.displayName + " " + iface.name).lowercase()
        return when {
            name.contains("ethernet") || name.contains("local area connection") -> 0
            name.contains("wi-fi") || name.contains("wifi") || name.contains("wireless") || name.contains("wlan") -> 1
            else -> 2
        }
    }
}

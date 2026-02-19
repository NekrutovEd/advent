package com.remoteclaude.server.discovery

import com.remoteclaude.server.net.NetworkUtils
import org.slf4j.LoggerFactory
import java.net.*
import kotlin.concurrent.thread

class UdpBroadcaster {

    private val log = LoggerFactory.getLogger(UdpBroadcaster::class.java)

    private val BROADCAST_PORT = 19872

    @Volatile private var running = false
    private var broadcastThread: Thread? = null

    fun start(port: Int) {
        running = true
        broadcastThread = thread(name = "RemoteClaudeServer-UDP", isDaemon = true) {
            try {
                var localAddr = NetworkUtils.getLocalAddress()
                if (localAddr == null) {
                    log.warn("UDP: could not determine local address, broadcaster not started")
                    return@thread
                }

                val hostname = InetAddress.getLocalHost().hostName
                    .replace(Regex("[^a-zA-Z0-9-]"), "-")
                val instanceName = "RemoteClaude@$hostname"
                var payload = "RC|$instanceName|${localAddr.hostAddress}|$port"
                var data = payload.toByteArray(Charsets.UTF_8)

                log.info("UDP: broadcasting '$payload' to port $BROADCAST_PORT")

                // Bind to the LAN address so broadcasts go out on the correct interface
                val socket = DatagramSocket(0, localAddr).apply { broadcast = true }
                log.info("UDP: socket bound to ${localAddr.hostAddress}:${socket.localPort}")

                var targets = getBroadcastTargets(localAddr)

                fun sendToAll() {
                    for (target in targets) {
                        try {
                            socket.send(DatagramPacket(data, data.size, target, BROADCAST_PORT))
                        } catch (e: Exception) {
                            log.warn("UDP: failed to send to $target: ${e.message}")
                        }
                    }
                }

                repeat(3) { i ->
                    if (!running) return@thread
                    sendToAll()
                    log.info("UDP: initial broadcast ${i + 1}/3")
                    if (running && i < 2) Thread.sleep(1000)
                }

                while (running) {
                    Thread.sleep(5000)
                    if (!running) break

                    val newAddr = NetworkUtils.getLocalAddress()
                    if (newAddr != null && newAddr != localAddr) {
                        log.info("UDP: IP changed $localAddr -> $newAddr, rebuilding payload")
                        localAddr = newAddr
                        payload = "RC|$instanceName|${localAddr.hostAddress}|$port"
                        data = payload.toByteArray(Charsets.UTF_8)
                        targets = getBroadcastTargets(localAddr)
                        repeat(3) { i ->
                            sendToAll()
                            if (running && i < 2) Thread.sleep(1000)
                        }
                    } else {
                        sendToAll()
                    }
                }

                socket.close()
                log.info("UDP: broadcast loop exited")
            } catch (e: Exception) {
                log.warn("UDP: broadcaster thread failed: ${e.message}")
            }
        }
    }

    fun stop() {
        log.info("UDP: stopping broadcaster")
        running = false
        broadcastThread?.interrupt()
        broadcastThread = null
    }

    /** Returns list of broadcast addresses: limited broadcast + subnet broadcast if available. */
    private fun getBroadcastTargets(localAddr: Inet4Address): List<InetAddress> {
        val targets = mutableListOf<InetAddress>()

        // Always include limited broadcast
        targets.add(InetAddress.getByName("255.255.255.255"))

        // Try to compute subnet broadcast from network interface
        try {
            val iface = NetworkInterface.getByInetAddress(localAddr)
            if (iface != null) {
                for (ifAddr in iface.interfaceAddresses) {
                    if (ifAddr.address == localAddr) {
                        val broadcast = ifAddr.broadcast
                        if (broadcast != null && broadcast.hostAddress != "255.255.255.255") {
                            targets.add(broadcast)
                            log.info("UDP: subnet broadcast = ${broadcast.hostAddress}")
                        }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("UDP: failed to compute subnet broadcast: ${e.message}")
        }

        return targets
    }
}

package com.remoteclaude.plugin.mdns

import com.intellij.openapi.diagnostic.Logger
import java.net.*
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Minimal mDNS/DNS-SD advertiser — pure Java stdlib, no external dependencies.
 * Announces _claudemobile._tcp.local. via multicast DNS so the Android app
 * can auto-discover the plugin without manual IP entry.
 *
 * Binds to port 5353 (standard mDNS) so that:
 *  - Announcements have the correct source port (Android NSD ignores non-5353, RFC 6762 §11)
 *  - Incoming PTR queries from Android can be received and answered immediately
 */
class MdnsAdvertiser {

    private val LOG = Logger.getInstance(MdnsAdvertiser::class.java)

    private val MDNS_ADDRESS = InetAddress.getByName("224.0.0.251")
    private val MDNS_PORT = 5353

    @Volatile private var running = false
    private var announceThread: Thread? = null
    private var socket: MulticastSocket? = null

    fun start(port: Int) {
        running = true
        announceThread = thread(name = "RemoteClaude-mDNS", isDaemon = true) {
            try {
                val localAddr = getLocalAddress()
                if (localAddr == null) {
                    LOG.warn("RemoteClaude mDNS: could not determine local address, advertiser not started")
                    return@thread
                }
                LOG.info("RemoteClaude mDNS: local address = $localAddr")

                val hostname = InetAddress.getLocalHost().hostName
                    .replace(Regex("[^a-zA-Z0-9-]"), "-")
                val instanceName = "RemoteClaude@$hostname"
                val serviceType = "_claudemobile._tcp.local."
                LOG.info("RemoteClaude mDNS: instance name = $instanceName, port = $port")

                val iface = NetworkInterface.getByInetAddress(localAddr)

                val sock = createMdnsSocket(localAddr, iface)
                if (sock == null) {
                    LOG.warn("RemoteClaude mDNS: FAILED to create mDNS socket. " +
                        "mDNS advertising is disabled — use manual IP entry in the app.")
                    return@thread
                }
                socket = sock
                val canReceiveQueries = sock.localPort == MDNS_PORT
                LOG.info("RemoteClaude mDNS: socket ready (localPort=${sock.localPort}, queryListener=$canReceiveQueries)")
                if (iface != null) LOG.info("RemoteClaude mDNS: using network interface = ${iface.displayName}")

                val responsePacket = buildDnsSdPacket(instanceName, port, localAddr, hostname)
                val serviceTypeEncoded = encodeDnsName(serviceType)

                // Initial burst: 3 announcements at startup (RFC 6762 §8.3 — announcing phase)
                repeat(3) { i ->
                    try {
                        sock.send(DatagramPacket(responsePacket, responsePacket.size, MDNS_ADDRESS, MDNS_PORT))
                        LOG.info("RemoteClaude mDNS: initial announcement ${i + 1}/3 — $instanceName on $localAddr:$port")
                    } catch (e: Exception) {
                        LOG.warn("RemoteClaude mDNS: failed to send initial announcement: ${e.message}")
                    }
                    if (running && i < 2) Thread.sleep(1000)
                }

                // Main loop: listen for queries + send periodic announcements
                val recvBuf = ByteArray(1500)
                var lastAnnounce = System.currentTimeMillis()
                while (running) {
                    if (canReceiveQueries) {
                        // Listen for incoming mDNS queries and respond immediately
                        try {
                            val dp = DatagramPacket(recvBuf, recvBuf.size)
                            sock.receive(dp)
                            if (isQueryForOurService(recvBuf, dp.length, serviceTypeEncoded)) {
                                LOG.info("RemoteClaude mDNS: query for $serviceType from ${dp.address}, responding")
                                sock.send(DatagramPacket(responsePacket, responsePacket.size, MDNS_ADDRESS, MDNS_PORT))
                                lastAnnounce = System.currentTimeMillis()
                            }
                        } catch (_: SocketTimeoutException) {
                            // Normal timeout — fall through to periodic announce check
                        } catch (e: Exception) {
                            if (running) LOG.warn("RemoteClaude mDNS: receive error: ${e.message}")
                        }
                    } else {
                        // Fallback: no query listening, just sleep between announcements
                        var waited = 0
                        while (running && waited < 1000) {
                            Thread.sleep(500)
                            waited += 500
                        }
                    }

                    // Periodic announcement every 30 seconds
                    val now = System.currentTimeMillis()
                    if (now - lastAnnounce >= 30_000) {
                        try {
                            sock.send(DatagramPacket(responsePacket, responsePacket.size, MDNS_ADDRESS, MDNS_PORT))
                            LOG.info("RemoteClaude mDNS: announced $instanceName on $localAddr:$port")
                            lastAnnounce = now
                        } catch (e: Exception) {
                            LOG.warn("RemoteClaude mDNS: failed to send announcement: ${e.message}")
                        }
                    }
                }
                LOG.info("RemoteClaude mDNS: announce loop exited")
            } catch (e: Exception) {
                LOG.warn("RemoteClaude mDNS: advertiser thread failed: ${e.message}")
            }
        }
    }

    fun stop() {
        LOG.info("RemoteClaude mDNS: stopping advertiser")
        running = false
        try { socket?.close() } catch (_: Exception) {}
        announceThread?.interrupt()
        announceThread = null
    }

    /**
     * Create multicast socket for mDNS.  Tries several bind strategies:
     *  1. localAddr:5353  — correct source IP & port, receives queries
     *  2. 0.0.0.0:5353    — correct source port, receives queries
     *  3. localAddr:0      — fallback with random port (Android may ignore)
     */
    private fun createMdnsSocket(localAddr: Inet4Address, iface: NetworkInterface?): MulticastSocket? {
        // Strategy 1: bind to specific address + mDNS port
        try {
            return MulticastSocket(null as SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(localAddr as InetAddress, MDNS_PORT))
                applyInterface(iface, localAddr)
                tryJoinGroup(iface)
                timeToLive = 255
                soTimeout = 1000
            }.also { LOG.info("RemoteClaude mDNS: bound to $localAddr:$MDNS_PORT") }
        } catch (e: Exception) {
            LOG.info("RemoteClaude mDNS: could not bind to $localAddr:$MDNS_PORT (${e.message}), trying wildcard")
        }

        // Strategy 2: wildcard address + mDNS port
        try {
            return MulticastSocket(null as SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(MDNS_PORT))
                applyInterface(iface, localAddr)
                tryJoinGroup(iface)
                timeToLive = 255
                soTimeout = 1000
            }.also { LOG.info("RemoteClaude mDNS: bound to 0.0.0.0:$MDNS_PORT") }
        } catch (e: Exception) {
            LOG.info("RemoteClaude mDNS: could not bind to 0.0.0.0:$MDNS_PORT (${e.message}), ephemeral fallback")
        }

        // Strategy 3: ephemeral port (announcements may be ignored by strict implementations)
        return try {
            MulticastSocket(InetSocketAddress(localAddr, 0)).apply {
                applyInterface(iface, localAddr)
                timeToLive = 255
                soTimeout = 1000
            }.also {
                LOG.warn("RemoteClaude mDNS: bound to ephemeral port ${it.localPort} — Android may not see announcements")
            }
        } catch (e: Exception) {
            LOG.warn("RemoteClaude mDNS: all socket strategies failed: ${e.message}")
            null
        }
    }

    private fun MulticastSocket.applyInterface(iface: NetworkInterface?, localAddr: Inet4Address) {
        if (iface == null) return
        try {
            networkInterface = iface
        } catch (e: Exception) {
            LOG.warn("RemoteClaude mDNS: setNetworkInterface failed (${e.message}), trying setInterface")
            try {
                @Suppress("DEPRECATION")
                setInterface(localAddr)
            } catch (e2: Exception) {
                LOG.warn("RemoteClaude mDNS: setInterface also failed (${e2.message})")
            }
        }
    }

    private fun MulticastSocket.tryJoinGroup(iface: NetworkInterface?) {
        try {
            joinGroup(InetSocketAddress(MDNS_ADDRESS, MDNS_PORT), iface)
            LOG.info("RemoteClaude mDNS: joined multicast group $MDNS_ADDRESS")
        } catch (e: Exception) {
            LOG.warn("RemoteClaude mDNS: joinGroup failed (${e.message}) — announcements only, no query responses")
        }
    }

    private fun getLocalAddress(): Inet4Address? {
        // Use DatagramSocket trick: OS picks the interface used for internet routing.
        val candidate = try {
            java.net.DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName("8.8.8.8"), 53)
                socket.localAddress as? Inet4Address
            }
        } catch (_: Exception) { null }

        // Verify the candidate interface is not a VPN/virtual adapter.
        // If it is, fall back to scanning for a physical LAN interface.
        if (candidate != null) {
            val iface = NetworkInterface.getByInetAddress(candidate)
            if (iface != null && !isVirtualAdapter(iface)) {
                return candidate
            }
            LOG.info("RemoteClaude mDNS: candidate address $candidate is on a virtual/VPN adapter (${iface?.displayName}), searching for physical interface")
        }

        // Find best physical interface: prefer WiFi/Ethernet, skip loopback and virtual adapters.
        return NetworkInterface.getNetworkInterfaces()?.asSequence()
            ?.filter { it.isUp && !it.isLoopback && !it.isVirtual && !isVirtualAdapter(it) }
            ?.sortedWith(compareBy { scoreInterface(it) })
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull()
    }

    /** Returns true if the interface looks like a VPN, TAP, or virtual adapter. */
    private fun isVirtualAdapter(iface: NetworkInterface): Boolean {
        val name = (iface.displayName + " " + iface.name).lowercase()
        val virtualKeywords = listOf("tap", "vpn", "virtual", "hyper-v", "vmware", "vbox", "wsl", "loopback", "tunnel", "tun", "pptp", "l2tp", "wireguard")
        return virtualKeywords.any { name.contains(it) }
    }

    /** Lower score = higher preference. Prefer Ethernet > WiFi > others. */
    private fun scoreInterface(iface: NetworkInterface): Int {
        val name = (iface.displayName + " " + iface.name).lowercase()
        return when {
            name.contains("ethernet") || name.contains("local area connection") -> 0
            name.contains("wi-fi") || name.contains("wifi") || name.contains("wireless") || name.contains("wlan") -> 1
            else -> 2
        }
    }

    /** Encode a DNS name into wire-format bytes for pattern matching in received packets. */
    private fun encodeDnsName(name: String): ByteArray {
        val buf = ByteBuffer.allocate(256)
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            buf.put(bytes.size.toByte())
            buf.put(bytes)
        }
        buf.put(0)
        return buf.array().copyOf(buf.position())
    }

    /** Check if an mDNS packet is a query containing our service type. */
    private fun isQueryForOurService(data: ByteArray, length: Int, serviceTypeEncoded: ByteArray): Boolean {
        if (length < 12) return false
        // QR bit must be 0 (query, not response)
        if (data[2].toInt() and 0x80 != 0) return false
        // Search for our encoded service type in the question section
        val end = length - serviceTypeEncoded.size
        if (end < 12) return false
        outer@ for (i in 12..end) {
            for (j in serviceTypeEncoded.indices) {
                if (data[i + j] != serviceTypeEncoded[j]) continue@outer
            }
            return true
        }
        return false
    }

    /**
     * Builds a DNS-SD mDNS response packet with PTR + SRV + TXT + A records.
     */
    private fun buildDnsSdPacket(
        instanceName: String,
        port: Int,
        addr: Inet4Address,
        hostname: String,
    ): ByteArray {
        val buf = ByteBuffer.allocate(512)

        // DNS Header: response, authoritative
        buf.putShort(0x0000)
        buf.putShort(0x8400.toShort())
        buf.putShort(0)       // QDCOUNT
        buf.putShort(4)       // ANCOUNT = PTR + SRV + TXT + A
        buf.putShort(0)
        buf.putShort(0)

        val serviceType = "_claudemobile._tcp.local."
        val fullInstance = "$instanceName.$serviceType"
        val hostLocal = "$hostname.local."

        // PTR record
        writeDnsName(buf, serviceType)
        buf.putShort(12)
        buf.putShort(0x0001)
        buf.putInt(4500)
        val ptrLenPos = buf.position(); buf.putShort(0)
        val ptrStart = buf.position()
        writeDnsName(buf, fullInstance)
        buf.putShort(ptrLenPos, (buf.position() - ptrStart).toShort())

        // SRV record
        writeDnsName(buf, fullInstance)
        buf.putShort(33)
        buf.putShort(0x8001.toShort())
        buf.putInt(120)
        val srvLenPos = buf.position(); buf.putShort(0)
        val srvStart = buf.position()
        buf.putShort(0); buf.putShort(0)
        buf.putShort(port.toShort())
        writeDnsName(buf, hostLocal)
        buf.putShort(srvLenPos, (buf.position() - srvStart).toShort())

        // TXT record (required by DNS-SD for resolveService to complete on Android)
        writeDnsName(buf, fullInstance)
        buf.putShort(16)                    // TYPE TXT
        buf.putShort(0x8001.toShort())      // CLASS FLUSH_CACHE | IN
        buf.putInt(4500)                    // TTL
        buf.putShort(1)                     // RDLENGTH = 1
        buf.put(0)                          // empty TXT string

        // A record
        writeDnsName(buf, hostLocal)
        buf.putShort(1)
        buf.putShort(0x8001.toShort())
        buf.putInt(120)
        buf.putShort(4)
        buf.put(addr.address)

        return buf.array().copyOf(buf.position())
    }

    private fun writeDnsName(buf: ByteBuffer, name: String) {
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            buf.put(bytes.size.toByte())
            buf.put(bytes)
        }
        buf.put(0)
    }
}

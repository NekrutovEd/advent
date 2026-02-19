package com.remoteclaude.server.net

import org.slf4j.LoggerFactory
import java.net.*

object NetworkUtils {

    private val log = LoggerFactory.getLogger(NetworkUtils::class.java)

    private val VIRTUAL_KEYWORDS = listOf(
        "tun", "tap", "ppp", "wg", "vpn", "tunnel", "wireguard", "proton", "nordlynx", "openvpn",
        "virtual", "hyper-v", "vmware", "vbox", "wsl", "loopback", "pptp", "l2tp",
        "cloudflare", "warp", "mullvad", "tailscale", "ts0", "zt", "zerotier", "utun",
        "fortissl", "fortinet", "cscotun", "cisco", "anyconnect", "ksi", "kaspersky",
        "surfshark", "express",
    )

    fun getLocalAddress(): Inet4Address? {
        val candidate = try {
            DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName("8.8.8.8"), 53)
                socket.localAddress as? Inet4Address
            }
        } catch (_: Exception) { null }

        if (candidate != null) {
            val iface = NetworkInterface.getByInetAddress(candidate)
            if (iface != null && !isVirtualAdapter(iface) && isPrivateLan(candidate.hostAddress)) {
                return candidate
            }
            log.info("candidate $candidate rejected (iface=${iface?.displayName}), scanning interfaces")
        }

        return NetworkInterface.getNetworkInterfaces()?.asSequence()
            ?.filter { it.isUp && !it.isLoopback && !it.isVirtual && !isVirtualAdapter(it) }
            ?.sortedWith(compareBy { scoreInterface(it) })
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.filterIsInstance<Inet4Address>()
            ?.filter { isPrivateLan(it.hostAddress) }
            ?.firstOrNull()
    }

    fun getLanAddressString(): String {
        return try {
            getLocalAddress()?.hostAddress ?: InetAddress.getLocalHost().hostAddress
        } catch (_: Exception) {
            InetAddress.getLocalHost().hostAddress
        }
    }

    fun isVirtualAdapter(iface: NetworkInterface): Boolean {
        val name = (iface.displayName + " " + iface.name).lowercase()
        return VIRTUAL_KEYWORDS.any { name.contains(it) }
    }

    fun scoreInterface(iface: NetworkInterface): Int {
        val name = (iface.displayName + " " + iface.name).lowercase()
        return when {
            name.contains("ethernet") || name.contains("local area connection") -> 0
            name.contains("wi-fi") || name.contains("wifi") || name.contains("wireless") || name.contains("wlan") -> 1
            else -> 2
        }
    }

    fun isPrivateLan(addr: String): Boolean =
        addr.startsWith("192.168.") ||
        addr.startsWith("10.") ||
        Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(addr)
}

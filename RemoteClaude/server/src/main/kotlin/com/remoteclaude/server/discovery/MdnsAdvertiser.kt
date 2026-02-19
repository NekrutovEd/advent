package com.remoteclaude.server.discovery

import com.remoteclaude.server.net.NetworkUtils
import org.slf4j.LoggerFactory
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import java.net.InetAddress
import kotlin.concurrent.thread

class MdnsAdvertiser {

    private val log = LoggerFactory.getLogger(MdnsAdvertiser::class.java)

    @Volatile private var jmdns: JmDNS? = null
    private var serviceInfo: ServiceInfo? = null

    fun start(port: Int) {
        thread(name = "RemoteClaudeServer-mDNS", isDaemon = true) {
            try {
                val localAddr = NetworkUtils.getLocalAddress()
                if (localAddr == null) {
                    log.warn("mDNS: could not determine local address, advertiser not started")
                    return@thread
                }
                log.info("mDNS: local address = $localAddr")

                val hostname = InetAddress.getLocalHost().hostName
                    .replace(Regex("[^a-zA-Z0-9-]"), "-")
                val instanceName = "RemoteClaude@$hostname"

                log.info("mDNS: creating JmDNS on $localAddr...")
                val jm = JmDNS.create(localAddr, "$hostname.local.")
                jmdns = jm

                val info = ServiceInfo.create(
                    "_claudeserver._tcp.local.",
                    instanceName,
                    port,
                    "server=RemoteClaude"
                )
                serviceInfo = info

                jm.registerService(info)
                log.info("mDNS: registered '$instanceName' on port $port via JmDNS")
            } catch (e: Exception) {
                log.warn("mDNS: failed to start: ${e.message}", e)
            }
        }
    }

    fun stop() {
        log.info("mDNS: stopping advertiser")
        try {
            serviceInfo?.let { jmdns?.unregisterService(it) }
            jmdns?.close()
        } catch (e: Exception) {
            log.warn("mDNS: error stopping: ${e.message}")
        }
        jmdns = null
        serviceInfo = null
    }
}

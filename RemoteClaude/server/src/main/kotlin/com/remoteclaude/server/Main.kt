package com.remoteclaude.server

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.remoteclaude.server.config.ConfigStore
import com.remoteclaude.server.discovery.MdnsAdvertiser
import com.remoteclaude.server.discovery.UdpBroadcaster
import com.remoteclaude.server.net.MessageRouter
import com.remoteclaude.server.net.WebSocketHub
import com.remoteclaude.server.state.AppRegistry
import com.remoteclaude.server.state.GlobalTabRegistry
import com.remoteclaude.server.state.PluginRegistry
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("RemoteClaudeServer")

fun main() = application {
    val config = ConfigStore.load()
    log.info("RemoteClaude Server starting on port ${config.port}")

    // State
    val pluginRegistry = PluginRegistry()
    val appRegistry = AppRegistry()
    val tabRegistry = GlobalTabRegistry()
    val router = MessageRouter(pluginRegistry, appRegistry, tabRegistry)
    val hub = WebSocketHub(pluginRegistry, appRegistry, tabRegistry, router)

    // Start server
    hub.start(config)

    // Discovery
    val mdns = if (config.enableMdns) MdnsAdvertiser().also { it.start(config.port) } else null
    val udp = if (config.enableUdpBroadcast) UdpBroadcaster().also { it.start(config.port) } else null

    log.info("RemoteClaude Server started on port ${config.port}")

    val windowState = rememberWindowState(
        width = 800.dp,
        height = 600.dp,
    )

    Window(
        onCloseRequest = {
            log.info("Shutting down RemoteClaude Server")
            mdns?.stop()
            udp?.stop()
            hub.stop()
            exitApplication()
        },
        state = windowState,
        title = "RemoteClaude Server",
    ) {
        ServerApp(hub, router, config.port)
    }
}

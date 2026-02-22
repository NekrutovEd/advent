package com.remoteclaude.server.net

import com.remoteclaude.server.config.KnownPlugin
import com.remoteclaude.server.config.ServerConfig
import com.remoteclaude.server.protocol.*
import com.remoteclaude.server.state.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class WebSocketHub(
    val pluginRegistry: PluginRegistry,
    val appRegistry: AppRegistry,
    val tabRegistry: GlobalTabRegistry,
    val router: MessageRouter,
    val knownPluginRegistry: KnownPluginRegistry,
) {
    private val log = LoggerFactory.getLogger(WebSocketHub::class.java)
    private var engine: EmbeddedServer<*, *>? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    fun start(config: ServerConfig) {
        log.info("Starting WebSocket hub on port ${config.port}")
        engine = embeddedServer(Netty, port = config.port) {
            install(WebSockets) {
                pingPeriod = 15.seconds
                timeout = 30.seconds
            }
            routing {
                get("/discover") {
                    val hostname = try {
                        InetAddress.getLocalHost().hostName
                            .replace(Regex("[^a-zA-Z0-9-]"), "-")
                    } catch (_: Exception) { "unknown" }
                    call.respondText(
                        """{"name":"RemoteClaude@$hostname","port":${config.port}}""",
                        ContentType.Application.Json
                    )
                }
                webSocket("/plugin") {
                    handlePluginConnection(this)
                }
                webSocket("/app") {
                    handleAppConnection(this)
                }
            }
        }.also {
            it.start(wait = false)
            _running.value = true
        }
        log.info("WebSocket hub started on port ${config.port}")
    }

    fun stop() {
        log.info("Stopping WebSocket hub")
        engine?.stop(500, 1000)
        engine = null
        _running.value = false
    }

    private suspend fun handlePluginConnection(ws: DefaultWebSocketSession) {
        val pluginSession = PluginSession(ws)
        log.info("New plugin connection")

        try {
            for (frame in ws.incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    val message = try {
                        wsJson.decodeFromString(WsMessage.serializer(), text)
                    } catch (e: Exception) {
                        log.warn("Failed to decode plugin message: ${e.message}")
                        continue
                    }

                    // Handle registration
                    if (message is RegisterPluginMessage) {
                        pluginSession.pluginId = message.pluginId
                        pluginRegistry.register(
                            message.pluginId, message.ideName, message.projectName, message.hostname,
                            message.projectPath, message.ideHomePath,
                        )
                        router.addPluginSession(message.pluginId, pluginSession)
                        pluginSession.send(PluginRegisteredMessage(message.pluginId))
                        log.info("Plugin registered: ${message.pluginId} (${message.ideName} - ${message.projectName}, path=${message.projectPath})")

                        // Persist to known plugins
                        if (message.projectPath.isNotEmpty()) {
                            knownPluginRegistry.upsert(KnownPlugin(
                                projectPath = message.projectPath,
                                projectName = message.projectName,
                                hostname = message.hostname,
                                ideName = message.ideName,
                                ideHomePath = message.ideHomePath,
                                lastSeenMs = System.currentTimeMillis(),
                            ))
                            knownPluginRegistry.saveToDisk()
                        }

                        // Notify apps — merge connected + known offline plugins
                        router.broadcastToApps(InitMessage(
                            tabs = tabRegistry.getAllTabs(),
                            plugins = buildMergedPluginList(),
                        ))
                        continue
                    }

                    router.handlePluginMessage(pluginSession, message)
                }
            }
        } finally {
            val pluginId = pluginSession.pluginId
            if (pluginId != null) {
                log.info("Plugin disconnected: $pluginId")
                router.removePluginSession(pluginId)
                val removedTabs = tabRegistry.removeAllTabsForPlugin(pluginId)
                pluginRegistry.unregister(pluginId)

                // Notify apps about removed tabs — plugin now appears as offline via known registry
                for (tabId in removedTabs) {
                    router.broadcastToApps(TabRemovedMessage(tabId))
                }
                router.broadcastToApps(InitMessage(
                    tabs = tabRegistry.getAllTabs(),
                    plugins = buildMergedPluginList(),
                ))
            }
        }
    }

    private suspend fun handleAppConnection(ws: DefaultWebSocketSession) {
        val sessionId = UUID.randomUUID().toString().take(8)
        val appSession = AppSession(ws, sessionId)
        log.info("New app connection: $sessionId")

        // Send init message with current state (includes both connected and known offline plugins)
        appSession.send(InitMessage(
            tabs = tabRegistry.getAllTabs(),
            plugins = buildMergedPluginList(),
        ))

        // Send buffered output for each tab
        for (tab in tabRegistry.getAllTabs()) {
            val snapshot = tabRegistry.getBuffer(tab.id)?.getSnapshot() ?: continue
            if (snapshot.isNotEmpty()) {
                appSession.send(BufferMessage(tab.id, snapshot))
            }
        }

        try {
            for (frame in ws.incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    val message = try {
                        wsJson.decodeFromString(WsMessage.serializer(), text)
                    } catch (e: Exception) {
                        log.warn("Failed to decode app message: ${e.message}")
                        continue
                    }

                    // Handle registration
                    if (message is RegisterAppMessage) {
                        appRegistry.register(sessionId, message.deviceName, message.platform)
                        router.addAppSession(sessionId, appSession)
                        log.info("App registered: $sessionId (${message.deviceName}, ${message.platform})")
                        continue
                    }

                    // If not registered yet, auto-register with defaults
                    if (appRegistry.count() == 0 || appSession.sessionId !in appRegistry.getAll().map { it.sessionId }) {
                        appRegistry.register(sessionId, "Unknown", "unknown")
                        router.addAppSession(sessionId, appSession)
                    }

                    router.handleAppMessage(appSession, message)
                }
            }
        } finally {
            log.info("App disconnected: $sessionId")
            router.removeAppSession(sessionId)
            appRegistry.unregister(sessionId)
        }
    }

    /** Merge connected plugins with known offline plugins into a single list for apps */
    fun buildMergedPluginList(): List<PluginInfo> {
        val connected = pluginRegistry.toPluginInfoList(tabRegistry)
        val connectedPaths = connected.mapNotNull { it.projectPath.ifEmpty { null } }.toSet()
        val offline = knownPluginRegistry.getDisconnectedPaths(connectedPaths).map { known ->
            PluginInfo(
                pluginId = "offline:${known.projectPath}",
                ideName = known.ideName,
                projectName = known.projectName,
                hostname = known.hostname,
                tabCount = 0,
                connected = false,
                projectPath = known.projectPath,
                ideHomePath = known.ideHomePath,
            )
        }
        return connected + offline
    }
}

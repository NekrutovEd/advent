package com.remoteclaude.plugin.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.ServerSocket

/**
 * Minimal local HTTP server — only keeps the /api/notify endpoint for Claude Code hooks.
 * The WebSocket server is replaced by WsPluginClient connecting to the central server.
 */
@Service(Service.Level.PROJECT)
class WsServer(private val project: Project) : Disposable {

    private val LOG = Logger.getInstance(WsServer::class.java)

    val registry = TabRegistry()
    var port: Int = 0
        private set

    private var engine: EmbeddedServer<*, *>? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        port = findFreePort()
        engine = embeddedServer(Netty, port = port) {
            routing {
                // HTTP endpoint for Claude Code hooks (Notification / Stop events)
                post("/api/notify") {
                    val body = call.receiveText()
                    try {
                        val json = Json.parseToJsonElement(body).jsonObject
                        val message = json["message"]?.jsonPrimitive?.contentOrNull ?: ""
                        // Find the most recently active tab (the one that just produced output)
                        val tabId = registry.findMostRecentlyActiveTab()
                            ?: json["tabId"]?.jsonPrimitive?.intOrNull
                            ?: registry.getAllTabs().firstOrNull()?.id
                        if (tabId != null) {
                            // Forward notification to central server via WsPluginClient
                            scope.launch {
                                val client = WsPluginClient.getInstance(project)
                                client.send(
                                    PluginTabStateMessage(client.pluginId, tabId, TabState.WAITING_INPUT, message)
                                )
                            }
                            // Also update local registry
                            registry.updateTabState(tabId, TabState.WAITING_INPUT, message)
                        }
                    } catch (e: Exception) {
                        LOG.warn("RemoteClaude: /api/notify parse error: ${e.message}")
                    }
                    call.respond(HttpStatusCode.OK, "ok")
                }
            }
        }.also { it.start(wait = false) }
        writePortFile(port)
        LOG.info("RemoteClaude: local HTTP server started on port $port (for /api/notify)")
    }

    fun stop() {
        engine?.stop(500, 1000)
        scope.cancel()
        deletePortFile()
    }

    /** Write the actual port to ~/.claude/.remoteclaude-port so hooks can read it */
    private fun writePortFile(port: Int) {
        try {
            val portFile = File(System.getProperty("user.home"), ".claude/.remoteclaude-port")
            portFile.parentFile?.mkdirs()
            portFile.writeText(port.toString())
            LOG.info("RemoteClaude: wrote port $port to ${portFile.absolutePath}")
        } catch (e: Exception) {
            LOG.warn("RemoteClaude: failed to write port file: ${e.message}")
        }
    }

    override fun dispose() {
        stop()
    }

    private fun deletePortFile() {
        try {
            val portFile = File(System.getProperty("user.home"), ".claude/.remoteclaude-port")
            portFile.delete()
        } catch (_: Exception) {}
    }

    private fun findFreePort(): Int =
        ServerSocket(0).use { it.localPort }

    companion object {
        fun getInstance(project: Project): WsServer =
            project.getService(WsServer::class.java)
    }
}

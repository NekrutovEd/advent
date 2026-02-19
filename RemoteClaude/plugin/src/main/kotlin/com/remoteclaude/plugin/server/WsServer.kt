package com.remoteclaude.plugin.server

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.ServerSocket
import kotlin.time.Duration.Companion.seconds

@Service(Service.Level.PROJECT)
class WsServer(private val project: Project) {

    val registry = TabRegistry()
    val sessionManager = WsSessionManager()
    var port: Int = 0
        private set

    private var engine: EmbeddedServer<*, *>? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        port = findFreePort()
        engine = embeddedServer(Netty, port = port) {
            install(WebSockets) {
                pingPeriod = 15.seconds
                timeout = 30.seconds
            }
            routing {
                webSocket("/terminal") {
                    sessionManager.addClient(this, registry)
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                handleClientFrame(frame.readText())
                            }
                        }
                    } finally {
                        sessionManager.removeClient(this)
                    }
                }

                // HTTP endpoint for Claude Code hooks (Notification event)
                post("/api/notify") {
                    val body = call.receiveText()
                    try {
                        val json = Json.parseToJsonElement(body).jsonObject
                        val tabId = json["tabId"]?.jsonPrimitive?.intOrNull ?: 0
                        val message = json["message"]?.jsonPrimitive?.contentOrNull ?: ""
                        scope.launch {
                            sessionManager.broadcast(
                                TabStateMessage(tabId, TabState.WAITING_INPUT, message)
                            )
                        }
                    } catch (e: Exception) {
                        // Malformed body — still respond OK
                    }
                    call.respond(HttpStatusCode.OK, "ok")
                }
            }
        }.also { it.start(wait = false) }
    }

    private suspend fun DefaultWebSocketSession.handleClientFrame(text: String) {
        val message = try {
            wsJson.decodeFromString(WsMessage.serializer(), text)
        } catch (e: Exception) {
            return
        }

        when (message) {
            is InputMessage -> {
                com.remoteclaude.plugin.terminal.TerminalTabsWatcher.getInstance(project)
                    .sendInput(message.tabId, message.data)
            }
            is RequestBufferMessage -> {
                val buf = registry.getBuffer(message.tabId)?.getSnapshot() ?: ""
                sessionManager.sendTo(this, BufferMessage(message.tabId, buf))
            }
            is ListProjectsMessage -> {
                val projects = com.remoteclaude.plugin.orchestration.ProjectRegistry.getInstance().allProjects()
                sessionManager.sendTo(this, ProjectsListMessage(projects))
            }
            is LaunchAgentMessage -> {
                com.remoteclaude.plugin.orchestration.AgentLauncher.getInstance(project).launch(
                    message, scope, registry, sessionManager
                )
            }
            is TerminateAgentMessage -> {
                com.remoteclaude.plugin.orchestration.AgentLauncher.getInstance(project).terminate(message.tabId)
            }
            is AddProjectMessage -> {
                com.remoteclaude.plugin.orchestration.ProjectRegistry.getInstance().addProject(message.path)
            }
            else -> {
                // Server-only messages arriving from client — ignore
            }
        }
    }

    fun stop() {
        engine?.stop(500, 1000)
        scope.cancel()
    }

    private fun findFreePort(): Int {
        val settings = com.remoteclaude.plugin.settings.RemoteClaudeSettings.getInstance()
        return try {
            ServerSocket(settings.state.port).use { it.localPort }
        } catch (e: Exception) {
            ServerSocket(0).use { it.localPort }
        }
    }

    companion object {
        fun getInstance(project: Project): WsServer =
            project.getService(WsServer::class.java)
    }
}

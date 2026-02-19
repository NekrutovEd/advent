package com.remoteclaude.plugin.server

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.websocket.*
import com.remoteclaude.plugin.net.ServerDiscovery
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.InetAddress

@Service(Service.Level.PROJECT)
class WsPluginClient(private val project: Project) {

    private val LOG = Logger.getInstance(WsPluginClient::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client = HttpClient(CIO) {
        install(WebSockets)
        engine {
            endpoint {
                connectTimeout = 10_000
            }
        }
    }

    private var session: DefaultClientWebSocketSession? = null
    private val sendChannel = Channel<WsMessage>(Channel.BUFFERED)
    private var connectJob: Job? = null

    val pluginId: String by lazy {
        val hostname = try {
            InetAddress.getLocalHost().hostName.replace(Regex("[^a-zA-Z0-9-]"), "-")
        } catch (_: Exception) { "unknown" }
        val projectName = project.name.replace(Regex("[^a-zA-Z0-9-]"), "-")
        "${hostname}_${projectName}_${System.currentTimeMillis() % 100000}"
    }

    @Volatile
    var connected = false
        private set

    // Callback for handling messages from the server (forwarded commands)
    var onMessage: (suspend (WsMessage) -> Unit)? = null

    fun start() {
        connectJob = scope.launch {
            connectWithRetry()
        }
    }

    private suspend fun connectWithRetry() {
        val host = ServerDiscovery.getServerHost()
        val port = ServerDiscovery.getServerPort()
        var attempt = 0

        while (currentCoroutineContext().isActive) {
            attempt++
            LOG.info("RemoteClaude: connecting to server at $host:$port/plugin (attempt $attempt)")

            try {
                client.webSocket(
                    method = HttpMethod.Get,
                    host = host,
                    port = port,
                    path = "/plugin"
                ) {
                    session = this
                    connected = true
                    LOG.info("RemoteClaude: connected to server at $host:$port/plugin")

                    // Register this plugin
                    val hostname = try {
                        InetAddress.getLocalHost().hostName
                    } catch (_: Exception) { "unknown" }
                    val ideName = try {
                        com.intellij.openapi.application.ApplicationInfo.getInstance().fullApplicationName
                    } catch (_: Exception) { "IDE" }
                    send(RegisterPluginMessage(pluginId, ideName, project.name, hostname))

                    // Send outgoing messages
                    val sendJob = launch {
                        for (msg in sendChannel) {
                            val text = wsJson.encodeToString(WsMessage.serializer(), msg)
                            send(Frame.Text(text))
                        }
                    }

                    // Receive incoming messages (forwarded commands from server)
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                val message = try {
                                    wsJson.decodeFromString(WsMessage.serializer(), text)
                                } catch (e: Exception) {
                                    LOG.warn("RemoteClaude: failed to decode server message: ${e.message}")
                                    continue
                                }
                                onMessage?.invoke(message)
                            }
                        }
                    } finally {
                        sendJob.cancel()
                        session = null
                        connected = false
                        LOG.info("RemoteClaude: disconnected from server")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("RemoteClaude: server connection attempt $attempt failed: ${e.message}")
                connected = false
            }

            // Exponential backoff
            val backoff = (1000L * (1L shl (attempt - 1).coerceAtMost(5))).coerceAtMost(30_000L)
            LOG.info("RemoteClaude: reconnecting in ${backoff}ms")
            delay(backoff)
        }
    }

    suspend fun send(message: WsMessage) {
        sendChannel.send(message)
    }

    fun sendFireAndForget(message: WsMessage) {
        scope.launch { send(message) }
    }

    fun stop() {
        LOG.info("RemoteClaude: stopping plugin client")
        connected = false
        connectJob?.cancel()
        scope.cancel()
        client.close()
    }

    companion object {
        fun getInstance(project: Project): WsPluginClient =
            project.getService(WsPluginClient::class.java)
    }
}

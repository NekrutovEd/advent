package com.remoteclaude.server.net

import com.remoteclaude.server.protocol.WsMessage
import com.remoteclaude.server.protocol.wsJson
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedSendChannelException
import org.slf4j.LoggerFactory

class PluginSession(
    val session: DefaultWebSocketSession,
    var pluginId: String? = null,
) {
    private val log = LoggerFactory.getLogger(PluginSession::class.java)

    suspend fun send(message: WsMessage) {
        val json = wsJson.encodeToString(WsMessage.serializer(), message)
        try {
            session.send(Frame.Text(json))
        } catch (e: ClosedSendChannelException) {
            log.warn("Plugin session closed when sending: ${e.message}")
        } catch (e: Exception) {
            log.warn("Failed to send to plugin ${pluginId}: ${e.message}")
        }
    }
}

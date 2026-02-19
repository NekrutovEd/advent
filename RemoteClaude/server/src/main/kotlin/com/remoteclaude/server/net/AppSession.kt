package com.remoteclaude.server.net

import com.remoteclaude.server.protocol.WsMessage
import com.remoteclaude.server.protocol.wsJson
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedSendChannelException
import org.slf4j.LoggerFactory

class AppSession(
    val session: DefaultWebSocketSession,
    var sessionId: String,
) {
    private val log = LoggerFactory.getLogger(AppSession::class.java)

    suspend fun send(message: WsMessage) {
        val json = wsJson.encodeToString(WsMessage.serializer(), message)
        try {
            session.send(Frame.Text(json))
        } catch (e: ClosedSendChannelException) {
            log.warn("App session closed when sending: ${e.message}")
        } catch (e: Exception) {
            log.warn("Failed to send to app $sessionId: ${e.message}")
        }
    }
}

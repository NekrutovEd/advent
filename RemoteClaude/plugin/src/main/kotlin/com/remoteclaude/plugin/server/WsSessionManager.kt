package com.remoteclaude.plugin.server

import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.serialization.encodeToString
import java.util.concurrent.CopyOnWriteArrayList

class WsSessionManager {

    private val clients = CopyOnWriteArrayList<DefaultWebSocketSession>()

    suspend fun addClient(session: DefaultWebSocketSession, registry: TabRegistry) {
        clients.add(session)

        // Send current tabs as InitMessage
        val tabs = registry.getAllTabs()
        sendTo(session, InitMessage(tabs))

        // Send buffered output for each tab
        for (tab in tabs) {
            val snapshot = registry.getBuffer(tab.id)?.getSnapshot() ?: continue
            if (snapshot.isNotEmpty()) {
                sendTo(session, BufferMessage(tab.id, snapshot))
            }
        }

        // Send verification message to confirm the pipeline works
        for (tab in tabs) {
            sendTo(session, OutputMessage(tab.id,
                "\r\n--- [RemoteClaude] Connection OK. Tab ${tab.id} (${tab.title}), state=${tab.state} ---\r\n"
            ))
        }
    }

    suspend fun removeClient(session: DefaultWebSocketSession) {
        clients.remove(session)
    }

    suspend fun broadcast(message: WsMessage) {
        val json = wsJson.encodeToString(WsMessage.serializer(), message)
        val dead = mutableListOf<DefaultWebSocketSession>()
        for (client in clients) {
            try {
                client.send(Frame.Text(json))
            } catch (e: ClosedSendChannelException) {
                dead.add(client)
            } catch (e: Exception) {
                dead.add(client)
            }
        }
        clients.removeAll(dead)
    }

    suspend fun sendTo(session: DefaultWebSocketSession, message: WsMessage) {
        val json = wsJson.encodeToString(WsMessage.serializer(), message)
        try {
            session.send(Frame.Text(json))
        } catch (e: Exception) {
            clients.remove(session)
        }
    }

    fun clientCount(): Int = clients.size
}

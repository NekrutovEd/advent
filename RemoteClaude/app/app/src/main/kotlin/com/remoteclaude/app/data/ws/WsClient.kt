package com.remoteclaude.app.data.ws

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.serializer
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

private const val TAG = "RC_DEBUG"

class WsClient(socketFactory: SocketFactory? = null) {
    private val client = HttpClient(OkHttp) {
        install(WebSockets)
        engine {
            config {
                connectTimeout(30, TimeUnit.SECONDS)
                readTimeout(0, TimeUnit.SECONDS)
                writeTimeout(0, TimeUnit.SECONDS)
                if (socketFactory != null) {
                    socketFactory(socketFactory)
                    Log.d(TAG, "WsClient: using custom WiFi-bound SocketFactory")
                }
            }
        }
    }

    private val _messages = MutableSharedFlow<WsMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<WsMessage> = _messages

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private var session: DefaultClientWebSocketSession? = null
    private var sendChannel = Channel<WsMessage>(Channel.BUFFERED)

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val host: String, val port: Int) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    suspend fun connect(host: String, port: Int) {
        Log.d(TAG, "WsClient.connect() called: ws://$host:$port/terminal")
        _connectionState.value = ConnectionState.Connecting
        try {
            client.webSocket("ws://$host:$port/terminal") {
                session = this
                _connectionState.value = ConnectionState.Connected(host, port)
                sendChannel = Channel(Channel.BUFFERED)
                Log.d(TAG, "WsClient: WebSocket session opened, state=Connected")

                // Send outgoing messages
                val sendJob = launch {
                    Log.d(TAG, "WsClient: sendJob started, waiting for outgoing messages")
                    for (msg in sendChannel) {
                        val text = wsJson.encodeToString(serializer<WsMessage>(), msg)
                        Log.d(TAG, "WsClient: SENDING >>> ${text.take(200)}")
                        send(Frame.Text(text))
                        Log.d(TAG, "WsClient: sent frame OK")
                    }
                }

                // Receive incoming messages
                try {
                    Log.d(TAG, "WsClient: receive loop started, waiting for incoming frames")
                    for (frame in incoming) {
                        Log.d(TAG, "WsClient: received frame type=${frame.frameType}")
                        if (frame is Frame.Text) {
                            val rawText = frame.readText()
                            Log.d(TAG, "WsClient: RECEIVED <<< ${rawText.take(300)}")
                            try {
                                val msg = wsJson.decodeFromString(serializer<WsMessage>(), rawText)
                                Log.d(TAG, "WsClient: decoded message type=${msg::class.simpleName}")
                                _messages.emit(msg)
                                Log.d(TAG, "WsClient: emitted to _messages flow")
                            } catch (e: Exception) {
                                Log.e(TAG, "WsClient: decode error: ${e.message}, raw=${rawText.take(200)}")
                            }
                        }
                    }
                    Log.d(TAG, "WsClient: receive loop ended (incoming channel closed)")
                } finally {
                    sendJob.cancel()
                    Log.d(TAG, "WsClient: sendJob cancelled")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "WsClient: connect error: ${e.message}", e)
            _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
        } finally {
            Log.d(TAG, "WsClient: connect() finally block, previous state=${_connectionState.value}")
            if (_connectionState.value is ConnectionState.Connected) {
                _connectionState.value = ConnectionState.Disconnected
            }
            session = null
        }
    }

    suspend fun send(message: WsMessage) {
        Log.d(TAG, "WsClient.send() queuing: ${message::class.simpleName}, session=${session != null}")
        sendChannel.send(message)
        Log.d(TAG, "WsClient.send() queued OK")
    }

    fun disconnect() {
        session?.let { runBlocking { it.close() } }
    }

    fun close() {
        disconnect()
        client.close()
    }
}

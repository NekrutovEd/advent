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
private const val MAX_BACKOFF_MS = 30_000L

class WsClient(private val wifiSocketFactory: SocketFactory? = null) {
    private var useWifiBinding = wifiSocketFactory != null

    private fun buildClient(socketFactory: SocketFactory?): HttpClient = HttpClient(OkHttp) {
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

    private var client = buildClient(wifiSocketFactory)

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
        data class Reconnecting(val attempt: Int) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    suspend fun connectWithRetry(host: String, port: Int) {
        var attempt = 0
        while (true) {
            attempt++
            Log.d(TAG, "WsClient.connectWithRetry() attempt=$attempt to ws://$host:$port/app")

            if (attempt == 1) {
                _connectionState.value = ConnectionState.Connecting
            } else {
                _connectionState.value = ConnectionState.Reconnecting(attempt)
            }

            try {
                connectInternal(host, port)
                // connectInternal returned normally — session ended cleanly
                Log.d(TAG, "WsClient: session ended, will reconnect")
            } catch (e: CancellationException) {
                Log.d(TAG, "WsClient: connectWithRetry cancelled")
                _connectionState.value = ConnectionState.Disconnected
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "WsClient: connect attempt $attempt failed: ${e.message}")
                if (useWifiBinding && e.message?.contains("EPERM") == true) {
                    Log.w(TAG, "WsClient: WiFi-bound socket got EPERM, falling back to default networking")
                    useWifiBinding = false
                    client.close()
                    client = buildClient(null)
                    attempt = 0  // reset backoff since this is a different client
                }
            }

            // Exponential backoff: 1s, 2s, 4s, 8s, ... max 30s
            val backoffMs = (1000L * (1L shl (attempt - 1).coerceAtMost(14))).coerceAtMost(MAX_BACKOFF_MS)
            Log.d(TAG, "WsClient: waiting ${backoffMs}ms before retry")
            delay(backoffMs)
        }
    }

    private suspend fun connectInternal(host: String, port: Int) {
        client.webSocket("ws://$host:$port/app") {
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
                session = null
                Log.d(TAG, "WsClient: sendJob cancelled, session cleared")
            }
        }
    }

    suspend fun connect(host: String, port: Int) {
        Log.d(TAG, "WsClient.connect() called: ws://$host:$port/app")
        _connectionState.value = ConnectionState.Connecting
        try {
            connectInternal(host, port)
        } catch (e: CancellationException) {
            throw e
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

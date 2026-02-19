package com.remoteclaude.app.notification

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.remoteclaude.app.RemoteClaudeApplication
import com.remoteclaude.app.data.ws.*
import kotlinx.coroutines.*

class TerminalForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = intent?.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
        val port = intent.getIntExtra(EXTRA_PORT, 8765)

        val client = WsClient().also { wsClient = it }

        startForeground(
            RemoteClaudeApplication.NOTIF_ID_PERSISTENT,
            NotificationCompat.Builder(this, RemoteClaudeApplication.CHANNEL_PERSISTENT)
                .setContentTitle("RemoteClaude")
                .setContentText("Connected to $host:$port")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setSilent(true)
                .build(),
        )

        scope.launch {
            client.connect(host, port)
        }

        scope.launch {
            client.messages.collect { msg ->
                when (msg) {
                    is TabStateMessage -> {
                        if (msg.state == TabState.WAITING_INPUT || msg.state == TabState.WAITING_TOOL) {
                            val notif = RcNotificationManager.buildWaitingNotification(
                                this@TerminalForegroundService, msg.tabId, msg.message,
                            )
                            NotificationManagerCompat.from(this@TerminalForegroundService)
                                .notify(RemoteClaudeApplication.NOTIF_ID_ALERT_BASE + (msg.tabId.hashCode() and 0x7FFFFFFF), notif)
                        }
                    }
                    else -> {}
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wsClient = null
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        var wsClient: WsClient? = null
    }
}

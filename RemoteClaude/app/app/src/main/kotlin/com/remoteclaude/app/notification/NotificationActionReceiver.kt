package com.remoteclaude.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.remoteclaude.app.RemoteClaudeApplication
import com.remoteclaude.app.data.ws.InputMessage
import kotlinx.coroutines.runBlocking

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RcNotificationManager.ACTION_SEND_INPUT) return
        val tabId = intent.getStringExtra(RcNotificationManager.EXTRA_TAB_ID) ?: return

        // Get input from inline reply or from extra
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(RcNotificationManager.KEY_REPLY)?.toString()
            ?: intent.getStringExtra(RcNotificationManager.EXTRA_INPUT)
            ?: return

        // Send via WsClient singleton (must exist from TerminalForegroundService)
        val client = TerminalForegroundService.wsClient
        if (client != null) {
            runBlocking { client.send(InputMessage(tabId, text)) }
        }

        NotificationManagerCompat.from(context)
            .cancel(RemoteClaudeApplication.NOTIF_ID_ALERT_BASE + (tabId.hashCode() and 0x7FFFFFFF))
    }
}

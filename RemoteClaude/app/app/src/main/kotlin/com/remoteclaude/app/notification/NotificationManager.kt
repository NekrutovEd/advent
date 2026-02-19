package com.remoteclaude.app.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.remoteclaude.app.MainActivity
import com.remoteclaude.app.RemoteClaudeApplication

object RcNotificationManager {

    const val ACTION_SEND_INPUT = "com.remoteclaude.ACTION_SEND_INPUT"
    const val EXTRA_TAB_ID = "tab_id"
    const val EXTRA_INPUT = "input"
    const val KEY_REPLY = "reply_text"

    fun buildWaitingNotification(ctx: Context, tabId: String, message: String?): Notification {
        val reqBase = tabId.hashCode() and 0x7FFFFFFF
        val openIntent = PendingIntent.getActivity(
            ctx, reqBase,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val yesIntent = actionIntent(ctx, tabId, "y\n", reqBase + 1)
        val noIntent = actionIntent(ctx, tabId, "n\n", reqBase + 2)

        val remoteInput = RemoteInput.Builder(KEY_REPLY)
            .setLabel("Reply...")
            .build()
        val replyIntent = PendingIntent.getBroadcast(
            ctx, reqBase + 3,
            Intent(ctx, NotificationActionReceiver::class.java).apply {
                action = ACTION_SEND_INPUT
                putExtra(EXTRA_TAB_ID, tabId)
            },
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, "Reply", replyIntent,
        ).addRemoteInput(remoteInput).build()

        return NotificationCompat.Builder(ctx, RemoteClaudeApplication.CHANNEL_ALERTS)
            .setContentTitle("Claude needs your input")
            .setContentText(message ?: "Tab $tabId is waiting")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_input_add, "Yes", yesIntent)
            .addAction(android.R.drawable.ic_delete, "No", noIntent)
            .addAction(replyAction)
            .build()
    }

    private fun actionIntent(ctx: Context, tabId: String, input: String, reqCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, reqCode,
            Intent(ctx, NotificationActionReceiver::class.java).apply {
                action = ACTION_SEND_INPUT
                putExtra(EXTRA_TAB_ID, tabId)
                putExtra(EXTRA_INPUT, input)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

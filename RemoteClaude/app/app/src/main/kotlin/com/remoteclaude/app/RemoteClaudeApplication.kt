package com.remoteclaude.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class RemoteClaudeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_PERSISTENT,
                "Background Connection",
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = "Shown while connected to RemoteClaude plugin" })

            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ALERTS,
                "Claude Waiting",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when Claude needs your input"
                enableVibration(true)
            })
        }
    }

    companion object {
        const val CHANNEL_PERSISTENT = "rc_persistent"
        const val CHANNEL_ALERTS = "rc_alerts"
        const val NOTIF_ID_PERSISTENT = 1001
        const val NOTIF_ID_ALERT_BASE = 2000
    }
}

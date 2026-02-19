package com.remoteclaude.server.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.remoteclaude.server.ui.components.QrCodeImage

@Composable
fun NetworkInfoPanel(
    lanIp: String,
    port: Int,
    serverRunning: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Network Info", style = MaterialTheme.typography.headlineSmall)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoRow("Status", if (serverRunning) "Running" else "Stopped")
                InfoRow("IP Address", lanIp)
                InfoRow("Port", port.toString())
                InfoRow("WebSocket URL", "ws://$lanIp:$port")
                InfoRow("Plugin URL", "ws://localhost:$port/plugin")
                InfoRow("App URL", "ws://$lanIp:$port/app")
            }
        }

        if (serverRunning) {
            Text("Scan to connect", style = MaterialTheme.typography.labelMedium)
            QrCodeImage(
                content = "rc://$lanIp:$port",
                size = 200,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

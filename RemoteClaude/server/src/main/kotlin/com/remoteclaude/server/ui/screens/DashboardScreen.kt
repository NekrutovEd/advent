package com.remoteclaude.server.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.remoteclaude.server.protocol.GlobalTabInfo
import com.remoteclaude.server.state.AppEntry
import com.remoteclaude.server.state.PluginEntry
import com.remoteclaude.server.ui.components.StatusBadge

@Composable
fun DashboardScreen(
    plugins: List<PluginEntry>,
    apps: List<AppEntry>,
    tabs: List<GlobalTabInfo>,
    serverRunning: Boolean,
    serverPort: Int,
    lanIp: String,
    onTerminalClick: (GlobalTabInfo) -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Stats cards
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatCard("Plugins", plugins.size.toString(), Modifier.weight(1f))
                StatCard("Apps", apps.size.toString(), Modifier.weight(1f))
                StatCard("Tabs", tabs.size.toString(), Modifier.weight(1f))
            }
        }

        // Server status
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    StatusBadge(
                        text = if (serverRunning) "Server Running" else "Server Stopped",
                        online = serverRunning,
                    )
                    if (serverRunning) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "$lanIp:$serverPort",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Plugins section
        if (plugins.isNotEmpty()) {
            item {
                Text("PLUGINS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            items(plugins) { plugin ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusBadge(
                            text = "${plugin.ideName} - ${plugin.projectName}",
                            online = true,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            plugin.hostname,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Apps section
        if (apps.isNotEmpty()) {
            item {
                Text("APPS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            items(apps) { app ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusBadge(
                            text = app.deviceName,
                            online = true,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            app.platform,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Terminal sessions
        if (tabs.isNotEmpty()) {
            item {
                Text("TERMINAL SESSIONS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            items(tabs) { tab ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { onTerminalClick(tab) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tab.pluginName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(120.dp),
                        )
                        Text(
                            tab.title,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            tab.state.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
        }
    }
}

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
import com.remoteclaude.server.state.PluginEntry
import com.remoteclaude.server.ui.components.StatusBadge

@Composable
fun PluginsPanel(
    plugins: List<PluginEntry>,
    tabs: List<GlobalTabInfo>,
    onTerminalClick: (GlobalTabInfo) -> Unit = {},
    onCloseTab: (GlobalTabInfo) -> Unit = {},
    onNewTerminal: (pluginId: String) -> Unit = {},
) {
    if (plugins.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No plugins connected.\nStart an IDE with the RemoteClaude plugin.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(plugins) { plugin ->
            val pluginTabs = tabs.filter { it.pluginId == plugin.pluginId }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Plugin header
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusBadge(
                            text = "${plugin.ideName} - ${plugin.projectName}",
                            online = true,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "ONLINE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Host: ${plugin.hostname}  |  Tabs: ${pluginTabs.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Terminal tabs
                    if (pluginTabs.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        for (tab in pluginTabs) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable { onTerminalClick(tab) }
                                    .padding(vertical = 4.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    tab.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    tab.state.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                // Close tab button
                                TextButton(
                                    onClick = { onCloseTab(tab) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp),
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) {
                                    Text("\u2715", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }

                    // New Terminal button
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onNewTerminal(plugin.pluginId) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        Text("+ New Terminal")
                    }
                }
            }
        }
    }
}

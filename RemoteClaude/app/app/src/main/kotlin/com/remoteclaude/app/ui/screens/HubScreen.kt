package com.remoteclaude.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.remoteclaude.app.data.ws.PluginInfo
import com.remoteclaude.app.data.ws.TabInfo
import com.remoteclaude.app.data.ws.TabState
import com.remoteclaude.app.viewmodel.TerminalViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen(
    viewModel: TerminalViewModel,
    onPluginClick: (pluginId: String) -> Unit,
    onServerTerminalCreated: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    // Group tabs by pluginId
    val tabsByPlugin = uiState.tabs.groupBy {
        it.pluginId.ifEmpty { it.id.substringBefore(":") }
    }

    // Separate server terminals from plugin terminals
    val serverTabs = tabsByPlugin["server"] ?: emptyList()
    val allPluginEntries = uiState.plugins.filter { it.pluginId != "server" }
    val connectedPlugins = allPluginEntries.filter { it.connected }
    val disconnectedPlugins = allPluginEntries.filter { !it.connected }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RemoteClaude") },
                navigationIcon = {
                    IconButton(onClick = onDisconnect) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Disconnect")
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Connected plugin cards
            items(connectedPlugins, key = { it.pluginId }) { plugin ->
                val pluginTabs = tabsByPlugin[plugin.pluginId] ?: emptyList()
                PluginCard(
                    plugin = plugin,
                    tabs = pluginTabs,
                    onClick = { onPluginClick(plugin.pluginId) },
                )
            }

            // Disconnected (offline) plugin cards
            items(disconnectedPlugins, key = { it.pluginId }) { plugin ->
                OfflinePluginCard(
                    plugin = plugin,
                    onLaunchIde = { viewModel.launchIde(plugin.projectPath) },
                )
            }

            // Server terminals card (if any exist)
            if (serverTabs.isNotEmpty()) {
                item(key = "server") {
                    ServerTerminalsCard(
                        tabs = serverTabs,
                        onClick = { onPluginClick("server") },
                    )
                }
            }

            // "+" card to create server terminal
            item(key = "add") {
                OutlinedCard(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "New server terminal",
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "New Server Terminal",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateServerTerminalDialog(
            projects = uiState.projects,
            onDismiss = { showCreateDialog = false },
            onCreate = { workingDir ->
                showCreateDialog = false
                viewModel.selectPlugin("server")
                viewModel.createServerTerminal(workingDir)
                onServerTerminalCreated()
            },
        )
    }
}

@Composable
private fun PluginCard(
    plugin: PluginInfo,
    tabs: List<TabInfo>,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = plugin.projectName.ifEmpty { plugin.ideName },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${plugin.ideName} \u2022 ${plugin.hostname}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (tabs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                tabs.forEach { tab ->
                    TabRow(tab)
                }
            }
        }
    }
}

@Composable
private fun OfflinePluginCard(
    plugin: PluginInfo,
    onLaunchIde: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(0.6f),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plugin.projectName.ifEmpty { plugin.ideName },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${plugin.ideName} \u2022 ${plugin.hostname}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = "Offline",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (plugin.ideHomePath.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onLaunchIde,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Launch IDE")
                }
            }
        }
    }
}

@Composable
private fun ServerTerminalsCard(
    tabs: List<TabInfo>,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Server Terminals",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${tabs.size} terminal(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (tabs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                tabs.forEach { tab ->
                    TabRow(tab)
                }
            }
        }
    }
}

@Composable
private fun TabRow(tab: TabInfo) {
    val indicatorColor = when (tab.state) {
        TabState.RUNNING, TabState.STARTING -> Color(0xFF4CAF50)
        TabState.WAITING_INPUT, TabState.WAITING_TOOL -> Color(0xFFF44336)
        TabState.COMPLETED, TabState.FAILED -> Color(0xFF9E9E9E)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(indicatorColor, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = tab.title,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun CreateServerTerminalDialog(
    projects: List<com.remoteclaude.app.data.ws.ProjectInfo>,
    onDismiss: () -> Unit,
    onCreate: (workingDir: String?) -> Unit,
) {
    var customPath by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Server Terminal") },
        text = {
            Column {
                if (projects.isNotEmpty()) {
                    Text(
                        "Select a project:",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    projects.forEach { project ->
                        ListItem(
                            headlineContent = { Text(project.name) },
                            supportingContent = { Text(project.path, style = MaterialTheme.typography.bodySmall) },
                            modifier = Modifier.clickable { onCreate(project.path) },
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                Text(
                    "Or enter a path:",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customPath,
                    onValueChange = { customPath = it },
                    label = { Text("Working directory") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(customPath.ifBlank { null }) },
            ) {
                Text("Open")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

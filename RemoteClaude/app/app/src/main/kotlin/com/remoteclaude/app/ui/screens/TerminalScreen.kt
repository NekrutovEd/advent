package com.remoteclaude.app.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.remoteclaude.app.data.ws.WsClient
import com.remoteclaude.app.ui.components.TerminalControlBar
import com.remoteclaude.app.ui.components.TabBar
import com.remoteclaude.app.ui.components.TerminalView
import com.remoteclaude.app.viewmodel.TerminalViewModel

private const val TAG = "RC_DEBUG"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onDisconnect: () -> Unit,
    onCancelReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val activeTabId = uiState.activeTabId
    val activeTab = uiState.tabs.find { it.id == activeTabId }

    val selectedPlugin = uiState.plugins.find { it.pluginId == uiState.selectedPluginId }
    val filteredTabs = if (uiState.selectedPluginId != null) {
        uiState.tabs.filter {
            it.pluginId == uiState.selectedPluginId ||
                it.id.substringBefore(":") == uiState.selectedPluginId
        }
    } else {
        uiState.tabs
    }

    var pluginMenuExpanded by remember { mutableStateOf(false) }

    Log.d(TAG, "TerminalScreen: compose, tabs=${uiState.tabs.size}, activeTabId=$activeTabId, activeTab.state=${activeTab?.state}")

    Box(modifier = modifier) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        if (uiState.plugins.size > 1) {
                            Box {
                                Row(
                                    modifier = Modifier.clickable { pluginMenuExpanded = true },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(selectedPlugin?.projectName ?: "RemoteClaude")
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Switch project")
                                }
                                DropdownMenu(
                                    expanded = pluginMenuExpanded,
                                    onDismissRequest = { pluginMenuExpanded = false },
                                ) {
                                    uiState.plugins.forEach { plugin ->
                                        DropdownMenuItem(
                                            text = { Text(plugin.projectName) },
                                            onClick = {
                                                viewModel.selectPlugin(plugin.pluginId)
                                                pluginMenuExpanded = false
                                            },
                                            leadingIcon = if (plugin.pluginId == uiState.selectedPluginId) {
                                                { Text("\u2022", style = MaterialTheme.typography.titleLarge) }
                                            } else null,
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(selectedPlugin?.projectName ?: "RemoteClaude")
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDisconnect) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Disconnect")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.launchBareTerminal() }) {
                            Icon(Icons.Default.Add, contentDescription = "New terminal")
                        }
                    },
                )
            },
        ) { innerPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                // Tab bar
                if (filteredTabs.isNotEmpty()) {
                    TabBar(
                        tabs = filteredTabs,
                        activeTabId = activeTabId,
                        onTabClick = { viewModel.switchTab(it) },
                        onTabClose = { viewModel.closeTab(it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HorizontalDivider()
                }

                // Terminal view
                val visibleTabId = activeTabId?.takeIf { id -> filteredTabs.any { it.id == id } }
                Box(modifier = Modifier.weight(1f)) {
                    if (visibleTabId != null) {
                        key(visibleTabId) {
                            TerminalView(
                                initialContent = viewModel.getBuffer(visibleTabId),
                                outputFlow = viewModel.outputFlow(visibleTabId),
                                onInput = { viewModel.sendRawInput(visibleTabId, it) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("No terminal sessions", style = MaterialTheme.typography.bodyLarge)
                                Button(onClick = { viewModel.launchBareTerminal() }) { Text("New Terminal") }
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Terminal control buttons
                TerminalControlBar(
                    onRawInput = { data ->
                        visibleTabId?.let { viewModel.sendRawInput(it, data) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Reconnecting overlay
        if (connectionState is WsClient.ConnectionState.Reconnecting) {
            val attempt = (connectionState as WsClient.ConnectionState.Reconnecting).attempt
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    modifier = Modifier.padding(32.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "Reconnecting... (attempt $attempt)",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        OutlinedButton(onClick = {
                            onCancelReconnect()
                            onDisconnect()
                        }) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

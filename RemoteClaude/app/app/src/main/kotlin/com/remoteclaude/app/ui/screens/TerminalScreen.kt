package com.remoteclaude.app.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val activeTabId = uiState.activeTabId
    val activeTab = uiState.tabs.find { it.id == activeTabId }

    Log.d(TAG, "TerminalScreen: compose, tabs=${uiState.tabs.size}, activeTabId=$activeTabId, activeTab.state=${activeTab?.state}")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RemoteClaude") },
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
        modifier = modifier,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // Tab bar
            if (uiState.tabs.isNotEmpty()) {
                TabBar(
                    tabs = uiState.tabs,
                    activeTabId = activeTabId,
                    onTabClick = { viewModel.switchTab(it) },
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
            }

            // Terminal view
            Box(modifier = Modifier.weight(1f)) {
                if (activeTabId != null) {
                    key(activeTabId) {
                        TerminalView(
                            initialContent = viewModel.getBuffer(activeTabId),
                            outputFlow = viewModel.outputFlow(activeTabId),
                            onInput = { viewModel.sendRawInput(activeTabId, it) },
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
                    activeTabId?.let { viewModel.sendRawInput(it, data) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

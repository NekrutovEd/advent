package com.remoteclaude.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.remoteclaude.app.data.mdns.DiscoveredServer
import com.remoteclaude.app.data.ws.WsClient
import com.remoteclaude.app.viewmodel.ConnectViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    viewModel: ConnectViewModel,
    onScanQr: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val servers by viewModel.servers.collectAsState()
    val error by viewModel.error.collectAsState()
    val connectionState by viewModel.wsClient.connectionState.collectAsState()

    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("8765") }

    val isConnecting = connectionState is WsClient.ConnectionState.Connecting

    Scaffold(modifier = modifier) { innerPadding ->
    Column(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("RemoteClaude", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Connect to Android Studio",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Discovered servers
        Card {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Found on network",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (servers.isEmpty()) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (servers.isEmpty()) {
                    Text(
                        "Searching...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    servers.forEach { server ->
                        ServerItem(server = server, onClick = { viewModel.connect(server) })
                    }
                }
            }
        }

        // QR code scanner
        OutlinedButton(
            onClick = onScanQr,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Scan QR Code")
        }

        // Manual entry
        Card {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Manual connection", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = { manualHost = it },
                        label = { Text("IP Address") },
                        modifier = Modifier.weight(2f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(
                        value = manualPort,
                        onValueChange = { manualPort = it },
                        label = { Text("Port") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Button(
                    onClick = { viewModel.connect(manualHost, manualPort.toIntOrNull() ?: 8765) },
                    enabled = manualHost.isNotBlank() && !isConnecting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Connect")
                }
            }
        }

        error?.let { errorMsg ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    errorMsg,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
    } // Scaffold
}

@Composable
private fun ServerItem(server: DiscoveredServer, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Default.Computer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(server.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${server.host}:${server.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onClick) { Text("Connect") }
    }
}

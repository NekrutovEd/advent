package com.remoteclaude.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.remoteclaude.app.ui.screens.ConnectScreen
import com.remoteclaude.app.ui.screens.LaunchAgentScreen
import com.remoteclaude.app.ui.screens.QrScannerScreen
import com.remoteclaude.app.ui.screens.TerminalScreen
import com.remoteclaude.app.ui.theme.RemoteClaudeTheme
import com.remoteclaude.app.viewmodel.ConnectViewModel
import com.remoteclaude.app.viewmodel.TerminalViewModel

private const val TAG = "RC_DEBUG"

sealed class Screen {
    object Connect : Screen()
    object QrScanner : Screen()
    object Terminal : Screen()
    object Launch : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RemoteClaudeTheme {
                RemoteClaudeNavHost(deepLinkIntent = intent)
            }
        }
    }
}

@Composable
fun RemoteClaudeNavHost(deepLinkIntent: Intent?) {
    val connectVm: ConnectViewModel = viewModel()
    val terminalVm = remember { TerminalViewModel(connectVm.wsClient) }

    var screen by remember { mutableStateOf<Screen>(Screen.Connect) }

    Log.d(TAG, "NavHost: compose, screen=${screen::class.simpleName}")

    // Handle deep link rc://host:port
    LaunchedEffect(deepLinkIntent) {
        val uri = deepLinkIntent?.data
        Log.d(TAG, "NavHost: deepLink uri=$uri")
        if (uri?.scheme == "rc") {
            val host = uri.host ?: return@LaunchedEffect
            val port = uri.port.takeIf { it > 0 } ?: 8765
            Log.d(TAG, "NavHost: deep link connect $host:$port")
            connectVm.connect(host, port)
        }
    }

    // Navigate to terminal on connect
    LaunchedEffect(connectVm) {
        connectVm.navigateToTerminal.collect {
            Log.d(TAG, "NavHost: navigateToTerminal event -> switching to Terminal screen")
            screen = Screen.Terminal
        }
    }

    when (screen) {
        is Screen.Connect -> ConnectScreen(
            viewModel = connectVm,
            onScanQr = { screen = Screen.QrScanner },
        )
        is Screen.QrScanner -> QrScannerScreen(
            onResult = { host, port ->
                connectVm.connect(host, port)
                screen = Screen.Connect
            },
            onBack = { screen = Screen.Connect },
        )
        is Screen.Terminal -> TerminalScreen(
            viewModel = terminalVm,
            onDisconnect = {
                connectVm.wsClient.disconnect()
                screen = Screen.Connect
            },
        )
        is Screen.Launch -> LaunchAgentScreen(
            viewModel = terminalVm,
            onBack = { screen = Screen.Terminal },
        )
    }
}

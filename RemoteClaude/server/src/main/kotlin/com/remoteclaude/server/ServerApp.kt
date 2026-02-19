package com.remoteclaude.server

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.remoteclaude.server.net.MessageRouter
import com.remoteclaude.server.net.NetworkUtils
import com.remoteclaude.server.net.WebSocketHub
import com.remoteclaude.server.protocol.GlobalTabInfo
import com.remoteclaude.server.ui.screens.*
import com.remoteclaude.server.ui.terminal.TerminalWindow
import com.remoteclaude.server.ui.theme.RemoteClaudeServerTheme
import kotlinx.coroutines.launch

enum class Screen(val label: String) {
    DASHBOARD("Dashboard"),
    PLUGINS("Plugins"),
    APPS("Apps"),
    NETWORK("Network"),
    LOGS("Logs"),
}

@Composable
fun ServerApp(hub: WebSocketHub, router: MessageRouter, port: Int) {
    var currentScreen by remember { mutableStateOf(Screen.DASHBOARD) }

    val plugins by hub.pluginRegistry.pluginsFlow.collectAsState()
    val apps by hub.appRegistry.appsFlow.collectAsState()
    val tabs by hub.tabRegistry.tabsFlow.collectAsState()
    val running by hub.running.collectAsState()
    val lanIp = remember { NetworkUtils.getLanAddressString() }
    // Re-read logs each recomposition (they're lightweight)
    val logs = remember(tabs, plugins, apps) { router.logs }

    // Open terminal windows: globalTabId -> GlobalTabInfo
    val openTerminals = remember { mutableStateMapOf<String, GlobalTabInfo>() }
    val scope = rememberCoroutineScope()

    RemoteClaudeServerTheme {
        Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // Sidebar
            NavigationSidebar(
                currentScreen = currentScreen,
                onScreenSelected = { currentScreen = it },
                pluginCount = plugins.size,
                appCount = apps.size,
                serverRunning = running,
                port = port,
            )

            // Content
            Box(modifier = Modifier.fillMaxSize()) {
                when (currentScreen) {
                    Screen.DASHBOARD -> DashboardScreen(
                        plugins, apps, tabs, running, port, lanIp,
                        onTerminalClick = { tab -> openTerminals[tab.id] = tab },
                    )
                    Screen.PLUGINS -> PluginsPanel(
                        plugins, tabs,
                        onTerminalClick = { tab -> openTerminals[tab.id] = tab },
                        onCloseTab = { tab -> scope.launch { router.closeTab(tab.id) } },
                        onNewTerminal = { pluginId -> scope.launch { router.createTerminalForPlugin(pluginId) } },
                    )
                    Screen.APPS -> AppsPanel(apps)
                    Screen.NETWORK -> NetworkInfoPanel(lanIp, port, running)
                    Screen.LOGS -> LogsPanel(logs)
                }
            }
        }

        // Render open terminal windows
        for ((tabId, tab) in openTerminals) {
            TerminalWindow(
                tab = tab,
                tabRegistry = hub.tabRegistry,
                router = router,
                onClose = { openTerminals.remove(tabId) },
            )
        }
    }
}

@Composable
private fun NavigationSidebar(
    currentScreen: Screen,
    onScreenSelected: (Screen) -> Unit,
    pluginCount: Int,
    appCount: Int,
    serverRunning: Boolean,
    port: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(80.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (screen in Screen.entries) {
            val selected = currentScreen == screen
            val badge = when (screen) {
                Screen.PLUGINS -> if (pluginCount > 0) pluginCount.toString() else null
                Screen.APPS -> if (appCount > 0) appCount.toString() else null
                else -> null
            }
            SidebarItem(
                label = screen.label,
                selected = selected,
                badge = badge,
                onClick = { onScreenSelected(screen) },
            )
        }

        Spacer(Modifier.weight(1f))

        // Status indicator at bottom
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (serverRunning) Color(0xFF4CAF50) else Color(0xFF757575))
            )
            Spacer(Modifier.height(2.dp))
            Text(
                ":$port",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SidebarItem(
    label: String,
    selected: Boolean,
    badge: String?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (badge != null) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Text(badge, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(2.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

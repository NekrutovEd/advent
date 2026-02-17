package ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import state.AppState

@Composable
fun App(appState: AppState) {
    val scope = rememberCoroutineScope()
    val isBusy = appState.chat1.isLoading || appState.chat2.isLoading

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar with settings
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { appState.showSettings = true }) {
                        Text("\u2699 Settings")
                    }
                }

                // Prompt bar
                PromptBar(
                    onSend = { prompt -> appState.sendToAll(prompt, scope) },
                    enabled = !isBusy && appState.settings.apiKey.isNotBlank()
                )

                // Two-panel chat area
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // Chat 1 — no constraints
                    ChatPanel(
                        title = "Chat 1 (no constraints)",
                        chatState = appState.chat1,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )

                    VerticalDivider()

                    // Chat 2 — with constraints
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        ConstraintsField(
                            value = appState.constraints,
                            onValueChange = { appState.constraints = it }
                        )
                        ChatPanel(
                            title = "Chat 2 (with constraints)",
                            chatState = appState.chat2,
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                    }
                }
            }
        }

        if (appState.showSettings) {
            SettingsDialog(
                settings = appState.settings,
                onDismiss = { appState.showSettings = false }
            )
        }
    }
}

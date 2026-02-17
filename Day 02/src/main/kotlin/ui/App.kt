package ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import state.AppState
import state.ChatState

@Composable
fun App(appState: AppState) {
    val scope = rememberCoroutineScope()
    val isBusy = appState.isBusy
    var prompt by remember { mutableStateOf("") }

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

                // Global system prompt
                ConstraintsField(
                    value = appState.settings.systemPrompt,
                    onValueChange = { appState.settings.systemPrompt = it },
                    placeholder = "System prompt (global)..."
                )

                // Global user prompt
                PromptBar(
                    text = prompt,
                    onTextChange = { prompt = it },
                    onSend = { if (prompt.isNotBlank()) appState.sendToAll(prompt, scope) },
                    enabled = !isBusy && appState.settings.apiKey.isNotBlank(),
                    onClearAll = { appState.clearAll() }
                )

                // Dynamic chat area
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    appState.chats.forEachIndexed { index, chatState ->
                        if (index > 0) {
                            VerticalDivider()
                        }

                        ChatColumn(
                            title = "Chat ${index + 1}",
                            chatState = chatState,
                            prompt = prompt,
                            onSend = { appState.sendToOne(chatState, prompt, scope) },
                            enabled = !chatState.isLoading && appState.settings.apiKey.isNotBlank(),
                            onDrop = if (index > 0) {{ appState.removeChat(index) }} else null,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }

                    // Add chat button
                    VerticalDivider()
                    TextButton(
                        onClick = { appState.addChat() },
                        modifier = Modifier.fillMaxHeight().width(48.dp)
                    ) {
                        Text("+", style = MaterialTheme.typography.headlineMedium)
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

@Composable
private fun ChatColumn(
    title: String,
    chatState: ChatState,
    prompt: String,
    onSend: () -> Unit,
    enabled: Boolean,
    onDrop: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    ChatPanel(
        title = title,
        chatState = chatState,
        prompt = prompt,
        onSend = onSend,
        enabled = enabled,
        modifier = modifier,
        onDrop = onDrop
    )
}

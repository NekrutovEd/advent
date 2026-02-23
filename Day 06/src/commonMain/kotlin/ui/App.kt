package ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import i18n.LocalStrings
import i18n.stringsFor
import state.AppState
import state.ChatState

@Composable
fun App(appState: AppState) {
    val scope = rememberCoroutineScope()
    val isBusy = appState.isBusy
    var prompt by remember { mutableStateOf("") }
    var isTopCollapsed by remember { mutableStateOf(false) }

    val hasApiKey = appState.settings.apiConfigs.any { it.apiKey.isNotBlank() }

    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(LocalStrings provides stringsFor(appState.settings.lang)) {
            val s = LocalStrings.current
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    // Top bar with collapse toggle and settings
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { isTopCollapsed = !isTopCollapsed }) {
                            Text(if (isTopCollapsed) "\u25BC" else "\u25B2")
                        }
                        TextButton(onClick = { appState.showSettings = true }) {
                            Text("\u2699 ${s.settingsTitle}")
                        }
                    }

                    // Collapsible: global system prompt + model selector + prompt bar
                    AnimatedVisibility(
                        visible = !isTopCollapsed,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        var modelSelectorExpanded by remember { mutableStateOf(false) }
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ConstraintsField(
                                    value = appState.settings.systemPrompt,
                                    onValueChange = { appState.settings.systemPrompt = it },
                                    placeholder = s.systemPromptGlobal,
                                    modifier = Modifier.weight(1f)
                                )
                                Box(modifier = Modifier.padding(end = 8.dp)) {
                                    OutlinedButton(onClick = { modelSelectorExpanded = true }) {
                                        Text(
                                            appState.settings.selectedModel,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = modelSelectorExpanded,
                                        onDismissRequest = { modelSelectorExpanded = false }
                                    ) {
                                        appState.settings.allModels().forEach { model ->
                                            DropdownMenuItem(
                                                text = { Text(model) },
                                                onClick = {
                                                    appState.settings.selectedModel = model
                                                    modelSelectorExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            PromptBar(
                                text = prompt,
                                onTextChange = { prompt = it },
                                onSend = { if (prompt.isNotBlank()) appState.sendToAll(prompt, scope) },
                                enabled = !isBusy && hasApiKey,
                                onClearAll = { appState.clearAll() }
                            )
                        }
                    }

                    // Dynamic chat area with minimum width and horizontal scroll
                    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val chatCount = appState.chats.size.coerceAtLeast(1)
                        // Scrollable area excludes the + button (48dp).
                        // minWidth of 260dp ensures a natural peek effect when there are 2+ chats:
                        // the next chat is visibly clipped, hinting the user to scroll.
                        val scrollAreaWidth = maxWidth - 48.dp
                        val chatWidth = maxOf(scrollAreaWidth - 28.dp, scrollAreaWidth / chatCount)

                        Row(modifier = Modifier.fillMaxSize()) {
                            // Scrollable chats
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .horizontalScroll(rememberScrollState())
                            ) {
                                appState.chats.forEachIndexed { index, chatState ->
                                    if (index > 0) {
                                        VerticalDivider()
                                    }

                                    ChatColumn(
                                        title = s.chatTitle(index),
                                        chatState = chatState,
                                        prompt = prompt,
                                        onSend = { appState.sendToOne(chatState, prompt, scope) },
                                        enabled = !chatState.isLoading && hasApiKey,
                                        onDrop = if (index > 0) {{ appState.removeChat(index) }} else null,
                                        availableModels = appState.settings.allModels(),
                                        globalModel = appState.settings.selectedModel,
                                        modifier = Modifier.width(350.dp).fillMaxHeight()
                                    )
                                }
                            }

                            // Add chat button — always visible outside scroll
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
            }

            if (appState.showSettings) {
                SettingsDialog(
                    settings = appState.settings,
                    onDismiss = { appState.showSettings = false }
                )
            }
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
    availableModels: List<String>,
    globalModel: String,
    onDrop: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    ChatPanel(
        title = title,
        chatState = chatState,
        prompt = prompt,
        onSend = onSend,
        enabled = enabled,
        availableModels = availableModels,
        globalModel = globalModel,
        modifier = modifier,
        onDrop = onDrop
    )
}

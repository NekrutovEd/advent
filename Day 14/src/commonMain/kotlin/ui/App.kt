package ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import i18n.LocalStrings
import i18n.stringsFor
import kotlinx.coroutines.delay
import state.AppState
import state.ChatState

@Composable
fun App(appState: AppState) {
    val scope = rememberCoroutineScope()
    val isBusy = appState.isBusy
    var prompt by remember { mutableStateOf("") }
    var isTopCollapsed by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }

    val hasApiKey = appState.settings.apiConfigs.any { it.apiKey.isNotBlank() }

    // Load persisted state on first composition
    LaunchedEffect(Unit) {
        appState.loadFromStorage()
    }

    // Auto-save every 60 seconds
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            appState.saveToStorage()
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(LocalStrings provides stringsFor(appState.settings.lang)) {
            val s = LocalStrings.current
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    // Top bar: collapse toggle + session tab bar + settings
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { isTopCollapsed = !isTopCollapsed }) {
                            Text(if (isTopCollapsed) "\u25BC" else "\u25B2")
                        }
                        SessionTabBar(
                            sessions = appState.sessions,
                            archivedSessions = appState.archivedSessions,
                            activeIndex = appState.activeSessionIndex,
                            onSelectSession = { appState.selectSession(it) },
                            onAddSession = { appState.addSession() },
                            onDeleteSession = { appState.deleteSession(it) },
                            onRenameSession = { index, name ->
                                appState.sessions.getOrNull(index)?.name = name
                            },
                            onRestoreFromArchive = { appState.restoreSession(it) },
                            onDeleteFromArchive = { appState.deleteFromArchive(it) },
                            onClearArchive = { appState.clearArchive() },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { appState.showMemoryPanel = !appState.showMemoryPanel }) {
                            Text(s.memoryPanelTitle)
                        }
                        // Profile selector
                        Box {
                            var profileDropdownExpanded by remember { mutableStateOf(false) }
                            val activeProfile = appState.profiles.firstOrNull { it.id == appState.activeProfileId }
                            OutlinedButton(
                                onClick = { profileDropdownExpanded = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    (activeProfile?.name ?: s.noProfileSelected) + " \u25BC",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            DropdownMenu(
                                expanded = profileDropdownExpanded,
                                onDismissRequest = { profileDropdownExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(s.noProfileSelected, style = MaterialTheme.typography.bodySmall) },
                                    onClick = {
                                        appState.selectProfile(null)
                                        profileDropdownExpanded = false
                                    }
                                )
                                appState.profiles.forEach { profile ->
                                    DropdownMenuItem(
                                        text = { Text(profile.name, style = MaterialTheme.typography.bodySmall) },
                                        onClick = {
                                            appState.selectProfile(profile.id)
                                            profileDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = { showProfileDialog = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = s.editProfile, modifier = Modifier.size(18.dp))
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
                                                trailingIcon = { ModelInfoIcon(model) },
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
                                onSend = {
                                    if (prompt.isNotBlank()) {
                                        val session = appState.activeSession
                                        val isFirst = session.chats.all { it.messages.isEmpty() }
                                        val sessionIdx = appState.activeSessionIndex
                                        val capturedPrompt = prompt
                                        appState.sendToAll(capturedPrompt, scope)
                                        if (isFirst && session.name == "New") {
                                            appState.autoRenameSession(sessionIdx, capturedPrompt, scope)
                                        }
                                    }
                                },
                                enabled = !isBusy && hasApiKey,
                                onClearAll = { appState.activeSession.clearAll() }
                            )
                        }
                    }

                    // Dynamic chat area with minimum width and horizontal scroll
                    val activeSession = appState.activeSession
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            val chatCount = activeSession.chats.size.coerceAtLeast(1)
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
                                    activeSession.chats.forEachIndexed { index, chatState ->
                                        if (index > 0) {
                                            VerticalDivider()
                                        }

                                        ChatColumn(
                                            title = s.chatTitle(index),
                                            chatState = chatState,
                                            prompt = prompt,
                                            onSend = {
                                                val session = appState.activeSession
                                                val isFirst = session.chats.all { it.messages.isEmpty() }
                                                val sessionIdx = appState.activeSessionIndex
                                                val capturedPrompt = prompt
                                                appState.sendToOne(chatState, capturedPrompt, scope)
                                                if (isFirst && session.name == "New") {
                                                    appState.autoRenameSession(sessionIdx, capturedPrompt, scope)
                                                }
                                            },
                                            enabled = !chatState.isLoading && hasApiKey,
                                            onClone = { activeSession.cloneChat(index) },
                                            onDrop = if (index > 0) {{ activeSession.removeChat(index) }} else null,
                                            availableModels = appState.settings.allModels(),
                                            globalModel = appState.settings.selectedModel,
                                            modifier = Modifier.width(350.dp).fillMaxHeight()
                                        )
                                    }
                                }

                                // Add chat button — always visible outside scroll
                                VerticalDivider()
                                TextButton(
                                    onClick = { activeSession.addChat() },
                                    modifier = Modifier.fillMaxHeight().width(48.dp)
                                ) {
                                    Text("+", style = MaterialTheme.typography.headlineMedium)
                                }
                            }
                        }

                        if (appState.showMemoryPanel) {
                            VerticalDivider()
                            MemoryPanel(
                                workingMemory = activeSession.workingMemory,
                                longTermMemory = appState.longTermMemory,
                                onAddWorkingItem = { activeSession.addWorkingMemoryItem(it, state.MemorySource.MANUAL, appState.currentTimeMs()) },
                                onRemoveWorkingItem = { activeSession.removeWorkingMemoryItem(it) },
                                onEditWorkingItem = { id, text -> activeSession.updateWorkingMemoryItem(id, text) },
                                onAddLongTermItem = { appState.addLongTermMemoryItem(it, state.MemorySource.MANUAL, appState.currentTimeMs()) },
                                onRemoveLongTermItem = { appState.removeLongTermMemoryItem(it) },
                                onEditLongTermItem = { id, text -> appState.updateLongTermMemoryItem(id, text) },
                                onPromoteItem = { appState.promoteToLongTerm(activeSession, it, appState.currentTimeMs()) },
                                invariants = appState.invariants,
                                onAddInvariant = { appState.addInvariant(it, appState.currentTimeMs()) },
                                onRemoveInvariant = { appState.removeInvariant(it) },
                                onEditInvariant = { id, text -> appState.updateInvariant(id, text) },
                                modifier = Modifier.width(280.dp).fillMaxHeight()
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

            if (showProfileDialog) {
                ProfileDialog(
                    profiles = appState.profiles,
                    activeProfileId = appState.activeProfileId,
                    onAddProfile = { appState.addProfile() },
                    onRemoveProfile = { appState.removeProfile(it) },
                    onSelectProfile = { appState.selectProfile(it) },
                    onRenameProfile = { id, name -> appState.renameProfile(id, name) },
                    onAddProfileItem = { id, content -> appState.addProfileItem(id, content) },
                    onRemoveProfileItem = { id, index -> appState.removeProfileItem(id, index) },
                    onUpdateProfileItem = { id, index, content -> appState.updateProfileItem(id, index, content) },
                    onDismiss = { showProfileDialog = false }
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
    onClone: () -> Unit = {},
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
        onClone = onClone,
        onDrop = onDrop
    )
}

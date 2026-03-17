package ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import i18n.Lang
import i18n.LocalStrings
import kotlinx.coroutines.launch
import state.ApiConfig
import state.McpOrchestrator
import state.McpServerEntry
import state.SettingsState

private class ContextDraft(settings: SettingsState) {
    var sendHistory by mutableStateOf(settings.defaultSendHistory)
    var autoSummarize by mutableStateOf(settings.defaultAutoSummarize)
    var threshold by mutableStateOf(settings.defaultSummarizeThreshold)
    var keepLast by mutableStateOf(settings.defaultKeepLastMessages)
    var slidingWindow by mutableStateOf(settings.defaultSlidingWindow)
    var extractMemory by mutableStateOf(settings.defaultExtractMemory)
    var taskTracking by mutableStateOf(settings.defaultTaskTracking)
}

private class ApiConfigDraft(config: ApiConfig) {
    var apiKey by mutableStateOf(config.apiKey)
    var temperature by mutableStateOf(config.temperature)
    var maxTokens by mutableStateOf(config.maxTokens)
    var connectTimeout by mutableStateOf(config.connectTimeout)
    var readTimeout by mutableStateOf(config.readTimeout)
}

@Composable
fun SettingsDialog(
    settings: SettingsState,
    orchestrator: McpOrchestrator? = null,
    onDismiss: () -> Unit
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val contextDraft = remember { ContextDraft(settings) }
    val drafts = remember { settings.apiConfigs.map { ApiConfigDraft(it) } }
    val expandedApis = remember {
        mutableStateMapOf<String, Boolean>().also { map ->
            settings.apiConfigs.firstOrNull()?.let { map[it.id] = true }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.settingsTitle) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .width(400.dp)
                    .heightIn(max = 550.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Language selector — applied immediately
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(s.language, style = MaterialTheme.typography.bodyMedium)
                    Lang.entries.forEach { lang ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = settings.lang == lang,
                                onClick = { settings.lang = lang }
                            )
                            Text(lang.displayName)
                        }
                    }
                }

                HorizontalDivider()

                // Context global defaults
                Text(s.globalContext, style = MaterialTheme.typography.titleSmall)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Switch(
                        checked = contextDraft.sendHistory,
                        onCheckedChange = { contextDraft.sendHistory = it }
                    )
                    Text(s.sendHistory, style = MaterialTheme.typography.bodyMedium)
                }
                if (contextDraft.sendHistory) {
                    OutlinedTextField(
                        value = contextDraft.slidingWindow,
                        onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) contextDraft.slidingWindow = it },
                        label = { Text(s.slidingWindowLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Switch(
                            checked = contextDraft.autoSummarize,
                            onCheckedChange = { contextDraft.autoSummarize = it }
                        )
                        Text(s.autoSummarize, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (contextDraft.autoSummarize) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = contextDraft.threshold,
                                onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) contextDraft.threshold = it },
                                label = { Text(s.summarizeThresholdLabel) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = contextDraft.keepLast,
                                onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) contextDraft.keepLast = it },
                                label = { Text(s.keepLastLabel) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Switch(
                        checked = contextDraft.extractMemory,
                        onCheckedChange = { contextDraft.extractMemory = it }
                    )
                    Text(s.extractMemory, style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Switch(
                        checked = contextDraft.taskTracking,
                        onCheckedChange = { contextDraft.taskTracking = it }
                    )
                    Text(s.taskTrackingLabel, style = MaterialTheme.typography.bodyMedium)
                }

                HorizontalDivider()

                // API providers list
                settings.apiConfigs.forEachIndexed { index, config ->
                    val draft = drafts[index]
                    val isExpanded = expandedApis[config.id] == true

                    Column {
                        // Section header with expand toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                config.name,
                                style = MaterialTheme.typography.titleSmall
                            )
                            TextButton(onClick = {
                                expandedApis[config.id] = !isExpanded
                            }) {
                                Text(if (isExpanded) "\u25B2" else "\u25BC")
                            }
                        }

                        AnimatedVisibility(
                            visible = isExpanded,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = draft.apiKey,
                                    onValueChange = { draft.apiKey = it },
                                    label = { Text(s.apiKey) },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Column {
                                    Text(
                                        s.temperatureValue("%.1f".format(draft.temperature)),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Slider(
                                        value = draft.temperature,
                                        onValueChange = { draft.temperature = it },
                                        valueRange = 0f..2f,
                                        steps = 19
                                    )
                                }

                                OutlinedTextField(
                                    value = draft.maxTokens,
                                    onValueChange = { newValue ->
                                        if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                                            draft.maxTokens = newValue
                                        }
                                    },
                                    label = { Text(s.maxTokensLabel) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = draft.connectTimeout,
                                        onValueChange = { newValue ->
                                            if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                                                draft.connectTimeout = newValue
                                            }
                                        },
                                        label = { Text(s.connectTimeout) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = draft.readTimeout,
                                        onValueChange = { newValue ->
                                            if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                                                draft.readTimeout = newValue
                                            }
                                        },
                                        label = { Text(s.readTimeout) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    if (index < settings.apiConfigs.size - 1) {
                        HorizontalDivider()
                    }
                }

                // MCP Orchestration section
                if (orchestrator != null) {
                    HorizontalDivider()
                    McpOrchestrationSection(orchestrator, s, scope)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                settings.defaultSendHistory = contextDraft.sendHistory
                settings.defaultAutoSummarize = contextDraft.autoSummarize
                settings.defaultSummarizeThreshold = contextDraft.threshold
                settings.defaultKeepLastMessages = contextDraft.keepLast
                settings.defaultSlidingWindow = contextDraft.slidingWindow
                settings.defaultExtractMemory = contextDraft.extractMemory
                settings.defaultTaskTracking = contextDraft.taskTracking
                settings.apiConfigs.forEachIndexed { index, config ->
                    val draft = drafts[index]
                    config.apiKey = draft.apiKey
                    config.temperature = draft.temperature
                    config.maxTokens = draft.maxTokens
                    config.connectTimeout = draft.connectTimeout
                    config.readTimeout = draft.readTimeout
                }
                onDismiss()
            }) {
                Text(s.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(s.cancel)
            }
        }
    )
}

@Composable
private fun McpOrchestrationSection(
    orchestrator: McpOrchestrator,
    s: i18n.Strings,
    scope: kotlinx.coroutines.CoroutineScope
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s.mcpSectionTitle, style = MaterialTheme.typography.titleSmall)

        // Orchestrator status
        if (orchestrator.connectedCount > 0) {
            Text(
                s.mcpOrchestratorStatus(orchestrator.connectedCount, orchestrator.allTools.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Global actions
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { scope.launch { orchestrator.connectAll() } },
                enabled = orchestrator.servers.any { !it.isConnected && it.serverCommand.isNotBlank() },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(s.mcpConnectAll, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                onClick = { orchestrator.disconnectAll() },
                enabled = orchestrator.connectedCount > 0,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(s.mcpDisconnectAll, style = MaterialTheme.typography.bodySmall)
            }
        }

        // Server entries
        orchestrator.servers.forEachIndexed { index, entry ->
            McpServerCard(entry, s, scope, onRemove = { orchestrator.removeServer(entry.id) })
            if (index < orchestrator.servers.size - 1) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        // Add server button
        OutlinedButton(
            onClick = { orchestrator.addServer() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(s.mcpAddServer)
        }
    }
}

@Composable
private fun McpServerCard(
    entry: McpServerEntry,
    s: i18n.Strings,
    scope: kotlinx.coroutines.CoroutineScope,
    onRemove: () -> Unit
) {
    var expanded by remember { mutableStateOf(!entry.isConnected) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Header row: label + status + expand toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Status dot
                val dotColor = when {
                    entry.isConnected -> MaterialTheme.colorScheme.primary
                    entry.error != null -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(
                    color = dotColor,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.size(8.dp)
                ) {}

                Text(
                    entry.label.ifBlank { entry.serverCommand.ifBlank { "New Server" } },
                    style = MaterialTheme.typography.bodyMedium
                )

                if (entry.isConnected && entry.tools.isNotEmpty()) {
                    Text(
                        "(${entry.tools.size})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "\u25B2" else "\u25BC", style = MaterialTheme.typography.bodySmall)
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = entry.label,
                    onValueChange = { entry.label = it },
                    label = { Text(s.mcpServerLabel) },
                    placeholder = { Text(s.mcpServerLabelPlaceholder) },
                    singleLine = true,
                    enabled = !entry.isConnected,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = entry.serverCommand,
                    onValueChange = { entry.serverCommand = it },
                    label = { Text(s.mcpServerCommand) },
                    singleLine = true,
                    enabled = !entry.isConnected && !entry.isConnecting,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = entry.serverArgs,
                    onValueChange = { entry.serverArgs = it },
                    label = { Text(s.mcpServerArgs) },
                    singleLine = true,
                    enabled = !entry.isConnected && !entry.isConnecting,
                    modifier = Modifier.fillMaxWidth()
                )

                // Connect/Disconnect + Remove
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!entry.isConnected) {
                        Button(
                            onClick = { scope.launch { entry.connect() } },
                            enabled = !entry.isConnecting && entry.serverCommand.isNotBlank(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(if (entry.isConnecting) s.mcpConnecting else s.mcpConnect)
                        }
                    } else {
                        Button(
                            onClick = { entry.disconnect() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(s.mcpDisconnect)
                        }
                    }

                    TextButton(
                        onClick = onRemove,
                        enabled = !entry.isConnecting,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            s.mcpRemoveServer,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Server info
                if (entry.serverName.isNotBlank()) {
                    Text(
                        s.mcpServerInfo(entry.serverName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Error (selectable so user can copy)
                if (entry.error != null) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            entry.error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Tools list
                if (entry.tools.isNotEmpty()) {
                    Text(
                        "${s.mcpToolsTitle} (${s.mcpToolCount(entry.tools.size)})",
                        style = MaterialTheme.typography.labelMedium
                    )
                    entry.tools.forEach { tool ->
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(tool.name, style = MaterialTheme.typography.bodySmall)
                            if (tool.description.isNotBlank()) {
                                Text(
                                    tool.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                } else if (entry.isConnected) {
                    Text(s.mcpNoTools, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

package ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
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
import state.McpState
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
    mcpState: McpState? = null,
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
                modifier = Modifier.width(350.dp)
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

                // MCP section
                if (mcpState != null) {
                    HorizontalDivider()
                    McpSection(mcpState, s, scope)
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
private fun McpSection(
    mcpState: McpState,
    s: i18n.Strings,
    scope: kotlinx.coroutines.CoroutineScope
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s.mcpSectionTitle, style = MaterialTheme.typography.titleSmall)

        OutlinedTextField(
            value = mcpState.serverCommand,
            onValueChange = { mcpState.serverCommand = it },
            label = { Text(s.mcpServerCommand) },
            singleLine = true,
            enabled = !mcpState.isConnected && !mcpState.isConnecting,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = mcpState.serverArgs,
            onValueChange = { mcpState.serverArgs = it },
            label = { Text(s.mcpServerArgs) },
            singleLine = true,
            enabled = !mcpState.isConnected && !mcpState.isConnecting,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!mcpState.isConnected) {
                Button(
                    onClick = { scope.launch { mcpState.connect() } },
                    enabled = !mcpState.isConnecting && mcpState.serverCommand.isNotBlank()
                ) {
                    Text(if (mcpState.isConnecting) s.mcpConnecting else s.mcpConnect)
                }
            } else {
                Button(onClick = { mcpState.disconnect() }) {
                    Text(s.mcpDisconnect)
                }
            }

            // Status indicator
            val statusColor = when {
                mcpState.isConnected -> MaterialTheme.colorScheme.primary
                mcpState.error != null -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val statusText = when {
                mcpState.isConnecting -> s.mcpConnecting
                mcpState.isConnected -> s.mcpConnected
                else -> s.mcpDisconnected
            }
            Text(statusText, color = statusColor, style = MaterialTheme.typography.bodySmall)
        }

        // Server info
        if (mcpState.serverName.isNotBlank()) {
            Text(
                s.mcpServerInfo(mcpState.serverName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Error
        if (mcpState.error != null) {
            Text(
                mcpState.error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Tools list
        if (mcpState.tools.isNotEmpty()) {
            Text(
                "${s.mcpToolsTitle} (${s.mcpToolCount(mcpState.tools.size)})",
                style = MaterialTheme.typography.labelMedium
            )
            mcpState.tools.forEach { tool ->
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(tool.name, style = MaterialTheme.typography.bodyMedium)
                    if (tool.description.isNotBlank()) {
                        Text(
                            tool.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else if (mcpState.isConnected) {
            Text(s.mcpNoTools, style = MaterialTheme.typography.bodySmall)
        }
    }
}

package ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import state.ChatOption
import state.ChatState

@Composable
fun ChatPanel(
    title: String,
    chatState: ChatState,
    modifier: Modifier = Modifier,
    onDrop: (() -> Unit)? = null
) {
    val listState = rememberLazyListState()
    var showOptions by remember { mutableStateOf(false) }

    LaunchedEffect(chatState.messages.size) {
        if (chatState.messages.isNotEmpty()) {
            listState.animateScrollToItem(chatState.messages.size - 1)
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.width(4.dp))
                Box {
                    IconButton(onClick = { showOptions = true }) {
                        Text("\u2699")
                    }
                    DropdownMenu(
                        expanded = showOptions,
                        onDismissRequest = { showOptions = false }
                    ) {
                        ChatOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = option in chatState.visibleOptions,
                                            onCheckedChange = null
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(option.label)
                                    }
                                },
                                onClick = { chatState.toggleOption(option) }
                            )
                        }
                    }
                }
            }
            Row {
                TextButton(onClick = { chatState.clear() }) {
                    Text("Clear")
                }
                if (onDrop != null) {
                    TextButton(onClick = onDrop) {
                        Text("\u2212")
                    }
                }
            }
        }

        if (ChatOption.SYSTEM_PROMPT in chatState.visibleOptions) {
            ConstraintsField(
                value = chatState.systemPrompt,
                onValueChange = { chatState.systemPrompt = it },
                placeholder = "System prompt (per-chat)..."
            )
        }
        if (ChatOption.CONSTRAINTS in chatState.visibleOptions) {
            ConstraintsField(
                value = chatState.constraints,
                onValueChange = { chatState.constraints = it },
                placeholder = "Additional prompt (per-chat)..."
            )
        }
        if (ChatOption.STOP_WORDS in chatState.visibleOptions) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                chatState.stopWords.forEachIndexed { index, word ->
                    OutlinedTextField(
                        value = word,
                        onValueChange = { chatState.stopWords[index] = it },
                        placeholder = { Text("Stop ${index + 1}") },
                        singleLine = true,
                        modifier = Modifier.width(120.dp)
                    )
                }
                if (chatState.stopWords.size < 4) {
                    TextButton(onClick = { chatState.addStopWord() }) {
                        Text("+")
                    }
                }
                if (chatState.stopWords.size > 1) {
                    TextButton(onClick = { chatState.removeStopWord() }) {
                        Text("\u2212")
                    }
                }
            }
        }
        if (ChatOption.MAX_TOKENS in chatState.visibleOptions) {
            OutlinedTextField(
                value = chatState.maxTokensOverride,
                onValueChange = { chatState.maxTokensOverride = it },
                placeholder = { Text("Max tokens (override)...") },
                singleLine = true,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).width(200.dp)
            )
        }
        if (ChatOption.STATISTICS in chatState.visibleOptions) {
            StatisticsRow(chatState)
        }
        if (ChatOption.RESPONSE_FORMAT in chatState.visibleOptions) {
            var expanded by remember { mutableStateOf(false) }
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        OutlinedButton(onClick = { expanded = true }) {
                            Text(chatState.responseFormatType)
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            listOf("text", "json_object", "json_schema").forEach { fmt ->
                                DropdownMenuItem(
                                    text = { Text(fmt) },
                                    onClick = {
                                        chatState.responseFormatType = fmt
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                if (chatState.responseFormatType == "json_schema") {
                    OutlinedTextField(
                        value = chatState.jsonSchema,
                        onValueChange = { chatState.jsonSchema = it },
                        placeholder = { Text("JSON Schema...") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                        maxLines = 5
                    )
                }
            }
        }

        HorizontalDivider()

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(4.dp)
            ) {
                items(chatState.messages) { message ->
                    MessageBubble(message)
                }
            }

            if (chatState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                    strokeWidth = 2.dp
                )
            }
        }

        if (chatState.error != null) {
            Text(
                text = chatState.error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatisticsRow(chatState: ChatState) {
    TooltipArea(
        tooltip = {
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 4.dp,
                shadowElevation = 4.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    val usage = chatState.lastUsage
                    if (usage != null) {
                        Text("Last request", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        TooltipEntry(usage.promptTokens, "Prompt Tokens", "Tokens sent in the request")
                        TooltipEntry(usage.completionTokens, "Completion Tokens", "Tokens generated by the model")
                        TooltipEntry(usage.totalTokens, "Total Tokens", "Sum of prompt and completion tokens")
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                    Text("Session total", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    TooltipEntry(chatState.totalPromptTokens, "Prompt Tokens", "All prompt tokens this session")
                    TooltipEntry(chatState.totalCompletionTokens, "Completion Tokens", "All completion tokens this session")
                    TooltipEntry(chatState.totalTokens, "Total Tokens", "All tokens consumed this session")
                }
            }
        },
        delayMillis = 300
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            val usage = chatState.lastUsage
            if (usage != null) {
                Text(
                    text = "Last: P:${usage.promptTokens} C:${usage.completionTokens} T:${usage.totalTokens}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = "Total: P:${chatState.totalPromptTokens} C:${chatState.totalCompletionTokens} T:${chatState.totalTokens}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun TooltipEntry(value: Int, label: String, description: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        text = description,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
}

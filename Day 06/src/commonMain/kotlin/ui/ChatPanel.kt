package ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import i18n.LocalStrings
import state.ChatOption
import state.ChatState

@Composable
fun ChatPanel(
    title: String,
    chatState: ChatState,
    prompt: String,
    onSend: () -> Unit,
    enabled: Boolean,
    availableModels: List<String>,
    globalModel: String,
    modifier: Modifier = Modifier,
    onDrop: (() -> Unit)? = null
) {
    val s = LocalStrings.current
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
                                        Text(option.label(s))
                                    }
                                },
                                onClick = { chatState.toggleOption(option) }
                            )
                        }
                    }
                }
            }
            Row {
                IconButton(
                    onClick = onSend,
                    enabled = enabled && prompt.isNotBlank()
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { chatState.clear() }) {
                    Icon(Icons.Default.Delete, contentDescription = null)
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
                placeholder = s.systemPromptPerChat
            )
        }
        if (ChatOption.CONSTRAINTS in chatState.visibleOptions) {
            ConstraintsField(
                value = chatState.constraints,
                onValueChange = { chatState.constraints = it },
                placeholder = s.constraintsPerChat
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
                        placeholder = { Text(s.stopWordPlaceholder(index)) },
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
                placeholder = { Text(s.maxTokensOverride) },
                singleLine = true,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).width(200.dp)
            )
        }
        if (ChatOption.TEMPERATURE in chatState.visibleOptions) {
            val tempValue = chatState.temperatureOverride ?: 1.0f
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(
                    s.temperatureValue("%.1f".format(tempValue)),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = tempValue,
                    onValueChange = { chatState.temperatureOverride = it },
                    valueRange = 0f..2f,
                    steps = 19
                )
            }
        }
        if (ChatOption.MODEL in chatState.visibleOptions) {
            var modelDropdownExpanded by remember { mutableStateOf(false) }
            val displayModel = chatState.modelOverride ?: globalModel
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    OutlinedButton(onClick = { modelDropdownExpanded = true }) {
                        Text(displayModel, style = MaterialTheme.typography.bodySmall)
                    }
                    DropdownMenu(
                        expanded = modelDropdownExpanded,
                        onDismissRequest = { modelDropdownExpanded = false }
                    ) {
                        availableModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model) },
                                onClick = {
                                    chatState.modelOverride = model
                                    modelDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }
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
                        placeholder = { Text(s.jsonSchemaPlaceholder) },
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

@Composable
internal expect fun StatisticsRow(chatState: ChatState)

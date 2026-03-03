package ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import api.ChatMessage
import i18n.LocalStrings
import state.MemoryItem
import state.MemorySource

@Composable
fun MemoryPanel(
    shortTermMessages: List<ChatMessage>,
    workingMemory: List<MemoryItem>,
    longTermMemory: List<MemoryItem>,
    onAddWorkingItem: (String) -> Unit,
    onRemoveWorkingItem: (String) -> Unit,
    onEditWorkingItem: (String, String) -> Unit,
    onAddLongTermItem: (String) -> Unit,
    onRemoveLongTermItem: (String) -> Unit,
    onEditLongTermItem: (String, String) -> Unit,
    onPromoteItem: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    var selectedTab by remember { mutableStateOf(1) } // default to Working tab

    Column(modifier = modifier.padding(4.dp)) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text(s.shortTermMemoryTab, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text(s.workingMemoryTab, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
            }
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                Text(s.longTermMemoryTab, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
            }
        }

        when (selectedTab) {
            0 -> ShortTermTab(shortTermMessages)
            1 -> MemoryListTab(
                items = workingMemory,
                onAdd = onAddWorkingItem,
                onRemove = onRemoveWorkingItem,
                onEdit = onEditWorkingItem,
                onPromote = onPromoteItem
            )
            2 -> MemoryListTab(
                items = longTermMemory,
                onAdd = onAddLongTermItem,
                onRemove = onRemoveLongTermItem,
                onEdit = onEditLongTermItem,
                onPromote = null
            )
        }
    }
}

@Composable
private fun ShortTermTab(messages: List<ChatMessage>) {
    val s = LocalStrings.current
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            s.shortTermReadOnly,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(4.dp)
        )
        if (messages.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.noMemoryItems, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(messages) { msg ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(2.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (msg.role == "user")
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            Text(
                                msg.role,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                msg.content.take(200),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryListTab(
    items: List<MemoryItem>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    onPromote: ((String) -> Unit)?
) {
    val s = LocalStrings.current
    var newItemText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        // Add input
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newItemText,
                onValueChange = { newItemText = it },
                placeholder = { Text(s.addMemoryPlaceholder, style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.width(4.dp))
            TextButton(
                onClick = {
                    if (newItemText.isNotBlank()) {
                        onAdd(newItemText.trim())
                        newItemText = ""
                    }
                },
                enabled = newItemText.isNotBlank()
            ) {
                Text("+")
            }
        }

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.noMemoryItems, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { item ->
                    MemoryItemRow(item, onRemove, onEdit, onPromote)
                }
            }
        }
    }
}

@Composable
private fun MemoryItemRow(
    item: MemoryItem,
    onRemove: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    onPromote: ((String) -> Unit)?
) {
    val s = LocalStrings.current
    var isEditing by remember { mutableStateOf(false) }
    var editText by remember(item.content) { mutableStateOf(item.content) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            // Source badge
            Text(
                when (item.source) {
                    MemorySource.AUTO_EXTRACTED -> s.memorySourceAuto
                    MemorySource.MANUAL -> s.memorySourceManual
                    MemorySource.PROMOTED -> s.memorySourcePromoted
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )

            if (isEditing) {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                    maxLines = 3
                )
                Row {
                    TextButton(onClick = {
                        onEdit(item.id, editText.trim())
                        isEditing = false
                    }) { Text(s.save, style = MaterialTheme.typography.labelSmall) }
                    TextButton(onClick = { isEditing = false; editText = item.content }) {
                        Text(s.cancel, style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else {
                Text(item.content, style = MaterialTheme.typography.bodySmall)
                Row {
                    IconButton(onClick = { isEditing = true }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                    IconButton(onClick = { onRemove(item.id) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                    if (onPromote != null) {
                        IconButton(onClick = { onPromote(item.id) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = s.promoteToLongTerm, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

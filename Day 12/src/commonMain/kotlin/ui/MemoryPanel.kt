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
import androidx.compose.ui.unit.sp
import i18n.LocalStrings
import state.MemoryItem
import state.MemorySource

@Composable
fun MemoryPanel(
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
    var selectedTab by remember { mutableStateOf(0) } // default to Session tab

    Column(modifier = modifier.padding(4.dp)) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    Text(s.sessionMemoryTab, style = MaterialTheme.typography.labelSmall)
                    Text(
                        s.sessionMemoryScopeLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    Text(s.globalMemoryTab, style = MaterialTheme.typography.labelSmall)
                    Text(
                        s.globalMemoryScopeLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        // Tab content
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> MemoryListTab(
                    items = workingMemory,
                    onAdd = onAddWorkingItem,
                    onRemove = onRemoveWorkingItem,
                    onEdit = onEditWorkingItem,
                    onPromote = onPromoteItem
                )
                1 -> MemoryListTab(
                    items = longTermMemory,
                    onAdd = onAddLongTermItem,
                    onRemove = onRemoveLongTermItem,
                    onEdit = onEditLongTermItem,
                    onPromote = null
                )
            }
        }

        // Token estimate footer
        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
        val allItems = workingMemory + longTermMemory
        val tokenEstimate = allItems.sumOf { it.content.length } / 4
        Text(
            s.memoryTokenEstimate(tokenEstimate),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
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
                    IconButton(onClick = { isEditing = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { onRemove(item.id) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    if (onPromote != null) {
                        IconButton(onClick = { onPromote(item.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = s.moveToGlobal, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

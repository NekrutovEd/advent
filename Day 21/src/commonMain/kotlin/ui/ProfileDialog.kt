package ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import i18n.LocalStrings
import state.UserProfile

@Composable
fun ProfileDialog(
    profiles: List<UserProfile>,
    activeProfileId: String?,
    onAddProfile: () -> Unit,
    onRemoveProfile: (String) -> Unit,
    onSelectProfile: (String?) -> Unit,
    onRenameProfile: (String, String) -> Unit,
    onAddProfileItem: (String, String) -> Unit,
    onRemoveProfileItem: (String, Int) -> Unit,
    onUpdateProfileItem: (String, Int, String) -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalStrings.current
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(s.cancel)
            }
        },
        title = { Text(s.profileDialogTitle) },
        text = {
            Column(modifier = Modifier.width(450.dp)) {
                // Profile chips row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    profiles.forEach { profile ->
                        FilterChip(
                            selected = profile.id == activeProfileId,
                            onClick = { onSelectProfile(profile.id) },
                            label = {
                                Text(
                                    profile.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        )
                    }
                    IconButton(
                        onClick = onAddProfile,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = s.addProfile, modifier = Modifier.size(18.dp))
                    }
                }

                val activeProfile = profiles.firstOrNull { it.id == activeProfileId }
                if (activeProfile != null) {
                    Spacer(Modifier.height(12.dp))

                    // Profile name field
                    OutlinedTextField(
                        value = activeProfile.name,
                        onValueChange = { onRenameProfile(activeProfile.id, it) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        label = { Text(s.profileSectionTitle) }
                    )

                    Spacer(Modifier.height(8.dp))

                    // Preferences header + delete profile button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            s.profileItemsHeader,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { showDeleteConfirm = activeProfile.id },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = s.deleteProfileConfirmTitle,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    // Add preference input
                    var newItemText by remember { mutableStateOf("") }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newItemText,
                            onValueChange = { newItemText = it },
                            placeholder = { Text(s.addProfileItemPlaceholder, style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                if (newItemText.isNotBlank()) {
                                    onAddProfileItem(activeProfile.id, newItemText.trim())
                                    newItemText = ""
                                }
                            },
                            enabled = newItemText.isNotBlank(),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // Preferences list
                    if (activeProfile.items.isEmpty()) {
                        Text(
                            s.noProfileItems,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(4.dp)
                        )
                    } else {
                        Box(modifier = Modifier.heightIn(max = 300.dp)) {
                            LazyColumn {
                                itemsIndexed(activeProfile.items) { index, item ->
                                    DialogProfileItemRow(
                                        text = item,
                                        onRemove = { onRemoveProfileItem(activeProfile.id, index) },
                                        onUpdate = { onUpdateProfileItem(activeProfile.id, index, it) }
                                    )
                                }
                            }
                        }
                    }
                } else if (profiles.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        s.noProfileSelected,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    )

    // Delete confirmation dialog
    if (showDeleteConfirm != null) {
        val profileToDelete = profiles.firstOrNull { it.id == showDeleteConfirm }
        if (profileToDelete != null) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = null },
                title = { Text(s.deleteProfileConfirmTitle) },
                text = { Text(s.deleteProfileConfirmBody(profileToDelete.name)) },
                confirmButton = {
                    TextButton(onClick = {
                        onRemoveProfile(profileToDelete.id)
                        showDeleteConfirm = null
                    }) {
                        Text(s.confirm, color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = null }) {
                        Text(s.cancel)
                    }
                }
            )
        }
    }
}

@Composable
private fun DialogProfileItemRow(
    text: String,
    onRemove: () -> Unit,
    onUpdate: (String) -> Unit
) {
    val s = LocalStrings.current
    var isEditing by remember { mutableStateOf(false) }
    var editText by remember(text) { mutableStateOf(text) }

    if (isEditing) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = editText,
                onValueChange = { editText = it },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = {
                onUpdate(editText.trim())
                isEditing = false
            }) { Text(s.save, style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = { isEditing = false; editText = text }) {
                Text(s.cancel, style = MaterialTheme.typography.labelSmall)
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "\u2022 $text",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = { isEditing = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

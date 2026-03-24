package ui

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import i18n.LocalStrings
import state.SessionState
import storage.ArchivedSessionDto

@Composable
fun SessionTabBar(
    sessions: List<SessionState>,
    archivedSessions: List<ArchivedSessionDto>,
    activeIndex: Int,
    onSelectSession: (Int) -> Unit,
    onAddSession: () -> Unit,
    onDeleteSession: (Int) -> Unit,
    onRenameSession: (Int, String) -> Unit,
    onRestoreFromArchive: (ArchivedSessionDto) -> Unit,
    onDeleteFromArchive: (ArchivedSessionDto) -> Unit,
    onClearArchive: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    var archiveExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Archive dropdown (only shown when archive is non-empty)
        if (archivedSessions.isNotEmpty()) {
            Box {
                TextButton(onClick = { archiveExpanded = true }) {
                    Text(s.archiveLabel, style = MaterialTheme.typography.bodySmall)
                }
                DropdownMenu(
                    expanded = archiveExpanded,
                    onDismissRequest = { archiveExpanded = false }
                ) {
                    archivedSessions.forEach { dto ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(dto.name, modifier = Modifier.weight(1f))
                                    IconButton(
                                        onClick = { onDeleteFromArchive(dto) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Text("✕", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            },
                            onClick = {
                                onRestoreFromArchive(dto)
                                archiveExpanded = false
                            }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = s.clearArchive,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(s.clearArchive, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = {
                            onClearArchive()
                            archiveExpanded = false
                        }
                    )
                }
            }
        }

        // Horizontally scrollable session tabs
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            sessions.forEachIndexed { index, session ->
                SessionTab(
                    name = session.name,
                    isActive = index == activeIndex,
                    onClick = { onSelectSession(index) },
                    onDelete = { onDeleteSession(index) },
                    onRename = { newName -> onRenameSession(index, newName) }
                )
            }
        }

        // Add session button — always visible outside scroll
        TextButton(onClick = onAddSession) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SessionTab(
    name: String,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit
) {
    var isEditing by remember(name) { mutableStateOf(false) }
    var editText by remember(name) { mutableStateOf(name) }
    val focusRequester = remember { FocusRequester() }
    // Tracks whether the field actually received focus at least once this edit session.
    // Prevents the initial isFocused=false event (before focus is requested) from triggering commit.
    var hasFocused by remember { mutableStateOf(false) }

    val colors = MaterialTheme.colorScheme
    val activeColor = colors.primary
    val inactiveColor = colors.onSurface.copy(alpha = 0.45f)
    val editingColor = colors.onSurface

    // Request focus when editing starts; reset hasFocused guard.
    // The delay lets the BasicTextField finish attaching to the focus tree before
    // we steal focus from whatever currently holds it.
    LaunchedEffect(isEditing) {
        if (isEditing) {
            hasFocused = false
            kotlinx.coroutines.delay(50)
            if (isEditing) runCatching { focusRequester.requestFocus() }
        }
    }

    fun commit() {
        val committed = editText.trim().ifBlank { name }
        onRename(committed)
        editText = committed
        isEditing = false
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 2.dp)
    ) {
        if (isEditing) {
            BasicTextField(
                value = editText,
                onValueChange = { editText = it },
                singleLine = true,
                cursorBrush = SolidColor(colors.onSurface),
                textStyle = MaterialTheme.typography.bodySmall.copy(color = editingColor),
                modifier = Modifier
                    .widthIn(min = 64.dp, max = 180.dp)
                    .border(1.dp, colors.outline, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            hasFocused = true
                        } else if (hasFocused && isEditing) {
                            commit()
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        when {
                            event.type == KeyEventType.KeyDown && event.key == Key.Enter -> {
                                commit(); true
                            }
                            event.type == KeyEventType.KeyDown && event.key == Key.Escape -> {
                                editText = name; isEditing = false; true
                            }
                            else -> false
                        }
                    }
            )
        } else {
            Text(
                text = name,
                color = if (isActive) activeColor else inactiveColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onClick() },
                            onLongPress = {
                                editText = name
                                hasFocused = false  // must reset before isEditing=true so onFocusChanged(false) doesn't trigger commit
                                isEditing = true
                            }
                        )
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(20.dp)
        ) {
            Text(
                "✕",
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) activeColor else inactiveColor
            )
        }
    }
}

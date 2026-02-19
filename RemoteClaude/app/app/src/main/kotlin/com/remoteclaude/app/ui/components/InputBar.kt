package com.remoteclaude.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TerminalControlBar(
    onRawInput: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface {
        Row(
            modifier = modifier
                .padding(8.dp)
                .imePadding(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left group: Esc, Ctrl+C
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedIconButton(
                    onClick = { onRawInput("\u001b") },
                    modifier = Modifier.size(48.dp),
                ) {
                    Text("Esc", fontSize = 12.sp)
                }

                OutlinedIconButton(
                    onClick = { onRawInput("\u0003") },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Ctrl+C")
                }
            }

            // Right group: Up, Down, Enter
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedIconButton(
                    onClick = { onRawInput("\u001b[A") },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Up")
                }

                OutlinedIconButton(
                    onClick = { onRawInput("\u001b[B") },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Down")
                }

                OutlinedIconButton(
                    onClick = { onRawInput("\r") },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Enter")
                }
            }
        }
    }
}

package ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp

@Composable
fun PromptBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f).onKeyEvent { event ->
                if (event.key == Key.Enter && event.type == KeyEventType.KeyDown
                    && !event.isShiftPressed
                ) {
                    if (text.isNotBlank() && enabled) onSend()
                    true
                } else false
            },
            placeholder = { Text("Enter your message...") },
            singleLine = true,
            enabled = enabled,
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { onTextChange("") }) {
                        Text("\u2715")
                    }
                }
            }
        )

        Spacer(Modifier.width(8.dp))

        Button(
            onClick = onSend,
            enabled = enabled && text.isNotBlank()
        ) {
            Text("Send All")
        }

        Spacer(Modifier.width(8.dp))

        OutlinedButton(onClick = onClearAll) {
            Text("Clear All")
        }
    }
}

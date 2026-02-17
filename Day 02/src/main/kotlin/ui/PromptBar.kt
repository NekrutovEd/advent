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
    onSend: (String) -> Unit,
    enabled: Boolean,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }

    fun doSend() {
        if (text.isNotBlank() && enabled) {
            onSend(text)
            text = ""
        }
    }

    Row(
        modifier = modifier.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f).onKeyEvent { event ->
                if (event.key == Key.Enter && event.type == KeyEventType.KeyDown
                    && !event.isShiftPressed
                ) {
                    doSend()
                    true
                } else false
            },
            placeholder = { Text("Enter your message...") },
            singleLine = true,
            enabled = enabled
        )

        Spacer(Modifier.width(8.dp))

        Button(
            onClick = { doSend() },
            enabled = enabled && text.isNotBlank()
        ) {
            Text("Send")
        }

        Spacer(Modifier.width(8.dp))

        OutlinedButton(onClick = onClearAll) {
            Text("Clear All")
        }
    }
}

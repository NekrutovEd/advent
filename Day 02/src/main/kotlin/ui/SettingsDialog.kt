package ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import state.SettingsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    settings: SettingsState,
    onDismiss: () -> Unit
) {
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var model by remember { mutableStateOf(settings.model) }
    var temperature by remember { mutableStateOf(settings.temperature) }
    var maxTokens by remember { mutableStateOf(settings.maxTokens) }
    var connectTimeout by remember { mutableStateOf(settings.connectTimeout) }
    var readTimeout by remember { mutableStateOf(settings.readTimeout) }
    var modelExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.width(350.dp)
            ) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                ExposedDropdownMenuBox(
                    expanded = modelExpanded,
                    onExpandedChange = { modelExpanded = it }
                ) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false }
                    ) {
                        settings.availableModels.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m) },
                                onClick = {
                                    model = m
                                    modelExpanded = false
                                }
                            )
                        }
                    }
                }

                Column {
                    Text(
                        "Temperature: ${"%.1f".format(temperature)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0f..2f,
                        steps = 19
                    )
                }

                OutlinedTextField(
                    value = maxTokens,
                    onValueChange = { newValue ->
                        if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                            maxTokens = newValue
                        }
                    },
                    label = { Text("Max Tokens (empty = default)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = connectTimeout,
                        onValueChange = { newValue ->
                            if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                                connectTimeout = newValue
                            }
                        },
                        label = { Text("Connect Timeout (sec)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = readTimeout,
                        onValueChange = { newValue ->
                            if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                                readTimeout = newValue
                            }
                        },
                        label = { Text("Read Timeout (sec)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                settings.apiKey = apiKey
                settings.model = model
                settings.temperature = temperature
                settings.maxTokens = maxTokens
                settings.connectTimeout = connectTimeout
                settings.readTimeout = readTimeout
                onDismiss()
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

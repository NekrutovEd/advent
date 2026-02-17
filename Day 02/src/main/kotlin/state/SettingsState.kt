package state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class SettingsState {
    var apiKey by mutableStateOf(System.getenv("OPENAI_API_KEY") ?: "")
    var model by mutableStateOf("gpt-4o")
    var temperature by mutableStateOf(1.0f)
    var maxTokens by mutableStateOf("")

    val availableModels = listOf("gpt-4o", "gpt-4o-mini", "gpt-3.5-turbo")

    fun maxTokensOrNull(): Int? = maxTokens.toIntOrNull()
}

package state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import i18n.Lang

class SettingsState {
    var lang by mutableStateOf(Lang.EN)
    var apiKey by mutableStateOf(System.getenv("OPENAI_API_KEY") ?: "")
    var model by mutableStateOf("gpt-4o")
    var temperature by mutableStateOf(1.0f)
    var maxTokens by mutableStateOf("")
    var connectTimeout by mutableStateOf("15")
    var readTimeout by mutableStateOf("60")
    var systemPrompt by mutableStateOf("")

    val availableModels = listOf("gpt-4o", "gpt-4o-mini", "gpt-3.5-turbo")

    fun maxTokensOrNull(): Int? = maxTokens.toIntOrNull()
    fun connectTimeoutSec(): Int = connectTimeout.toIntOrNull() ?: 15
    fun readTimeoutSec(): Int = readTimeout.toIntOrNull() ?: 60
}

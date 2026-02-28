package state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class ApiConfig(
    val id: String,
    val name: String,
    val availableModels: List<String>,
    var baseUrl: String = "https://api.openai.com",
    defaultApiKey: String = "",
    defaultTemperature: Float = 1.0f,
    defaultMaxTokens: String = "",
    defaultConnectTimeout: String = "15",
    defaultReadTimeout: String = "60"
) {
    var apiKey by mutableStateOf(defaultApiKey)
    var temperature by mutableStateOf(defaultTemperature)
    var maxTokens by mutableStateOf(defaultMaxTokens)
    var connectTimeout by mutableStateOf(defaultConnectTimeout)
    var readTimeout by mutableStateOf(defaultReadTimeout)

    fun maxTokensOrNull(): Int? = maxTokens.toIntOrNull()
    fun connectTimeoutSec(): Int = connectTimeout.toIntOrNull() ?: 15
    fun readTimeoutSec(): Int = readTimeout.toIntOrNull() ?: 60
}

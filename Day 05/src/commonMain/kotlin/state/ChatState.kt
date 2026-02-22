package state

import api.ChatApiInterface
import api.ChatMessage
import api.TokenUsage
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class ChatState(
    private val chatApi: ChatApiInterface
) {
    val messages = mutableStateListOf<ChatMessage>()
    var isLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var constraints by mutableStateOf("")
    var systemPrompt by mutableStateOf("")
    var visibleOptions by mutableStateOf(emptySet<ChatOption>())

    val stopWords = mutableStateListOf("")
    var maxTokensOverride by mutableStateOf("")
    var temperatureOverride by mutableStateOf<Float?>(null)
    var modelOverride by mutableStateOf<String?>(null)
    var responseFormatType by mutableStateOf("text")
    var jsonSchema by mutableStateOf("")
    var lastUsage by mutableStateOf<TokenUsage?>(null)
    var totalPromptTokens by mutableStateOf(0)
    var totalCompletionTokens by mutableStateOf(0)
    var totalTokens by mutableStateOf(0)

    private val history = mutableListOf<ChatMessage>()

    fun toggleOption(option: ChatOption) {
        if (option in visibleOptions) {
            visibleOptions = visibleOptions - option
            resetOption(option)
        } else {
            visibleOptions = visibleOptions + option
        }
    }

    fun resetOption(option: ChatOption) {
        when (option) {
            ChatOption.SYSTEM_PROMPT -> systemPrompt = ""
            ChatOption.CONSTRAINTS -> constraints = ""
            ChatOption.STOP_WORDS -> {
                stopWords.clear()
                stopWords.add("")
            }
            ChatOption.MAX_TOKENS -> maxTokensOverride = ""
            ChatOption.MODEL -> modelOverride = null
            ChatOption.TEMPERATURE -> temperatureOverride = null
            ChatOption.STATISTICS -> {
                lastUsage = null
                totalPromptTokens = 0
                totalCompletionTokens = 0
                totalTokens = 0
            }
            ChatOption.RESPONSE_FORMAT -> {
                responseFormatType = "text"
                jsonSchema = ""
            }
        }
    }

    fun addStopWord() {
        if (stopWords.size < 4) {
            stopWords.add("")
        }
    }

    fun removeStopWord() {
        if (stopWords.size > 1) {
            stopWords.removeAt(stopWords.size - 1)
        }
    }

    fun maxTokensOverrideOrNull(): Int? = maxTokensOverride.toIntOrNull()

    suspend fun sendMessage(
        userContent: String,
        apiKey: String,
        model: String,
        temperature: Double?,
        maxTokens: Int?,
        systemPrompt: String? = null,
        connectTimeoutSec: Int? = null,
        readTimeoutSec: Int? = null,
        stop: List<String>? = null,
        responseFormat: String? = null,
        jsonSchema: String? = null
    ) {
        isLoading = true
        error = null

        val userMessage = ChatMessage("user", userContent)
        messages.add(userMessage)
        history.add(userMessage)

        try {
            val response = chatApi.sendMessage(
                history = history,
                apiKey = apiKey,
                model = model,
                temperature = temperature,
                maxTokens = maxTokens,
                systemPrompt = systemPrompt,
                connectTimeoutSec = connectTimeoutSec,
                readTimeoutSec = readTimeoutSec,
                stop = stop,
                responseFormat = responseFormat,
                jsonSchema = jsonSchema
            )
            val assistantMessage = ChatMessage("assistant", response.content)
            history.add(assistantMessage)
            messages.add(assistantMessage)

            if (response.usage != null) {
                lastUsage = response.usage
                totalPromptTokens += response.usage.promptTokens
                totalCompletionTokens += response.usage.completionTokens
                totalTokens += response.usage.totalTokens
            }
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
            if (history.isNotEmpty()) history.removeLast()
            messages.removeLastOrNull()
        } finally {
            isLoading = false
        }
    }

    fun clear() {
        messages.clear()
        history.clear()
        error = null
        isLoading = false
        lastUsage = null
        totalPromptTokens = 0
        totalCompletionTokens = 0
        totalTokens = 0
    }
}

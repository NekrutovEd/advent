package state

import api.ChatApi
import api.ChatMessage
import api.TokenUsage
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

class ChatState(
    private val chatApi: ChatApi = ChatApi(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    val messages = mutableStateListOf<ChatMessage>()
    var isLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var constraints by mutableStateOf("")
    var systemPrompt by mutableStateOf("")
    var visibleOptions by mutableStateOf(emptySet<ChatOption>())

    val stopWords = mutableStateListOf("")
    var maxTokensOverride by mutableStateOf("")
    var responseFormatType by mutableStateOf("text")
    var jsonSchema by mutableStateOf("")
    var lastUsage by mutableStateOf<TokenUsage?>(null)
    var totalPromptTokens by mutableStateOf(0)
    var totalCompletionTokens by mutableStateOf(0)
    var totalTokens by mutableStateOf(0)

    private val history = JSONArray()

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

        messages.add(ChatMessage("user", userContent))
        ChatApi.addMessage(history, "user", userContent)

        try {
            val requestBody = ChatApi.buildRequestBody(
                history, model, temperature, maxTokens, systemPrompt,
                stop, responseFormat, jsonSchema
            )
            val responseBody = withContext(ioDispatcher) {
                chatApi.sendMessage(apiKey, requestBody, connectTimeoutSec, readTimeoutSec)
            }
            val content = ChatApi.parseResponseContent(responseBody)
            ChatApi.addMessage(history, "assistant", content)
            messages.add(ChatMessage("assistant", content))

            val usage = ChatApi.parseUsage(responseBody)
            if (usage != null) {
                lastUsage = usage
                totalPromptTokens += usage.promptTokens
                totalCompletionTokens += usage.completionTokens
                totalTokens += usage.totalTokens
            }
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
            // Remove the user message from history on failure so it can be retried
            if (history.length() > 0) {
                history.remove(history.length() - 1)
            }
            messages.removeLastOrNull()
        } finally {
            isLoading = false
        }
    }

    fun clear() {
        messages.clear()
        while (history.length() > 0) {
            history.remove(history.length() - 1)
        }
        error = null
        isLoading = false
        lastUsage = null
        totalPromptTokens = 0
        totalCompletionTokens = 0
        totalTokens = 0
    }
}

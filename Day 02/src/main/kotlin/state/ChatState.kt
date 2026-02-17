package state

import api.ChatApi
import api.ChatMessage
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

    private val history = JSONArray()

    suspend fun sendMessage(
        userContent: String,
        apiKey: String,
        model: String,
        temperature: Double?,
        maxTokens: Int?,
        systemPrompt: String? = null,
        connectTimeoutSec: Int? = null,
        readTimeoutSec: Int? = null
    ) {
        isLoading = true
        error = null

        messages.add(ChatMessage("user", userContent))
        ChatApi.addMessage(history, "user", userContent)

        try {
            val requestBody = ChatApi.buildRequestBody(history, model, temperature, maxTokens, systemPrompt)
            val responseBody = withContext(ioDispatcher) {
                chatApi.sendMessage(apiKey, requestBody, connectTimeoutSec, readTimeoutSec)
            }
            val content = ChatApi.parseResponseContent(responseBody)
            ChatApi.addMessage(history, "assistant", content)
            messages.add(ChatMessage("assistant", content))
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
    }
}

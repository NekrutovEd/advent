package state

import api.ChatApiInterface
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppState(
    private val chatApi: ChatApiInterface
) {
    val settings = SettingsState()
    val chats = mutableStateListOf<ChatState>()

    var showSettings by mutableStateOf(false)

    init {
        chats.add(ChatState(chatApi))
    }

    fun addChat() {
        chats.add(ChatState(chatApi))
    }

    fun removeChat(index: Int) {
        if (index > 0 && index < chats.size) {
            chats.removeAt(index)
        }
    }

    fun clearAll() {
        chats.forEach { it.clear() }
    }

    val isBusy: Boolean get() = chats.any { it.isLoading }

    fun sendToAll(prompt: String, scope: CoroutineScope): List<Job> {
        if (prompt.isBlank() || settings.apiKey.isBlank()) return emptyList()

        val temperature = settings.temperature.toDouble()
        val maxTokens = settings.maxTokensOrNull()
        val model = settings.model
        val apiKey = settings.apiKey
        val connectTimeoutSec = settings.connectTimeoutSec()
        val readTimeoutSec = settings.readTimeoutSec()
        val globalSystemPrompt = settings.systemPrompt

        val supervisorScope = CoroutineScope(scope.coroutineContext + SupervisorJob())

        return chats.map { chat ->
            val chatPrompt = applyConstraints(prompt, chat.constraints)
            val combinedSystemPrompt = combineSystemPrompts(globalSystemPrompt, chat.systemPrompt)
            val stop = chat.stopWords.filter { it.isNotBlank() }.ifEmpty { null }
            val effectiveMaxTokens = chat.maxTokensOverrideOrNull() ?: maxTokens
            val responseFormat = chat.responseFormatType
            val jsonSchema = chat.jsonSchema
            supervisorScope.launch {
                chat.sendMessage(
                    chatPrompt, apiKey, model, temperature, effectiveMaxTokens,
                    combinedSystemPrompt, connectTimeoutSec, readTimeoutSec,
                    stop, responseFormat, jsonSchema
                )
            }
        }
    }

    fun sendToOne(chat: ChatState, prompt: String, scope: CoroutineScope): Job? {
        if (prompt.isBlank() || settings.apiKey.isBlank()) return null

        val temperature = settings.temperature.toDouble()
        val maxTokens = settings.maxTokensOrNull()
        val model = settings.model
        val apiKey = settings.apiKey
        val connectTimeoutSec = settings.connectTimeoutSec()
        val readTimeoutSec = settings.readTimeoutSec()
        val globalSystemPrompt = settings.systemPrompt

        val chatPrompt = applyConstraints(prompt, chat.constraints)
        val combinedSystemPrompt = combineSystemPrompts(globalSystemPrompt, chat.systemPrompt)
        val stop = chat.stopWords.filter { it.isNotBlank() }.ifEmpty { null }
        val effectiveMaxTokens = chat.maxTokensOverrideOrNull() ?: maxTokens
        val responseFormat = chat.responseFormatType
        val jsonSchema = chat.jsonSchema

        return scope.launch {
            chat.sendMessage(
                chatPrompt, apiKey, model, temperature, effectiveMaxTokens,
                combinedSystemPrompt, connectTimeoutSec, readTimeoutSec,
                stop, responseFormat, jsonSchema
            )
        }
    }

    companion object {
        fun applyConstraints(prompt: String, constraints: String): String {
            return if (constraints.isBlank()) prompt else "$prompt\n\n$constraints"
        }

        fun combineSystemPrompts(global: String, perChat: String): String? {
            val g = global.trim()
            val p = perChat.trim()
            return when {
                g.isEmpty() && p.isEmpty() -> null
                g.isEmpty() -> p
                p.isEmpty() -> g
                else -> "$g\n\n$p"
            }
        }
    }
}

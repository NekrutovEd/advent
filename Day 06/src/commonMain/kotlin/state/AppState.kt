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
        chats.add(newChat())
    }

    fun addChat() {
        chats.add(newChat())
    }

    private fun newChat() = ChatState(
        chatApi,
        defaultSendHistory = settings.defaultSendHistory,
        defaultAutoSummarize = settings.defaultAutoSummarize,
        defaultSummarizeThreshold = settings.defaultSummarizeThreshold,
        defaultKeepLastMessages = settings.defaultKeepLastMessages
    )

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
        if (prompt.isBlank()) return emptyList()

        val globalModel = settings.selectedModel
        val globalSystemPrompt = settings.systemPrompt
        val supervisorScope = CoroutineScope(scope.coroutineContext + SupervisorJob())

        return chats.mapNotNull { chat ->
            val model = chat.modelOverride ?: globalModel
            val apiConfig = settings.configForModel(model) ?: return@mapNotNull null
            if (apiConfig.apiKey.isBlank()) return@mapNotNull null

            val chatPrompt = applyConstraints(prompt, chat.constraints)
            val combinedSystemPrompt = combineSystemPrompts(globalSystemPrompt, chat.systemPrompt)
            val stop = chat.stopWords.filter { it.isNotBlank() }.ifEmpty { null }
            val effectiveMaxTokens = chat.maxTokensOverrideOrNull() ?: apiConfig.maxTokensOrNull()
            val effectiveTemperature = chat.temperatureOverride?.toDouble() ?: apiConfig.temperature.toDouble()
            val responseFormat = chat.responseFormatType
            val jsonSchema = chat.jsonSchema

            supervisorScope.launch {
                chat.sendMessage(
                    chatPrompt, apiConfig.apiKey, model, effectiveTemperature, effectiveMaxTokens,
                    combinedSystemPrompt, apiConfig.connectTimeoutSec(), apiConfig.readTimeoutSec(),
                    stop, responseFormat, jsonSchema
                )
            }
        }
    }

    fun sendToOne(chat: ChatState, prompt: String, scope: CoroutineScope): Job? {
        if (prompt.isBlank()) return null

        val model = chat.modelOverride ?: settings.selectedModel
        val apiConfig = settings.configForModel(model) ?: return null
        if (apiConfig.apiKey.isBlank()) return null

        val globalSystemPrompt = settings.systemPrompt
        val chatPrompt = applyConstraints(prompt, chat.constraints)
        val combinedSystemPrompt = combineSystemPrompts(globalSystemPrompt, chat.systemPrompt)
        val stop = chat.stopWords.filter { it.isNotBlank() }.ifEmpty { null }
        val effectiveMaxTokens = chat.maxTokensOverrideOrNull() ?: apiConfig.maxTokensOrNull()
        val effectiveTemperature = chat.temperatureOverride?.toDouble() ?: apiConfig.temperature.toDouble()
        val responseFormat = chat.responseFormatType
        val jsonSchema = chat.jsonSchema

        return scope.launch {
            chat.sendMessage(
                chatPrompt, apiConfig.apiKey, model, effectiveTemperature, effectiveMaxTokens,
                combinedSystemPrompt, apiConfig.connectTimeoutSec(), apiConfig.readTimeoutSec(),
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

package state

import api.ChatApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppState(
    chatApi: ChatApi = ChatApi(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    val settings = SettingsState()
    val chat1 = ChatState(chatApi, ioDispatcher)
    val chat2 = ChatState(chatApi, ioDispatcher)

    var showSettings by mutableStateOf(false)

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

        val chats = listOf(chat1, chat2)
        return chats.map { chat ->
            val chatPrompt = applyConstraints(prompt, chat.constraints)
            val combinedSystemPrompt = combineSystemPrompts(globalSystemPrompt, chat.systemPrompt)
            supervisorScope.launch {
                chat.sendMessage(
                    chatPrompt, apiKey, model, temperature, maxTokens,
                    combinedSystemPrompt, connectTimeoutSec, readTimeoutSec
                )
            }
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

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

    var constraints by mutableStateOf("")
    var showSettings by mutableStateOf(false)

    fun sendToAll(prompt: String, scope: CoroutineScope): List<Job> {
        if (prompt.isBlank() || settings.apiKey.isBlank()) return emptyList()

        val temperature = settings.temperature.toDouble()
        val maxTokens = settings.maxTokensOrNull()
        val model = settings.model
        val apiKey = settings.apiKey

        val supervisorScope = CoroutineScope(scope.coroutineContext + SupervisorJob())

        val job1 = supervisorScope.launch {
            chat1.sendMessage(prompt, apiKey, model, temperature, maxTokens)
        }

        val chat2Prompt = applyConstraints(prompt, constraints)
        val job2 = supervisorScope.launch {
            chat2.sendMessage(chat2Prompt, apiKey, model, temperature, maxTokens)
        }

        return listOf(job1, job2)
    }

    companion object {
        fun applyConstraints(prompt: String, constraints: String): String {
            return if (constraints.isBlank()) prompt else "$prompt\n\n$constraints"
        }
    }
}

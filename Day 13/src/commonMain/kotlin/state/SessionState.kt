package state

import api.ChatApiInterface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SessionState(
    internal val chatApi: ChatApiInterface,
    internal val settings: SettingsState,
    val id: String,
    name: String
) {
    var name by mutableStateOf(name)
    val chats = mutableStateListOf<ChatState>()
    val workingMemory = mutableStateListOf<MemoryItem>()

    val isBusy: Boolean get() = chats.any { it.isLoading }

    init {
        chats.add(createChat())
    }

    fun addChat() {
        chats.add(createChat())
    }

    fun removeChat(index: Int) {
        if (index >= 1 && index < chats.size) {
            chats.removeAt(index)
        }
    }

    fun addWorkingMemoryItem(content: String, source: MemorySource, timestamp: Long): MemoryItem {
        val item = MemoryItem(id = buildMemoryId(), content = content, source = source, timestamp = timestamp)
        workingMemory.add(item)
        return item
    }

    fun removeWorkingMemoryItem(itemId: String) {
        workingMemory.removeAll { it.id == itemId }
    }

    fun updateWorkingMemoryItem(itemId: String, newContent: String) {
        val index = workingMemory.indexOfFirst { it.id == itemId }
        if (index >= 0) {
            workingMemory[index] = workingMemory[index].copy(content = newContent)
        }
    }

    fun workingMemoryText(): String =
        workingMemory.joinToString("\n") { "- ${it.content}" }

    fun cloneChat(index: Int) {
        val source = chats.getOrNull(index) ?: return
        val clone = createChat()
        clone.messages.addAll(source.messages)
        clone.restoreHistory(source.historySnapshot())
        clone.constraints = source.constraints
        clone.systemPrompt = source.systemPrompt
        clone.modelOverride = source.modelOverride
        clone.maxTokensOverride = source.maxTokensOverride
        clone.temperatureOverride = source.temperatureOverride
        clone.responseFormatType = source.responseFormatType
        clone.jsonSchema = source.jsonSchema
        clone.sendHistory = source.sendHistory
        clone.autoSummarize = source.autoSummarize
        clone.summarizeThreshold = source.summarizeThreshold
        clone.keepLastMessages = source.keepLastMessages
        clone.summaryCount = source.summaryCount
        clone.slidingWindow = source.slidingWindow
        clone.extractMemory = source.extractMemory
        clone.taskTracking = source.taskTracking
        clone.taskTracker.phase = source.taskTracker.phase
        clone.taskTracker.isPaused = source.taskTracker.isPaused
        clone.taskTracker.steps.addAll(source.taskTracker.steps)
        clone.taskTracker.currentStepIndex = source.taskTracker.currentStepIndex
        clone.taskTracker.expectedAction = source.taskTracker.expectedAction
        clone.taskTracker.taskDescription = source.taskTracker.taskDescription
        clone.stopWords.clear()
        clone.stopWords.addAll(source.stopWords)
        clone.visibleOptions = source.visibleOptions
        clone.lastUsage = source.lastUsage
        clone.totalPromptTokens = source.totalPromptTokens
        clone.totalCompletionTokens = source.totalCompletionTokens
        clone.totalTokens = source.totalTokens
        chats.add(index + 1, clone)
    }

    fun clearAll() {
        chats.forEach { it.clear() }
    }

    fun sendToAll(
        prompt: String,
        scope: CoroutineScope,
        longTermMemoryText: String = "",
        profileText: String = "",
        timestamp: Long = 0L,
        onLongTermExtracted: ((List<String>) -> Unit)? = null
    ): List<Job> {
        if (prompt.isBlank()) return emptyList()

        val globalModel = settings.selectedModel
        val globalSystemPrompt = settings.systemPrompt
        val supervisorScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
        val wmText = workingMemoryText()

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
                    stop, responseFormat, jsonSchema, apiConfig.baseUrl,
                    workingMemoryText = wmText,
                    longTermMemoryText = longTermMemoryText,
                    profileText = profileText,
                    lang = settings.lang,
                    onMemoryExtracted = { result ->
                        result.workingItems.forEach { addWorkingMemoryItem(it, MemorySource.AUTO_EXTRACTED, timestamp) }
                        if (result.longTermItems.isNotEmpty()) {
                            onLongTermExtracted?.invoke(result.longTermItems)
                        }
                    }
                )
            }
        }
    }

    fun sendToOne(
        chat: ChatState,
        prompt: String,
        scope: CoroutineScope,
        longTermMemoryText: String = "",
        profileText: String = "",
        timestamp: Long = 0L,
        onLongTermExtracted: ((List<String>) -> Unit)? = null
    ): Job? {
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
        val wmText = workingMemoryText()

        return scope.launch {
            chat.sendMessage(
                chatPrompt, apiConfig.apiKey, model, effectiveTemperature, effectiveMaxTokens,
                combinedSystemPrompt, apiConfig.connectTimeoutSec(), apiConfig.readTimeoutSec(),
                stop, responseFormat, jsonSchema, apiConfig.baseUrl,
                workingMemoryText = wmText,
                longTermMemoryText = longTermMemoryText,
                profileText = profileText,
                lang = settings.lang,
                onMemoryExtracted = { result ->
                    result.workingItems.forEach { addWorkingMemoryItem(it, MemorySource.AUTO_EXTRACTED, timestamp) }
                    if (result.longTermItems.isNotEmpty()) {
                        onLongTermExtracted?.invoke(result.longTermItems)
                    }
                }
            )
        }
    }

    internal fun createChat(id: String? = null) = ChatState(
        chatApi = chatApi,
        defaultSendHistory = settings.defaultSendHistory,
        defaultAutoSummarize = settings.defaultAutoSummarize,
        defaultSummarizeThreshold = settings.defaultSummarizeThreshold,
        defaultKeepLastMessages = settings.defaultKeepLastMessages,
        defaultSlidingWindow = settings.defaultSlidingWindow,
        defaultExtractMemory = settings.defaultExtractMemory,
        defaultTaskTracking = settings.defaultTaskTracking,
        id = id
    )

    companion object {
        fun applyConstraints(prompt: String, constraints: String): String =
            if (constraints.isBlank()) prompt else "$prompt\n\n$constraints"

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

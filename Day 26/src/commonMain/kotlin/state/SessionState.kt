package state

import api.ChatApiInterface
import mcp.McpTool
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
        clone.taskTracker.taskDescription = source.taskTracker.taskDescription
        clone.ragEnabled = source.ragEnabled
        clone.ragMode = source.ragMode
        // Copy task memory
        clone.taskMemory.goal = source.taskMemory.goal
        clone.taskMemory.clarifications.addAll(source.taskMemory.clarifications)
        clone.taskMemory.constraints.addAll(source.taskMemory.constraints)
        clone.taskMemory.coveredTopics.addAll(source.taskMemory.coveredTopics)
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
        invariantsText: String = "",
        timestamp: Long = 0L,
        onLongTermExtracted: ((List<String>) -> Unit)? = null,
        mcpTools: List<McpTool>? = null,
        toolExecutor: (suspend (String, String) -> String)? = null,
        ragProvider: RagProvider? = null
    ): List<Job> {
        if (prompt.isBlank()) return emptyList()

        val globalModel = settings.selectedModel
        val globalSystemPrompt = settings.systemPrompt
        val supervisorScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
        val wmText = workingMemoryText()

        return chats.mapNotNull { chat ->
            val model = chat.modelOverride ?: globalModel
            val apiConfig = settings.configForModel(model) ?: return@mapNotNull null
            if (apiConfig.apiKey.isBlank() && apiConfig.requiresApiKey) return@mapNotNull null

            val chatPrompt = applyConstraints(prompt, chat.constraints)
            val combinedSystemPrompt = combineSystemPrompts(globalSystemPrompt, chat.systemPrompt)
            val stop = chat.stopWords.filter { it.isNotBlank() }.ifEmpty { null }
            val effectiveMaxTokens = chat.maxTokensOverrideOrNull() ?: apiConfig.maxTokensOrNull()
            val effectiveTemperature = chat.temperatureOverride?.toDouble() ?: apiConfig.temperature.toDouble()
            val responseFormat = chat.responseFormatType
            val jsonSchema = chat.jsonSchema

            supervisorScope.launch {
                // RAG: search for relevant context if enabled for this chat
                val ragContext = if (chat.ragEnabled && ragProvider != null && ragProvider.isReady) {
                    try {
                        // Day 25: conversation-aware query enrichment
                        val enrichedQuery = ConversationAwareQuery.build(
                            prompt, chat.taskMemory, chat.historySnapshot()
                        )
                        val ragResult = ragProvider.search(enrichedQuery, chat.ragMode)
                        // Store chunks for citation validation
                        chat.lastRagChunks = ragResult.chunks
                        if (ragResult.lowConfidence || ragResult.chunks.isEmpty()) {
                            chat.lastRagSources = "Low confidence — no relevant sources found\nMode: ${chat.ragMode.label}"
                        } else if (ragResult.chunks.isNotEmpty()) {
                            val pipelineInfo = if (ragResult.candidateCount > 0)
                                " [${ragResult.chunks.size}/${ragResult.candidateCount}]" else ""
                            chat.lastRagSources = ragResult.chunks.joinToString("\n") {
                                "${it.source} (${it.section}) — score: ${"%.2f".format(it.score)}"
                            } + "\nMode: ${chat.ragMode.label}$pipelineInfo"
                        }
                        ragProvider.buildContext(ragResult)
                    } catch (_: Exception) { "" }
                } else ""

                chat.sendMessage(
                    chatPrompt, apiConfig.apiKey, model, effectiveTemperature, effectiveMaxTokens,
                    combinedSystemPrompt, apiConfig.connectTimeoutSec(), apiConfig.readTimeoutSec(),
                    stop, responseFormat, jsonSchema, apiConfig.baseUrl,
                    workingMemoryText = wmText,
                    longTermMemoryText = longTermMemoryText,
                    profileText = profileText,
                    invariantsText = invariantsText,
                    lang = settings.lang,
                    onMemoryExtracted = { result ->
                        result.workingItems.forEach { addWorkingMemoryItem(it, MemorySource.AUTO_EXTRACTED, timestamp) }
                        if (result.longTermItems.isNotEmpty()) {
                            onLongTermExtracted?.invoke(result.longTermItems)
                        }
                    },
                    mcpTools = mcpTools,
                    toolExecutor = toolExecutor,
                    schedulerChatId = if (mcpTools != null) chat.id else "",
                    schedulerSessionId = if (mcpTools != null) id else "",
                    ragContextText = ragContext
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
        invariantsText: String = "",
        timestamp: Long = 0L,
        onLongTermExtracted: ((List<String>) -> Unit)? = null,
        mcpTools: List<McpTool>? = null,
        toolExecutor: (suspend (String, String) -> String)? = null,
        hideUserMessage: Boolean = false,
        ragProvider: RagProvider? = null
    ): Job? {
        if (prompt.isBlank()) return null

        val model = chat.modelOverride ?: settings.selectedModel
        val apiConfig = settings.configForModel(model) ?: return null
        if (apiConfig.apiKey.isBlank() && apiConfig.requiresApiKey) return null

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
            // RAG: search for relevant context if enabled for this chat
            val ragContext = if (chat.ragEnabled && ragProvider != null && ragProvider.isReady) {
                try {
                    // Day 25: conversation-aware query enrichment
                    val enrichedQuery = ConversationAwareQuery.build(
                        prompt, chat.taskMemory, chat.historySnapshot()
                    )
                    val ragResult = ragProvider.search(enrichedQuery, chat.ragMode)
                    // Store chunks for citation validation
                    chat.lastRagChunks = ragResult.chunks
                    if (ragResult.lowConfidence || ragResult.chunks.isEmpty()) {
                        chat.lastRagSources = "Low confidence — no relevant sources found\nMode: ${chat.ragMode.label}"
                    } else if (ragResult.chunks.isNotEmpty()) {
                        val pipelineInfo = if (ragResult.candidateCount > 0)
                            " [${ragResult.chunks.size}/${ragResult.candidateCount}]" else ""
                        chat.lastRagSources = ragResult.chunks.joinToString("\n") {
                            "${it.source} (${it.section}) — score: ${"%.2f".format(it.score)}"
                        } + "\nMode: ${chat.ragMode.label}$pipelineInfo"
                    }
                    ragProvider.buildContext(ragResult)
                } catch (_: Exception) { "" }
            } else ""

            chat.sendMessage(
                chatPrompt, apiConfig.apiKey, model, effectiveTemperature, effectiveMaxTokens,
                combinedSystemPrompt, apiConfig.connectTimeoutSec(), apiConfig.readTimeoutSec(),
                stop, responseFormat, jsonSchema, apiConfig.baseUrl,
                workingMemoryText = wmText,
                longTermMemoryText = longTermMemoryText,
                profileText = profileText,
                invariantsText = invariantsText,
                lang = settings.lang,
                onMemoryExtracted = { result ->
                    result.workingItems.forEach { addWorkingMemoryItem(it, MemorySource.AUTO_EXTRACTED, timestamp) }
                    if (result.longTermItems.isNotEmpty()) {
                        onLongTermExtracted?.invoke(result.longTermItems)
                    }
                },
                mcpTools = mcpTools,
                toolExecutor = toolExecutor,
                schedulerChatId = if (mcpTools != null) chat.id else "",
                schedulerSessionId = if (mcpTools != null) id else "",
                hideUserMessage = hideUserMessage,
                ragContextText = ragContext
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

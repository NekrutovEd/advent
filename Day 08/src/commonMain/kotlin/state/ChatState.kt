package state

import api.ChatApiInterface
import api.ChatMessage
import api.TokenUsage
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

class ChatState(
    private val chatApi: ChatApiInterface,
    defaultSendHistory: Boolean = true,
    defaultAutoSummarize: Boolean = true,
    defaultSummarizeThreshold: String = "10",
    defaultKeepLastMessages: String = "4",
    id: String? = null
) {
    val id: String = id ?: buildId()
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

    // History
    var sendHistory by mutableStateOf(defaultSendHistory)

    // Summarization
    var autoSummarize by mutableStateOf(defaultAutoSummarize)
    var summarizeThreshold by mutableStateOf(defaultSummarizeThreshold)
    var keepLastMessages by mutableStateOf(defaultKeepLastMessages)
    var isSummarizing by mutableStateOf(false)
    var summaryCount by mutableStateOf(0)

    private val history = mutableListOf<ChatMessage>()

    fun historySnapshot(): List<ChatMessage> = history.toList()

    fun restoreHistory(msgs: List<ChatMessage>) {
        history.clear()
        history.addAll(msgs)
    }

    private companion object {
        fun buildId(): String = Random.Default.nextBytes(4).joinToString("") {
            val v = it.toInt() and 0xFF
            val h = v.toString(16)
            if (h.length == 1) "0$h" else h
        }
    }

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
            ChatOption.HISTORY -> sendHistory = true
            ChatOption.SUMMARIZATION -> {
                autoSummarize = false
                summarizeThreshold = "10"
                keepLastMessages = "4"
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

    private suspend fun summarizeIfNeeded(
        apiKey: String,
        model: String,
        temperature: Double?,
        maxTokens: Int?,
        connectTimeoutSec: Int?,
        readTimeoutSec: Int?
    ) {
        if (!autoSummarize || !sendHistory) return
        val threshold = summarizeThreshold.toIntOrNull()?.coerceAtLeast(4) ?: return
        val keep = keepLastMessages.toIntOrNull()?.coerceAtLeast(2) ?: return

        // Count only conversational messages (user/assistant)
        val conversational = history.filter { it.role == "user" || it.role == "assistant" }
        if (conversational.size <= threshold) return

        val toSummarize = conversational.dropLast(keep)
        val toKeep = conversational.takeLast(keep)

        isSummarizing = true
        try {
            // Build the summarization request: existing summaries as context + messages to compress
            val existingSummaries = history.filter { it.role == "system" && it.content.startsWith("[Summary") }
            val summaryRequest = buildList {
                addAll(existingSummaries)
                addAll(toSummarize)
                add(ChatMessage("user", "Summarize the conversation above concisely. Capture key topics, facts, decisions, and context needed to continue the conversation."))
            }

            val response = chatApi.sendMessage(
                history = summaryRequest,
                apiKey = apiKey,
                model = model,
                temperature = temperature,
                maxTokens = maxTokens,
                systemPrompt = "You are a helpful assistant that creates concise conversation summaries.",
                connectTimeoutSec = connectTimeoutSec,
                readTimeoutSec = readTimeoutSec,
                stop = null,
                responseFormat = null,
                jsonSchema = null
            )

            summaryCount++
            val summaryMsg = ChatMessage("system", "[Summary #$summaryCount]\n${response.content}")

            // Rebuild history: existing summaries + new summary + tail
            history.clear()
            history.addAll(existingSummaries)
            history.add(summaryMsg)
            history.addAll(toKeep)

            // Update UI messages: replace summarized user/assistant messages with a summary bubble
            val nonSummaryIndices = messages.indices.filter {
                messages[it].role == "user" || messages[it].role == "assistant"
            }
            val indicesToReplace = nonSummaryIndices.take(toSummarize.size)
            if (indicesToReplace.isNotEmpty()) {
                indicesToReplace.reversed().forEach { messages.removeAt(it) }
                messages.add(indicesToReplace.first(), ChatMessage("summary", response.content))
            }
        } finally {
            isSummarizing = false
        }
    }

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

        val summaryCountBefore = summaryCount
        summarizeIfNeeded(apiKey, model, temperature, maxTokens, connectTimeoutSec, readTimeoutSec)
        val freshSummarization = summaryCount > summaryCountBefore

        val snapshotHistory = if (sendHistory) history.toList() else emptyList()
        val snapshot = try {
            chatApi.buildSnapshot(
                history = snapshotHistory,
                model = model,
                temperature = temperature,
                maxTokens = maxTokens,
                systemPrompt = systemPrompt,
                stop = stop,
                responseFormat = responseFormat,
                jsonSchema = jsonSchema,
                userContent = userContent,
                freshSummarization = freshSummarization
            )
        } catch (_: Exception) { null }

        val userMessage = ChatMessage("user", userContent, requestSnapshot = snapshot)
        messages.add(userMessage)
        history.add(userMessage)

        try {
            val apiHistory = if (sendHistory) history else listOf(history.last())
            val response = chatApi.sendMessage(
                history = apiHistory,
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
        isSummarizing = false
        summaryCount = 0
        lastUsage = null
        totalPromptTokens = 0
        totalCompletionTokens = 0
        totalTokens = 0
    }
}

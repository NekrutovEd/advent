package state

import api.ChatApiInterface
import api.ChatMessage
import api.TokenUsage
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import i18n.Lang
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

data class ExtractedMemoryResult(
    val workingItems: List<String>,
    val longTermItems: List<String>
)

class ChatState(
    private val chatApi: ChatApiInterface,
    defaultSendHistory: Boolean = true,
    defaultAutoSummarize: Boolean = true,
    defaultSummarizeThreshold: String = "10",
    defaultKeepLastMessages: String = "4",
    defaultSlidingWindow: String = "",
    defaultExtractMemory: Boolean = false,
    defaultTaskTracking: Boolean = false,
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

    // Sliding Window
    var slidingWindow by mutableStateOf(defaultSlidingWindow)

    // Memory extraction (replaces sticky facts)
    var extractMemory by mutableStateOf(defaultExtractMemory)
    var isExtractingMemory by mutableStateOf(false)

    // Task tracking
    var taskTracking by mutableStateOf(defaultTaskTracking)
    val taskTracker = TaskTracker()

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

        val memoryJson = Json { ignoreUnknownKeys = true }
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
            ChatOption.CONTEXT -> {
                sendHistory = true
                autoSummarize = false
                summarizeThreshold = "10"
                keepLastMessages = "4"
                slidingWindow = ""
                extractMemory = false
            }
            ChatOption.TASK_TRACKING -> {
                taskTracking = true
                taskTracker.reset()
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
        readTimeoutSec: Int?,
        baseUrl: String? = null
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
                jsonSchema = null,
                baseUrl = baseUrl
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

    private fun applySlidingWindow(msgs: List<ChatMessage>): List<ChatMessage> {
        val windowSize = slidingWindow.toIntOrNull() ?: return msgs
        if (windowSize <= 0) return msgs

        // Separate system/summary messages from conversational messages
        val systemMsgs = msgs.filter { it.role == "system" }
        val conversational = msgs.filter { it.role != "system" }

        val windowed = if (conversational.size > windowSize) {
            conversational.takeLast(windowSize)
        } else {
            conversational
        }

        return systemMsgs + windowed
    }

    suspend fun extractMemoryIfNeeded(
        apiKey: String,
        model: String,
        temperature: Double?,
        connectTimeoutSec: Int?,
        readTimeoutSec: Int?,
        baseUrl: String? = null,
        lang: Lang = Lang.EN
    ): ExtractedMemoryResult? {
        if (!extractMemory) return null
        val conversational = history.filter { it.role == "user" || it.role == "assistant" }
        if (conversational.size < 2) return null

        isExtractingMemory = true
        try {
            val lastExchange = conversational.takeLast(2)
            val langInstruction = when (lang) {
                Lang.EN -> "Write extracted facts in English."
                Lang.RU -> "Write extracted facts in Russian (на русском языке)."
            }
            val prompt = buildString {
                appendLine("Latest exchange:")
                lastExchange.forEach { msg ->
                    appendLine("${msg.role}: ${msg.content}")
                }
                appendLine()
                appendLine("Extract any important facts, decisions, or preferences from this exchange.")
                appendLine(langInstruction)
                appendLine("Classify each fact as either \"working\" (relevant to the current task/session) or \"long_term\" (general user preference or enduring fact).")
                appendLine("Return JSON: {\"working\": [\"fact1\", ...], \"long_term\": [\"fact1\", ...]}")
                appendLine("If no facts are worth extracting, return {\"working\": [], \"long_term\": []}")
            }

            val response = chatApi.sendMessage(
                history = listOf(ChatMessage("user", prompt)),
                apiKey = apiKey,
                model = model,
                temperature = temperature,
                maxTokens = 500,
                systemPrompt = "You extract facts from conversations and classify them. Output only valid JSON.",
                connectTimeoutSec = connectTimeoutSec,
                readTimeoutSec = readTimeoutSec,
                stop = null,
                responseFormat = "json_object",
                jsonSchema = null,
                baseUrl = baseUrl
            )

            val jsonElement = memoryJson.parseToJsonElement(response.content.trim())
            val obj = jsonElement.jsonObject
            val working = obj["working"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val longTerm = obj["long_term"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            return ExtractedMemoryResult(working, longTerm)
        } catch (_: Exception) {
            return null
        } finally {
            isExtractingMemory = false
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
        jsonSchema: String? = null,
        baseUrl: String? = null,
        workingMemoryText: String = "",
        longTermMemoryText: String = "",
        profileText: String = "",
        lang: Lang = Lang.EN,
        onMemoryExtracted: (suspend (ExtractedMemoryResult) -> Unit)? = null
    ) {
        isLoading = true
        error = null

        val summaryCountBefore = summaryCount
        summarizeIfNeeded(apiKey, model, temperature, maxTokens, connectTimeoutSec, readTimeoutSec, baseUrl)
        val freshSummarization = summaryCount > summaryCountBefore

        val snapshotHistory = if (sendHistory) applySlidingWindow(history.toList()) else emptyList()

        // Prepend memory and task state as system messages in snapshot
        val taskContext = if (taskTracking) taskTracker.toContextString(lang) else ""
        val memoryPreamble = buildMemoryPreamble(profileText, longTermMemoryText, workingMemoryText, taskContext)
        val snapshotWithMemory = memoryPreamble + snapshotHistory

        val snapshot = try {
            chatApi.buildSnapshot(
                history = snapshotWithMemory,
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
            val fullHistory = if (sendHistory) history.toList() else listOf(history.last())
            val windowedHistory = if (sendHistory) applySlidingWindow(fullHistory) else fullHistory

            // Prepend memory and task state
            val apiHistory = buildMemoryPreamble(profileText, longTermMemoryText, workingMemoryText, taskContext) + windowedHistory

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
                jsonSchema = jsonSchema,
                baseUrl = baseUrl
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

            // Extract memory after successful response
            val result = extractMemoryIfNeeded(apiKey, model, temperature, connectTimeoutSec, readTimeoutSec, baseUrl, lang)
            if (result != null) {
                onMemoryExtracted?.invoke(result)
            }

            // Extract task state after successful response
            extractTaskStateIfNeeded(apiKey, model, temperature, connectTimeoutSec, readTimeoutSec, baseUrl, lang)
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
            if (history.isNotEmpty()) history.removeLast()
            messages.removeLastOrNull()
        } finally {
            isLoading = false
        }
    }

    private suspend fun extractTaskStateIfNeeded(
        apiKey: String,
        model: String,
        temperature: Double?,
        connectTimeoutSec: Int?,
        readTimeoutSec: Int?,
        baseUrl: String? = null,
        lang: Lang = Lang.EN
    ) {
        if (!taskTracking || taskTracker.isPaused) return
        val conversational = history.filter { it.role == "user" || it.role == "assistant" }
        if (conversational.size < 2) return

        taskTracker.isExtracting = true
        try {
            val extracted = TaskTracker.extractState(
                chatApi, conversational, apiKey, model, temperature,
                connectTimeoutSec, readTimeoutSec, baseUrl, lang
            ) ?: return

            taskTracker.phase = extracted.phase
            taskTracker.taskDescription = extracted.taskDescription
            taskTracker.steps.clear()
            taskTracker.steps.addAll(extracted.steps)
            taskTracker.currentStepIndex = extracted.currentStepIndex
        } finally {
            taskTracker.isExtracting = false
        }
    }

    private fun buildMemoryPreamble(profileText: String, longTermMemoryText: String, workingMemoryText: String, taskContext: String = ""): List<ChatMessage> {
        return buildList {
            if (profileText.isNotBlank()) {
                add(ChatMessage("system", "[Active Profile]\n$profileText"))
            }
            if (longTermMemoryText.isNotBlank()) {
                add(ChatMessage("system", "[Long-Term Memory]\n$longTermMemoryText"))
            }
            if (workingMemoryText.isNotBlank()) {
                add(ChatMessage("system", "[Task Context]\n$workingMemoryText"))
            }
            if (taskContext.isNotBlank()) {
                add(ChatMessage("system", taskContext))
            }
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
        isExtractingMemory = false
        taskTracker.reset()
    }
}

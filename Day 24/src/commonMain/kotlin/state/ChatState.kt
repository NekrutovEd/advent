package state

import api.ChatApiInterface
import api.ChatMessage
import api.ChatResponse
import api.TokenUsage
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import i18n.Lang
import mcp.McpTool
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

    // Invariant checking
    var isCheckingInvariants by mutableStateOf(false)

    // Task tracking
    var taskTracking by mutableStateOf(defaultTaskTracking)
    val taskTracker = TaskTracker()

    // RAG (Retrieval-Augmented Generation)
    var ragEnabled by mutableStateOf(false)
    var ragMode by mutableStateOf(RagMode.RERANKED)
    var lastRagSources by mutableStateOf("")

    private val history = mutableListOf<ChatMessage>()

    fun historySnapshot(): List<ChatMessage> = history.toList()

    fun restoreHistory(msgs: List<ChatMessage>) {
        history.clear()
        history.addAll(msgs)
    }

    private companion object {
        const val MAX_TOOL_ROUNDS = 10

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
            ChatOption.RAG -> {
                ragEnabled = false
                lastRagSources = ""
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
        invariantsText: String = "",
        lang: Lang = Lang.EN,
        onMemoryExtracted: (suspend (ExtractedMemoryResult) -> Unit)? = null,
        mcpTools: List<McpTool>? = null,
        toolExecutor: (suspend (String, String) -> String)? = null,
        schedulerChatId: String = "",
        schedulerSessionId: String = "",
        hideUserMessage: Boolean = false,
        ragContextText: String = ""
    ) {
        isLoading = true
        error = null

        val summaryCountBefore = summaryCount
        summarizeIfNeeded(apiKey, model, temperature, maxTokens, connectTimeoutSec, readTimeoutSec, baseUrl)
        val freshSummarization = summaryCount > summaryCountBefore

        val snapshotHistory = if (sendHistory) applySlidingWindow(history.toList()) else emptyList()

        // Prepend memory and task state as system messages in snapshot
        val taskContext = if (taskTracking) taskTracker.toContextString(lang) else ""
        val hasTools = mcpTools?.isNotEmpty() == true
        val memoryPreamble = buildMemoryPreamble(profileText, longTermMemoryText, workingMemoryText, taskContext, invariantsText, schedulerChatId, schedulerSessionId, hasTools, ragContextText)
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
        if (!hideUserMessage) messages.add(userMessage)
        history.add(userMessage)

        try {
            val effectiveTools = mcpTools?.takeIf { it.isNotEmpty() }

            var response = sendApiRequest(
                apiKey, model, temperature, maxTokens, systemPrompt,
                connectTimeoutSec, readTimeoutSec, stop, responseFormat, jsonSchema,
                baseUrl, profileText, longTermMemoryText, workingMemoryText,
                taskContext, invariantsText, effectiveTools,
                schedulerChatId, schedulerSessionId, ragContextText
            )
            accumulateUsage(response)

            // Tool call loop — max 10 rounds to prevent infinite loops
            var toolRounds = 0
            while (response.toolCalls != null && toolExecutor != null && toolRounds < MAX_TOOL_ROUNDS) {
                toolRounds++

                // Add assistant message with tool_calls to history
                val assistantToolMsg = ChatMessage("assistant", "", toolCalls = response.toolCalls)
                history.add(assistantToolMsg)

                // Execute each tool call and add results
                for (tc in response.toolCalls!!) {
                    // Show tool call in UI
                    messages.add(ChatMessage("tool", "\u2699 ${tc.name}", toolCallId = tc.id))

                    val result = try {
                        toolExecutor(tc.name, tc.arguments)
                    } catch (e: Exception) {
                        "Error: ${e.message}"
                    }

                    // Update UI bubble with result preview
                    val lastIdx = messages.size - 1
                    val preview = result.take(200).let { if (result.length > 200) "$it..." else it }
                    messages[lastIdx] = ChatMessage("tool", "\u2699 ${tc.name}\n$preview", toolCallId = tc.id)

                    // Add tool result to history
                    history.add(ChatMessage("tool", result, toolCallId = tc.id))
                }

                // Send again with tool results
                response = sendApiRequest(
                    apiKey, model, temperature, maxTokens, systemPrompt,
                    connectTimeoutSec, readTimeoutSec, stop, responseFormat, jsonSchema,
                    baseUrl, profileText, longTermMemoryText, workingMemoryText,
                    taskContext, invariantsText, effectiveTools
                )
                accumulateUsage(response)
            }

            val violation = if (invariantsText.isNotBlank()) {
                checkInvariants(response.content, invariantsText, apiKey, model, temperature, connectTimeoutSec, readTimeoutSec, baseUrl, lang)
            } else null

            val ragSourcesForMessage = if (ragContextText.isNotBlank()) lastRagSources.takeIf { it.isNotBlank() } else null
            val assistantMessage = ChatMessage("assistant", response.content, invariantViolation = violation, ragSources = ragSourcesForMessage)
            history.add(ChatMessage("assistant", response.content))
            messages.add(assistantMessage)

            // Extract memory after successful response
            val result = extractMemoryIfNeeded(apiKey, model, temperature, connectTimeoutSec, readTimeoutSec, baseUrl, lang)
            if (result != null) {
                onMemoryExtracted?.invoke(result)
            }

            // Auto-resume when user sends a message while paused
            if (taskTracker.isPaused) {
                taskTracker.resume()
            }

            // Extract task state after successful response
            extractTaskStateIfNeeded(apiKey, model, temperature, connectTimeoutSec, readTimeoutSec, baseUrl, lang)
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
            if (history.isNotEmpty()) history.removeLast()
            if (!hideUserMessage) messages.removeLastOrNull()
        } finally {
            isLoading = false
        }
    }

    private suspend fun sendApiRequest(
        apiKey: String, model: String, temperature: Double?, maxTokens: Int?,
        systemPrompt: String?, connectTimeoutSec: Int?, readTimeoutSec: Int?,
        stop: List<String>?, responseFormat: String?, jsonSchema: String?,
        baseUrl: String?, profileText: String, longTermMemoryText: String,
        workingMemoryText: String, taskContext: String, invariantsText: String,
        tools: List<McpTool>?,
        schedulerChatId: String = "", schedulerSessionId: String = "",
        ragContextText: String = ""
    ): ChatResponse {
        val fullHistory = if (sendHistory) history.toList() else listOf(history.last())
        val windowedHistory = if (sendHistory) applySlidingWindow(fullHistory) else fullHistory
        // When no tools are provided, strip tool-related messages to avoid API errors
        val cleanHistory = if (tools == null) {
            windowedHistory.filter { it.role != "tool" && it.toolCalls == null }
        } else windowedHistory
        val apiHistory = buildMemoryPreamble(profileText, longTermMemoryText, workingMemoryText, taskContext, invariantsText, schedulerChatId, schedulerSessionId, hasTools = tools != null, ragContextText = ragContextText) + cleanHistory

        return chatApi.sendMessage(
            history = apiHistory,
            apiKey = apiKey, model = model, temperature = temperature, maxTokens = maxTokens,
            systemPrompt = systemPrompt, connectTimeoutSec = connectTimeoutSec,
            readTimeoutSec = readTimeoutSec, stop = stop, responseFormat = responseFormat,
            jsonSchema = jsonSchema, baseUrl = baseUrl, tools = tools
        )
    }

    private fun accumulateUsage(response: ChatResponse) {
        if (response.usage != null) {
            lastUsage = response.usage
            totalPromptTokens += response.usage.promptTokens
            totalCompletionTokens += response.usage.completionTokens
            totalTokens += response.usage.totalTokens
        }
    }

    private suspend fun checkInvariants(
        assistantResponse: String,
        invariantsText: String,
        apiKey: String,
        model: String,
        temperature: Double?,
        connectTimeoutSec: Int?,
        readTimeoutSec: Int?,
        baseUrl: String? = null,
        lang: Lang = Lang.EN
    ): String? {
        isCheckingInvariants = true
        try {
            val langInstruction = when (lang) {
                Lang.EN -> "Write the violation description in English."
                Lang.RU -> "Write the violation description in Russian (на русском языке)."
            }
            val prompt = buildString {
                appendLine("Check if the following assistant response violates any of these invariants.")
                appendLine()
                appendLine("INVARIANTS:")
                appendLine(invariantsText)
                appendLine()
                appendLine("ASSISTANT RESPONSE:")
                appendLine(assistantResponse)
                appendLine()
                appendLine("$langInstruction")
                appendLine("Return JSON: {\"violated\": true/false, \"description\": \"which invariant was violated and how, or empty string if none\"}")
            }
            val response = chatApi.sendMessage(
                history = listOf(ChatMessage("user", prompt)),
                apiKey = apiKey,
                model = model,
                temperature = temperature,
                maxTokens = 300,
                systemPrompt = "You are a strict invariant checker. Analyze the assistant response against the given invariants. Output only valid JSON.",
                connectTimeoutSec = connectTimeoutSec,
                readTimeoutSec = readTimeoutSec,
                stop = null,
                responseFormat = "json_object",
                jsonSchema = null,
                baseUrl = baseUrl
            )
            val jsonElement = memoryJson.parseToJsonElement(response.content.trim())
            val obj = jsonElement.jsonObject
            val violated = obj["violated"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            if (!violated) return null
            return obj["description"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            return null
        } finally {
            isCheckingInvariants = false
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
                connectTimeoutSec, readTimeoutSec, baseUrl, lang,
                currentPhase = taskTracker.phase
            ) ?: return

            // Enforce controlled state transitions — reject illegal phase jumps
            val transitionAllowed = taskTracker.tryTransition(extracted.phase)

            // Always update metadata (steps, description) regardless of transition result
            taskTracker.taskDescription = extracted.taskDescription
            taskTracker.steps.clear()
            taskTracker.steps.addAll(extracted.steps)
            taskTracker.currentStepIndex = extracted.currentStepIndex

            if (!transitionAllowed) {
                // Phase remains unchanged; rejection is recorded for UI and next context injection
                return
            }
        } finally {
            taskTracker.isExtracting = false
        }
    }

    private fun buildMemoryPreamble(
        profileText: String, longTermMemoryText: String, workingMemoryText: String,
        taskContext: String = "", invariantsText: String = "",
        schedulerChatId: String = "", schedulerSessionId: String = "",
        hasTools: Boolean = false, ragContextText: String = ""
    ): List<ChatMessage> {
        return buildList {
            if (hasTools) {
                add(ChatMessage("system", buildString {
                    appendLine("[MCP Tools — IMPORTANT]")
                    appendLine("You have access to tools via function calling. You MUST use them by making actual tool_call requests.")
                    appendLine("DO NOT describe tool calls as text or pseudocode. DO NOT write 'functions.name(...)' or similar.")
                    appendLine("Instead, invoke tools directly through the tool_call mechanism provided by the API.")
                    appendLine("When a task requires multiple tools, call them step by step — execute one tool, wait for its result, then call the next.")
                    appendLine("Always use the tool's actual parameter names from its schema.")
                }))
            }
            if (profileText.isNotBlank()) {
                add(ChatMessage("system", "[Active Profile]\n$profileText"))
            }
            if (longTermMemoryText.isNotBlank()) {
                add(ChatMessage("system", "[Long-Term Memory]\n$longTermMemoryText"))
            }
            if (workingMemoryText.isNotBlank()) {
                add(ChatMessage("system", "[Task Context]\n$workingMemoryText"))
            }
            if (invariantsText.isNotBlank()) {
                add(ChatMessage("system", "[INVARIANTS — MUST NOT BE VIOLATED]\nThe following invariants are absolute constraints. You MUST respect them in every response. If the user's request conflicts with an invariant, explain the conflict and refuse to violate the invariant.\n$invariantsText"))
            }
            if (taskContext.isNotBlank()) {
                add(ChatMessage("system", taskContext))
            }
            if (ragContextText.isNotBlank()) {
                add(ChatMessage("system", ragContextText))
            }
            if (schedulerChatId.isNotBlank() && schedulerSessionId.isNotBlank()) {
                add(ChatMessage("system", "[Scheduler Context]\nchat_id: $schedulerChatId\nsession_id: $schedulerSessionId\nWhen using schedule_once or schedule_recurring tools:\n1. Pass these IDs as chat_id and session_id so results are delivered back to this chat.\n2. CRITICAL: The action_prompt field must contain a DIRECT instruction for what to do when the task fires — e.g. \"Расскажи анекдот\" or \"Tell a joke\". Do NOT put the original user request (like \"расскажи анекдот через минуту\") — strip away ALL time/scheduling references and write ONLY the action. The AI receiving this prompt will execute it immediately without scheduling."))
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
        isCheckingInvariants = false
        taskTracker.reset()
        lastRagSources = ""
    }
}

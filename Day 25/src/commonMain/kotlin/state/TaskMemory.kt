package state

import api.ChatApiInterface
import api.ChatMessage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import i18n.Lang
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Structured task memory — accumulates conversation context across turns.
 * Unlike TaskTracker (which tracks phases), TaskMemory captures the *content*
 * of the task: what the user wants, what's been established, and what's covered.
 */
class TaskMemory {
    var goal by mutableStateOf<String?>(null)
    val clarifications = mutableStateListOf<String>()
    val constraints = mutableStateListOf<String>()
    val coveredTopics = mutableStateListOf<String>()
    var isExtracting by mutableStateOf(false)

    /**
     * Format task memory for injection into the system prompt.
     * Returns empty string if memory is empty (nothing to inject).
     */
    fun toPromptContext(): String {
        val hasContent = goal != null ||
            clarifications.isNotEmpty() ||
            constraints.isNotEmpty() ||
            coveredTopics.isNotEmpty()
        if (!hasContent) return ""

        return buildString {
            appendLine("[Task Memory — accumulated conversation context]")
            if (goal != null) {
                appendLine("GOAL: $goal")
            }
            if (clarifications.isNotEmpty()) {
                appendLine("ESTABLISHED FACTS:")
                clarifications.forEach { appendLine("  - $it") }
            }
            if (constraints.isNotEmpty()) {
                appendLine("USER CONSTRAINTS:")
                constraints.forEach { appendLine("  - $it") }
            }
            if (coveredTopics.isNotEmpty()) {
                appendLine("ALREADY COVERED:")
                coveredTopics.forEach { appendLine("  - $it") }
            }
            appendLine()
            appendLine("Use this context to maintain coherence. Do NOT repeat already-covered topics unless asked.")
        }.trim()
    }

    fun reset() {
        goal = null
        clarifications.clear()
        constraints.clear()
        coveredTopics.clear()
        isExtracting = false
    }

    companion object {
        private val parseJson = Json { ignoreUnknownKeys = true }

        /**
         * Use LLM to extract/update task memory from the latest exchange.
         */
        suspend fun extractFromConversation(
            chatApi: ChatApiInterface,
            conversationHistory: List<ChatMessage>,
            currentMemory: TaskMemory,
            apiKey: String,
            model: String,
            temperature: Double?,
            connectTimeoutSec: Int?,
            readTimeoutSec: Int?,
            baseUrl: String? = null,
            lang: Lang = Lang.EN
        ): ExtractedTaskMemory? {
            if (conversationHistory.size < 2) return null

            val recent = conversationHistory.takeLast(6)
            val langInstruction = when (lang) {
                Lang.EN -> "Write all values in English."
                Lang.RU -> "Write all values in Russian (на русском языке)."
            }

            val currentContext = buildString {
                if (currentMemory.goal != null) appendLine("Current goal: ${currentMemory.goal}")
                if (currentMemory.clarifications.isNotEmpty())
                    appendLine("Established: ${currentMemory.clarifications.joinToString("; ")}")
                if (currentMemory.constraints.isNotEmpty())
                    appendLine("Constraints: ${currentMemory.constraints.joinToString("; ")}")
                if (currentMemory.coveredTopics.isNotEmpty())
                    appendLine("Covered: ${currentMemory.coveredTopics.joinToString("; ")}")
            }

            val prompt = buildString {
                appendLine("Analyze this conversation and extract/update the task memory.")
                appendLine(langInstruction)
                appendLine()
                if (currentContext.isNotBlank()) {
                    appendLine("CURRENT TASK MEMORY:")
                    appendLine(currentContext)
                    appendLine()
                }
                appendLine("CONVERSATION:")
                recent.forEach { msg ->
                    val content = if (msg.content.length > 800)
                        msg.content.take(500) + "\n...\n" + msg.content.takeLast(300)
                    else msg.content
                    appendLine("${msg.role}: $content")
                }
                appendLine()
                appendLine("Extract:")
                appendLine("- goal: The user's current overarching goal (update if topic changed, keep if refined)")
                appendLine("- new_clarifications: NEW facts established in the latest exchange (not already in current memory)")
                appendLine("- new_constraints: NEW constraints/requirements mentioned (not already in current memory)")
                appendLine("- new_covered: NEW topics/aspects covered in the latest answer (not already in current memory)")
                appendLine()
                appendLine("IMPORTANT: Only include NEW items not already in current memory. If nothing new, use empty arrays.")
                appendLine("If the user changed the topic entirely, set goal to the new topic.")
                appendLine()
                appendLine("Return JSON only:")
                appendLine("""{"goal":"...","new_clarifications":["..."],"new_constraints":["..."],"new_covered":["..."]}""")
                appendLine("""If nothing to extract: {"goal":null,"new_clarifications":[],"new_constraints":[],"new_covered":[]}""")
            }

            return try {
                val response = chatApi.sendMessage(
                    history = listOf(ChatMessage("user", prompt)),
                    apiKey = apiKey,
                    model = model,
                    temperature = temperature,
                    maxTokens = 400,
                    systemPrompt = "You extract task memory from conversations. Output only valid JSON.",
                    connectTimeoutSec = connectTimeoutSec,
                    readTimeoutSec = readTimeoutSec,
                    stop = null,
                    responseFormat = "json_object",
                    jsonSchema = null,
                    baseUrl = baseUrl
                )

                val jsonElement = parseJson.parseToJsonElement(response.content.trim())
                val obj = jsonElement.jsonObject

                val goal = obj["goal"]?.jsonPrimitive?.content?.takeIf { it != "null" && it.isNotBlank() }
                val newClarifications = obj["new_clarifications"]?.jsonArray
                    ?.map { it.jsonPrimitive.content } ?: emptyList()
                val newConstraints = obj["new_constraints"]?.jsonArray
                    ?.map { it.jsonPrimitive.content } ?: emptyList()
                val newCovered = obj["new_covered"]?.jsonArray
                    ?.map { it.jsonPrimitive.content } ?: emptyList()

                ExtractedTaskMemory(goal, newClarifications, newConstraints, newCovered)
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class ExtractedTaskMemory(
    val goal: String?,
    val newClarifications: List<String>,
    val newConstraints: List<String>,
    val newCovered: List<String>
)

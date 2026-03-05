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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull

enum class TaskPhase {
    IDLE, PLANNING, EXECUTION, VALIDATION, DONE;

    fun next(): TaskPhase = when (this) {
        IDLE -> PLANNING
        PLANNING -> EXECUTION
        EXECUTION -> VALIDATION
        VALIDATION -> DONE
        DONE -> DONE
    }
}

data class TaskStep(val description: String, val completed: Boolean = false)

class TaskTracker {
    var phase by mutableStateOf(TaskPhase.IDLE)
    var isPaused by mutableStateOf(false)
    val steps = mutableStateListOf<TaskStep>()
    var currentStepIndex by mutableStateOf(0)
    var expectedAction by mutableStateOf("")
    var taskDescription by mutableStateOf("")
    var isExtracting by mutableStateOf(false)

    fun pause() { isPaused = true }
    fun resume() { isPaused = false }

    fun toContextString(lang: Lang): String {
        if (phase == TaskPhase.IDLE) return ""
        return buildString {
            val phaseLabel = when (lang) {
                Lang.EN -> "Phase"
                Lang.RU -> "Faza"
            }
            val stepsLabel = when (lang) {
                Lang.EN -> "Steps"
                Lang.RU -> "Shagi"
            }
            val expectedLabel = when (lang) {
                Lang.EN -> "Expected"
                Lang.RU -> "Ozhidaemoe"
            }
            val pausedLabel = when (lang) {
                Lang.EN -> "PAUSED"
                Lang.RU -> "PAUSED"
            }
            appendLine("[Task State]")
            if (taskDescription.isNotBlank()) appendLine("Task: $taskDescription")
            append("$phaseLabel: ${phase.name}")
            if (isPaused) append(" ($pausedLabel)")
            appendLine()
            if (steps.isNotEmpty()) {
                appendLine("$stepsLabel:")
                steps.forEachIndexed { i, step ->
                    val marker = when {
                        step.completed -> "[x]"
                        i == currentStepIndex -> "[>]"
                        else -> "[ ]"
                    }
                    appendLine("  $marker ${step.description}")
                }
            }
            if (expectedAction.isNotBlank()) {
                appendLine("$expectedLabel: $expectedAction")
            }
            if (isPaused) {
                when (lang) {
                    Lang.EN -> appendLine("The task is paused. Continue from where you left off without repeating previous explanations.")
                    Lang.RU -> appendLine("The task is paused. Continue from where you left off without repeating previous explanations.")
                }
            }
        }.trim()
    }

    fun reset() {
        phase = TaskPhase.IDLE
        isPaused = false
        steps.clear()
        currentStepIndex = 0
        expectedAction = ""
        taskDescription = ""
        isExtracting = false
    }

    companion object {
        private val parseJson = Json { ignoreUnknownKeys = true }

        suspend fun extractState(
            chatApi: ChatApiInterface,
            conversationHistory: List<ChatMessage>,
            apiKey: String,
            model: String,
            temperature: Double?,
            connectTimeoutSec: Int?,
            readTimeoutSec: Int?,
            baseUrl: String? = null,
            lang: Lang = Lang.EN
        ): ExtractedTaskState? {
            if (conversationHistory.size < 2) return null

            val recent = conversationHistory.takeLast(6)
            val langInstruction = when (lang) {
                Lang.EN -> "Write step descriptions in English."
                Lang.RU -> "Write step descriptions in Russian."
            }

            val prompt = buildString {
                appendLine("Analyze this conversation and determine the current task state.")
                appendLine(langInstruction)
                appendLine()
                recent.forEach { msg ->
                    appendLine("${msg.role}: ${msg.content.take(500)}")
                }
                appendLine()
                appendLine("Determine:")
                appendLine("1. task_description: brief description of the overall task (empty if just chatting)")
                appendLine("2. phase: one of idle/planning/execution/validation/done")
                appendLine("   - idle: no task, casual conversation")
                appendLine("   - planning: discussing approach, requirements, design")
                appendLine("   - execution: actively implementing, writing code, creating content")
                appendLine("   - validation: reviewing, testing, checking results")
                appendLine("   - done: task completed")
                appendLine("3. steps: array of sub-step descriptions for the current phase (max 5)")
                appendLine("4. current_step: 0-based index of the active step")
                appendLine("5. completed_steps: array of 0-based indices of completed steps")
                appendLine("6. expected_action: what is expected next from the user or assistant")
                appendLine("7. awaiting_user: true if the assistant asked a question and waits for user input")
                appendLine()
                appendLine("Return JSON only:")
                appendLine("""{"task_description":"...","phase":"...","steps":["..."],"current_step":0,"completed_steps":[0],"expected_action":"...","awaiting_user":false}""")
                appendLine("If it's casual conversation with no task, return {\"phase\":\"idle\"}")
            }

            return try {
                val response = chatApi.sendMessage(
                    history = listOf(ChatMessage("user", prompt)),
                    apiKey = apiKey,
                    model = model,
                    temperature = temperature,
                    maxTokens = 300,
                    systemPrompt = "You analyze conversations and extract task state. Output only valid JSON.",
                    connectTimeoutSec = connectTimeoutSec,
                    readTimeoutSec = readTimeoutSec,
                    stop = null,
                    responseFormat = "json_object",
                    jsonSchema = null,
                    baseUrl = baseUrl
                )

                val jsonElement = parseJson.parseToJsonElement(response.content.trim())
                val obj = jsonElement.jsonObject

                val phaseStr = obj["phase"]?.jsonPrimitive?.content ?: "idle"
                val phase = when (phaseStr.lowercase()) {
                    "planning" -> TaskPhase.PLANNING
                    "execution" -> TaskPhase.EXECUTION
                    "validation" -> TaskPhase.VALIDATION
                    "done" -> TaskPhase.DONE
                    else -> TaskPhase.IDLE
                }

                val stepsArr = obj["steps"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                val currentStep = obj["current_step"]?.jsonPrimitive?.intOrNull ?: 0
                val completedSteps = obj["completed_steps"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.intOrNull }?.toSet() ?: emptySet()
                val expectedAction = obj["expected_action"]?.jsonPrimitive?.content ?: ""
                val taskDescription = obj["task_description"]?.jsonPrimitive?.content ?: ""
                val awaitingUser = obj["awaiting_user"]?.jsonPrimitive?.booleanOrNull ?: false

                ExtractedTaskState(
                    phase = phase,
                    steps = stepsArr.mapIndexed { i, desc ->
                        TaskStep(desc, i in completedSteps)
                    },
                    currentStepIndex = currentStep.coerceIn(0, (stepsArr.size - 1).coerceAtLeast(0)),
                    expectedAction = expectedAction,
                    taskDescription = taskDescription,
                    awaitingUser = awaitingUser
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class ExtractedTaskState(
    val phase: TaskPhase,
    val steps: List<TaskStep>,
    val currentStepIndex: Int,
    val expectedAction: String,
    val taskDescription: String,
    val awaitingUser: Boolean
)

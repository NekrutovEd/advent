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
    var taskDescription by mutableStateOf("")
    var isExtracting by mutableStateOf(false)

    fun pause() { isPaused = true }
    fun resume() { isPaused = false }

    fun phaseActionLabel(lang: Lang): String = when (lang) {
        Lang.EN -> when (phase) {
            TaskPhase.IDLE -> ""
            TaskPhase.PLANNING -> "Planning..."
            TaskPhase.EXECUTION -> "Working..."
            TaskPhase.VALIDATION -> "Verifying..."
            TaskPhase.DONE -> "Complete"
        }
        Lang.RU -> when (phase) {
            TaskPhase.IDLE -> ""
            TaskPhase.PLANNING -> "Планирование..."
            TaskPhase.EXECUTION -> "Выполнение..."
            TaskPhase.VALIDATION -> "Проверка..."
            TaskPhase.DONE -> "Готово"
        }
    }

    fun toContextString(lang: Lang): String {
        val flowRules = "[Task Flow Rules] Complete the ENTIRE task in a SINGLE response. " +
            "Never stop to ask for confirmation, approval, or permission. " +
            "Never ask 'should I proceed?', 'shall I continue?', 'do you want me to...?'. " +
            "Just do the work from start to finish."

        if (phase == TaskPhase.IDLE) return flowRules

        return buildString {
            appendLine("[Task State]")
            if (taskDescription.isNotBlank()) appendLine("Task: $taskDescription")
            appendLine("Phase: ${phase.name}")
            if (steps.isNotEmpty()) {
                appendLine("Steps:")
                steps.forEachIndexed { i, step ->
                    val marker = when {
                        step.completed -> "[x]"
                        i == currentStepIndex -> "[>]"
                        else -> "[ ]"
                    }
                    appendLine("  $marker ${step.description}")
                }
            }
            appendLine()
            if (isPaused) {
                appendLine("[Task Resumed] The user paused and sent a new message.")
                appendLine("Read it carefully — it may contain corrections or extra context.")
                appendLine("Continue from where you left off. Do NOT repeat previous work.")
            } else {
                appendLine(flowRules)
            }
        }.trim()
    }

    fun reset() {
        phase = TaskPhase.IDLE
        isPaused = false
        steps.clear()
        currentStepIndex = 0
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
                appendLine("PHASE RULES (the assistant should never stop between phases):")
                appendLine("- idle: no task, casual conversation")
                appendLine("- planning: the assistant outlined/stated the approach (even briefly)")
                appendLine("- execution: the assistant is actively doing the work (the main phase)")
                appendLine("- validation: the assistant is reviewing or verifying the result")
                appendLine("- done: task complete, final result presented")
                appendLine("- The assistant completes all phases in one response. Detect the LAST phase reached.")
                appendLine("- If the response contains a plan AND execution, the phase is execution (not planning).")
                appendLine("- If the response contains execution AND verification, the phase is validation.")
                appendLine("- If the task is fully complete, the phase is done.")
                appendLine()
                appendLine("Determine:")
                appendLine("1. task_description: brief description of the overall task (empty if just chatting)")
                appendLine("2. phase: one of idle/planning/execution/validation/done (the LAST phase reached)")
                appendLine("3. steps: array of sub-step descriptions for the overall task (max 5)")
                appendLine("4. current_step: 0-based index of the active step")
                appendLine("5. completed_steps: array of 0-based indices of completed steps")
                appendLine()
                appendLine("Return JSON only:")
                appendLine("""{"task_description":"...","phase":"...","steps":["..."],"current_step":0,"completed_steps":[0]}""")
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
                val taskDescription = obj["task_description"]?.jsonPrimitive?.content ?: ""

                ExtractedTaskState(
                    phase = phase,
                    steps = stepsArr.mapIndexed { i, desc ->
                        TaskStep(desc, i in completedSteps)
                    },
                    currentStepIndex = currentStep.coerceIn(0, (stepsArr.size - 1).coerceAtLeast(0)),
                    taskDescription = taskDescription
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
    val taskDescription: String
)

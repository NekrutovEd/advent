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
        if (phase == TaskPhase.IDLE) return buildString {
            appendLine("[Task Flow Rules]")
            appendLine("If the user gives you a task:")
            appendLine("1. PLANNING: First present a clear plan with numbered steps. Then STOP and ask the user to confirm.")
            appendLine("2. EXECUTION: After the user confirms, execute the plan completely without stopping.")
            appendLine("3. VALIDATION: Review and verify the result.")
            appendLine("4. DONE: Present the final result.")
            appendLine("Do NOT ask for permission at each step during execution. Only stop after presenting the plan.")
        }.trim()
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
            if (expectedAction.isNotBlank()) {
                appendLine("Expected: $expectedAction")
            }
            appendLine()
            // Flow rules
            appendLine("[Task Flow Rules]")
            when {
                isPaused -> {
                    appendLine("Task is PAUSED by user. The user sent a message to continue.")
                    appendLine("- Read the user's message carefully — it may contain corrections or additional context.")
                    appendLine("- Continue from where you left off. Do NOT repeat previous explanations or re-plan.")
                    appendLine("- Resume the current phase and proceed.")
                }
                phase == TaskPhase.PLANNING -> {
                    appendLine("You are in PLANNING phase.")
                    appendLine("- Present your plan clearly with numbered steps.")
                    appendLine("- After presenting the plan, STOP and ask the user to confirm before proceeding.")
                    appendLine("- Do NOT start executing until the user confirms the plan.")
                }
                phase == TaskPhase.EXECUTION || phase == TaskPhase.VALIDATION -> {
                    appendLine("You are in ${phase.name} phase.")
                    appendLine("- Proceed with the work WITHOUT stopping or asking for confirmation.")
                    appendLine("- Complete all steps in this phase in a single response.")
                    appendLine("- Move to the next phase automatically when done.")
                    appendLine("- Do NOT ask 'should I continue?' — just do it.")
                }
                phase == TaskPhase.DONE -> {
                    appendLine("Task is DONE. Present the final result.")
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
                appendLine("IMPORTANT FLOW RULES:")
                appendLine("- The task flow is: planning -> execution -> validation -> done")
                appendLine("- PLANNING: the assistant is discussing approach, presenting a plan, or asking clarifying questions")
                appendLine("- EXECUTION: the assistant is actively doing the work (writing, implementing, creating)")
                appendLine("- VALIDATION: the assistant is reviewing, checking, or verifying the result")
                appendLine("- DONE: the task is complete, final result presented")
                appendLine("- idle: no task, casual conversation")
                appendLine()
                appendLine("PHASE DETECTION RULES:")
                appendLine("- If the assistant presented a plan and is waiting for user confirmation -> planning, awaiting_user=true")
                appendLine("- If the user confirmed the plan (e.g. 'yes', 'ok', 'go ahead', 'do it') -> execution, awaiting_user=false")
                appendLine("- If the assistant is actively working on the task -> execution, awaiting_user=false")
                appendLine("- If the assistant finished the work and is reviewing -> validation, awaiting_user=false")
                appendLine("- If the task is complete -> done, awaiting_user=false")
                appendLine("- awaiting_user should ONLY be true when the assistant explicitly asked the user a question and needs an answer to proceed")
                appendLine()
                appendLine("Determine:")
                appendLine("1. task_description: brief description of the overall task (empty if just chatting)")
                appendLine("2. phase: one of idle/planning/execution/validation/done")
                appendLine("3. steps: array of sub-step descriptions for the current phase (max 5)")
                appendLine("4. current_step: 0-based index of the active step")
                appendLine("5. completed_steps: array of 0-based indices of completed steps")
                appendLine("6. expected_action: brief description of what happens next (from the assistant's perspective)")
                appendLine("7. awaiting_user: true ONLY if the assistant asked a question and cannot proceed without user's answer")
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

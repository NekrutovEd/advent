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

    /** Allowed transitions: key = current phase, value = set of valid target phases. */
    companion object {
        val allowedTransitions: Map<TaskPhase, Set<TaskPhase>> = mapOf(
            IDLE       to setOf(IDLE, PLANNING, DONE),         // DONE for trivial Q&A
            PLANNING   to setOf(PLANNING, EXECUTION),          // must execute before validate/done
            EXECUTION  to setOf(EXECUTION, VALIDATION),        // must validate before done
            VALIDATION to setOf(VALIDATION, EXECUTION, DONE),  // can loop back to fix issues
            DONE       to setOf(DONE, IDLE)                    // reset for new task
        )

        fun isTransitionAllowed(from: TaskPhase, to: TaskPhase): Boolean =
            to in (allowedTransitions[from] ?: emptySet())
    }
}

data class TransitionRejection(
    val attempted: TaskPhase,
    val current: TaskPhase,
    val timestamp: Long = currentTimeMillis()
)

internal expect fun currentTimeMillis(): Long

data class TaskStep(val description: String, val completed: Boolean = false)

class TaskTracker {
    var phase by mutableStateOf(TaskPhase.IDLE)
    var isPaused by mutableStateOf(false)
    val steps = mutableStateListOf<TaskStep>()
    var currentStepIndex by mutableStateOf(0)
    var taskDescription by mutableStateOf("")
    var isExtracting by mutableStateOf(false)
    var lastRejection by mutableStateOf<TransitionRejection?>(null)

    fun pause() { isPaused = true }
    fun resume() { isPaused = false }

    /**
     * Attempt to transition to [target] phase.
     * Returns true if transition is allowed and applied, false if rejected.
     */
    fun tryTransition(target: TaskPhase): Boolean {
        if (target == phase) return true // staying in same phase is always ok
        if (TaskPhase.isTransitionAllowed(phase, target)) {
            phase = target
            lastRejection = null
            return true
        }
        lastRejection = TransitionRejection(attempted = target, current = phase)
        return false
    }

    fun dismissRejection() { lastRejection = null }

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

        val transitionRules = "[Phase Transition Rules] " +
            "Phases must follow a strict order: PLANNING → EXECUTION → VALIDATION → DONE. " +
            "You CANNOT skip phases. Specifically: " +
            "you cannot execute without a plan; " +
            "you cannot validate without execution; " +
            "you cannot finish without validation. " +
            "Allowed transitions: " +
            "IDLE→PLANNING, IDLE→DONE (trivial Q&A only), " +
            "PLANNING→EXECUTION, " +
            "EXECUTION→VALIDATION, " +
            "VALIDATION→DONE, VALIDATION→EXECUTION (to fix issues)."

        if (phase == TaskPhase.IDLE) return "$flowRules\n$transitionRules"

        return buildString {
            appendLine("[Task State]")
            if (taskDescription.isNotBlank()) appendLine("Task: $taskDescription")
            appendLine("Phase: ${phase.name}")
            val allowed = TaskPhase.allowedTransitions[phase]
                ?.filter { it != phase }
                ?.joinToString(", ") { it.name } ?: ""
            appendLine("Allowed next phases: $allowed")
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
            if (lastRejection != null) {
                appendLine()
                appendLine("[TRANSITION BLOCKED] Attempted ${lastRejection!!.attempted.name} " +
                    "from ${lastRejection!!.current.name} — this transition is not allowed. " +
                    "You must follow the phase order.")
            }
            appendLine()
            if (isPaused) {
                appendLine("[Task Resumed] The user paused and sent a new message.")
                appendLine("Read it carefully — it may contain corrections or extra context.")
                appendLine("Continue from where you left off. Do NOT repeat previous work.")
            } else {
                appendLine(flowRules)
            }
            appendLine(transitionRules)
        }.trim()
    }

    fun reset() {
        phase = TaskPhase.IDLE
        isPaused = false
        steps.clear()
        currentStepIndex = 0
        taskDescription = ""
        isExtracting = false
        lastRejection = null
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
            lang: Lang = Lang.EN,
            currentPhase: TaskPhase = TaskPhase.IDLE
        ): ExtractedTaskState? {
            if (conversationHistory.size < 2) return null

            // Short-response heuristic: if we're in IDLE and the last assistant reply
            // is brief, it's simple Q&A — skip the extractor entirely.
            if (currentPhase == TaskPhase.IDLE) {
                val lastAssistant = conversationHistory.lastOrNull { it.role == "assistant" }
                if (lastAssistant != null && lastAssistant.content.length < 200) {
                    return ExtractedTaskState(
                        phase = TaskPhase.IDLE,
                        steps = emptyList(),
                        currentStepIndex = 0,
                        taskDescription = ""
                    )
                }
            }

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
                    val content = msg.content
                    // Show beginning + end of long messages so we can detect completion
                    val truncated = if (content.length > 1000) {
                        content.take(600) + "\n...[truncated]...\n" + content.takeLast(400)
                    } else {
                        content
                    }
                    appendLine("${msg.role}: $truncated")
                }
                appendLine()
                appendLine("CRITICAL PHASE RULES:")
                appendLine("- idle: casual conversation, greetings, simple Q&A with no multi-step task")
                appendLine("- planning: assistant ONLY stated an approach but did NOT start work yet")
                appendLine("- execution: assistant is actively doing the work")
                appendLine("- validation: assistant is reviewing/verifying the result")
                appendLine("- done: assistant provided a complete answer or finished the task")
                appendLine()
                appendLine("IMPORTANT: Detect the LAST phase the assistant reached in their response:")
                appendLine("- Short answers (math, facts, simple questions) → idle (NOT planning, NOT done)")
                appendLine("- If the assistant provided a complete answer to a COMPLEX task → done")
                appendLine("- If the response contains a plan AND actual work → execution (NOT planning)")
                appendLine("- If the response contains work AND a conclusion → done")
                appendLine("- planning is ONLY when the assistant outlined steps for a COMPLEX multi-step task but did NOT execute any")
                appendLine("- When in doubt between planning and done, choose idle if the task is simple")
                appendLine("- Most single-response answers are idle or done, NEVER planning")
                appendLine("- idle is the DEFAULT — only use other phases for genuinely complex, multi-step tasks")
                appendLine()
                appendLine("Return JSON only:")
                appendLine("""{"task_description":"...","phase":"...","steps":["..."],"current_step":0,"completed_steps":[0]}""")
                appendLine("If idle, return {\"phase\":\"idle\"}")
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

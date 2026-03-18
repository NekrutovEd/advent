package mcp.server

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintStream
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.fixedRateTimer

/**
 * MCP server for scheduled/periodic tasks.
 * Communicates via JSON-RPC 2.0 over stdin/stdout.
 *
 * Supported tools: schedule_once, schedule_recurring, list_tasks,
 * cancel_task, get_results, get_summary.
 *
 * Data is persisted as JSON in ~/.ai-advent/scheduler-data.json.
 */
fun main() {
    val storageDir = System.getProperty("user.home") + "/.ai-advent"
    val server = SchedulerMcpServer(TaskStore(File(storageDir, "scheduler-data.json")))
    server.run()
}

// ───── Data model ─────

enum class TaskType { REMINDER, GIT_STATUS, CUSTOM_COMMAND }
enum class TaskScheduleType { ONCE, RECURRING }
enum class TaskStatus { PENDING, RUNNING, COMPLETED, CANCELLED }

data class ScheduledTask(
    val id: String = UUID.randomUUID().toString().take(8),
    val type: TaskType,
    val scheduleType: TaskScheduleType,
    val description: String,
    val actionPrompt: String = "",     // clean prompt sent to AI when task fires (what to DO, not the original request)
    val payload: String = "",          // extra data: command, repo path, reminder text
    val intervalMs: Long = 0,          // for recurring
    val delayMs: Long = 0,             // for once
    val chatId: String = "",           // target chat for notification delivery
    val sessionId: String = "",        // target session for notification delivery
    val createdAt: Long = System.currentTimeMillis(),
    var nextRunAt: Long = System.currentTimeMillis() + if (scheduleType == TaskScheduleType.ONCE) delayMs else intervalMs,
    var status: TaskStatus = TaskStatus.PENDING,
    val results: MutableList<TaskResult> = mutableListOf()
)

data class TaskResult(
    val timestamp: Long = System.currentTimeMillis(),
    val output: String,
    val isError: Boolean = false
)

// ───── JSON persistence ─────

class TaskStore(private val file: File) {
    private val tasks = ConcurrentHashMap<String, ScheduledTask>()

    init { load() }

    fun all(): List<ScheduledTask> = tasks.values.toList()
    fun get(id: String): ScheduledTask? = tasks[id]
    fun put(task: ScheduledTask) { tasks[task.id] = task; save() }
    fun remove(id: String): Boolean { val removed = tasks.remove(id) != null; if (removed) save(); return removed }

    fun save() {
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        for (task in tasks.values) {
            arr.put(taskToJson(task))
        }
        file.writeText(arr.toString(2))
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val t = jsonToTask(arr.getJSONObject(i))
                tasks[t.id] = t
            }
        } catch (_: Exception) {}
    }

    companion object {
        fun taskToJson(t: ScheduledTask): JSONObject = JSONObject()
            .put("id", t.id)
            .put("type", t.type.name)
            .put("scheduleType", t.scheduleType.name)
            .put("description", t.description)
            .put("actionPrompt", t.actionPrompt)
            .put("payload", t.payload)
            .put("intervalMs", t.intervalMs)
            .put("delayMs", t.delayMs)
            .put("chatId", t.chatId)
            .put("sessionId", t.sessionId)
            .put("createdAt", t.createdAt)
            .put("nextRunAt", t.nextRunAt)
            .put("status", t.status.name)
            .put("results", JSONArray().apply {
                t.results.forEach { r ->
                    put(JSONObject()
                        .put("timestamp", r.timestamp)
                        .put("output", r.output)
                        .put("isError", r.isError))
                }
            })

        fun jsonToTask(j: JSONObject): ScheduledTask {
            val results = mutableListOf<TaskResult>()
            val arr = j.optJSONArray("results")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    results.add(TaskResult(
                        timestamp = r.getLong("timestamp"),
                        output = r.getString("output"),
                        isError = r.optBoolean("isError", false)
                    ))
                }
            }
            val scheduleType = TaskScheduleType.valueOf(j.getString("scheduleType"))
            val delayMs = j.optLong("delayMs", 0)
            return ScheduledTask(
                id = j.getString("id"),
                type = TaskType.valueOf(j.getString("type")),
                scheduleType = scheduleType,
                description = j.getString("description"),
                actionPrompt = j.optString("actionPrompt", ""),
                payload = j.optString("payload", ""),
                intervalMs = j.optLong("intervalMs", 0),
                delayMs = delayMs,
                chatId = j.optString("chatId", ""),
                sessionId = j.optString("sessionId", ""),
                createdAt = j.getLong("createdAt"),
                nextRunAt = j.optLong("nextRunAt", System.currentTimeMillis()),
                status = TaskStatus.valueOf(j.optString("status", "PENDING")),
                results = results
            )
        }
    }
}

// ───── Task executor ─────

data class Notification(
    val taskId: String,
    val taskDescription: String,
    val taskType: TaskType,
    val timestamp: Long = System.currentTimeMillis(),
    val output: String,
    val isError: Boolean = false,
    val chatId: String = "",
    val sessionId: String = ""
)

class TaskExecutor(private val store: TaskStore) {
    private val timers = ConcurrentHashMap<String, Timer>()
    private val pendingNotifications = java.util.concurrent.ConcurrentLinkedQueue<Notification>()

    fun drainNotifications(): List<Notification> {
        val result = mutableListOf<Notification>()
        while (true) {
            result.add(pendingNotifications.poll() ?: break)
        }
        return result
    }

    fun startAll() {
        for (task in store.all()) {
            if (task.status == TaskStatus.PENDING || task.status == TaskStatus.RUNNING) {
                scheduleTimer(task)
            }
        }
    }

    fun schedule(task: ScheduledTask) {
        store.put(task)
        scheduleTimer(task)
    }

    fun cancel(taskId: String): Boolean {
        timers.remove(taskId)?.cancel()
        val task = store.get(taskId) ?: return false
        task.status = TaskStatus.CANCELLED
        store.put(task)
        return true
    }

    private fun scheduleTimer(task: ScheduledTask) {
        timers.remove(task.id)?.cancel()

        when (task.scheduleType) {
            TaskScheduleType.ONCE -> {
                val delay = (task.nextRunAt - System.currentTimeMillis()).coerceAtLeast(0)
                val timer = Timer("task-${task.id}", true)
                timer.schedule(object : TimerTask() {
                    override fun run() { executeTask(task) }
                }, delay)
                timers[task.id] = timer
            }
            TaskScheduleType.RECURRING -> {
                val delay = (task.nextRunAt - System.currentTimeMillis()).coerceAtLeast(0)
                val timer = Timer("task-${task.id}", true)
                timer.scheduleAtFixedRate(object : TimerTask() {
                    override fun run() { executeTask(task) }
                }, delay, task.intervalMs)
                timers[task.id] = timer
            }
        }
    }

    internal fun executeTask(task: ScheduledTask) {
        task.status = TaskStatus.RUNNING
        val result = when (task.type) {
            TaskType.REMINDER -> TaskResult(
                output = "REMINDER: ${task.description}" +
                    if (task.payload.isNotBlank()) "\n${task.payload}" else ""
            )
            TaskType.GIT_STATUS -> executeGitStatus(task.payload)
            TaskType.CUSTOM_COMMAND -> executeCommand(task.payload)
        }
        task.results.add(result)
        task.nextRunAt = System.currentTimeMillis() + task.intervalMs

        // Queue notification for UI polling
        // actionPrompt is the clean instruction for the AI; fall back to description if not set
        val deliveryPrompt = task.actionPrompt.ifBlank { task.description }
        pendingNotifications.add(Notification(
            taskId = task.id,
            taskDescription = deliveryPrompt,
            taskType = task.type,
            output = result.output,
            isError = result.isError,
            chatId = task.chatId,
            sessionId = task.sessionId
        ))

        if (task.scheduleType == TaskScheduleType.ONCE) {
            task.status = TaskStatus.COMPLETED
            timers.remove(task.id)?.cancel()
        }
        store.save()
    }

    private fun executeGitStatus(repoPath: String): TaskResult {
        return try {
            val dir = File(repoPath)
            if (!dir.exists()) return TaskResult(output = "Repository not found: $repoPath", isError = true)
            val pb = ProcessBuilder("git", "status", "--short", "--branch")
            pb.directory(dir)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader(Charsets.UTF_8).readText().trimEnd()
            proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            val exitCode = if (proc.isAlive) { proc.destroyForcibly(); -1 } else proc.exitValue()
            TaskResult(
                output = "Git status for $repoPath:\n$output",
                isError = exitCode != 0
            )
        } catch (e: Exception) {
            TaskResult(output = "Error checking git status: ${e.message}", isError = true)
        }
    }

    private fun executeCommand(command: String): TaskResult {
        return try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val cmd = if (isWindows) listOf("cmd", "/c", command) else listOf("sh", "-c", command)
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader(Charsets.UTF_8).readText().trimEnd()
            proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            val exitCode = if (proc.isAlive) { proc.destroyForcibly(); -1 } else proc.exitValue()
            TaskResult(output = output, isError = exitCode != 0)
        } catch (e: Exception) {
            TaskResult(output = "Error executing command: ${e.message}", isError = true)
        }
    }

    fun stopAll() {
        timers.values.forEach { it.cancel() }
        timers.clear()
    }
}

// ───── MCP Server ─────

class SchedulerMcpServer(internal val store: TaskStore) {
    internal val executor = TaskExecutor(store)

    private val tools = listOf(
        toolDef(
            "schedule_once", "Schedule a one-time task (reminder, git check, or command) after a delay. Result is delivered as an AI message into the specified chat.",
            prop("type", "string", "Task type: REMINDER, GIT_STATUS, or CUSTOM_COMMAND"),
            prop("description", "string", "Short human-readable label for this task (e.g. 'Tell a joke')"),
            prop("action_prompt", "string", "The exact prompt/instruction to send to the AI when the task fires. Must be a DIRECT instruction to perform the action (e.g. 'Расскажи анекдот'), NOT the original user request (e.g. NOT 'расскажи анекдот через минуту'). The AI receiving this prompt will execute it immediately without scheduling anything."),
            prop("delay_seconds", "integer", "Delay in seconds before execution (minimum 1)"),
            prop("chat_id", "string", "Target chat ID for delivering the result"),
            prop("session_id", "string", "Target session ID for delivering the result"),
            prop("payload", "string", "Extra data: reminder text, repo path, or shell command", required = false)
        ),
        toolDef(
            "schedule_recurring", "Schedule a recurring task that runs periodically. Result is delivered as an AI message into the specified chat.",
            prop("type", "string", "Task type: REMINDER, GIT_STATUS, or CUSTOM_COMMAND"),
            prop("description", "string", "Short human-readable label for this task (e.g. 'Git status check')"),
            prop("action_prompt", "string", "The exact prompt/instruction to send to the AI each time the task fires. Must be a DIRECT instruction (e.g. 'Расскажи анекдот'), NOT the original scheduling request."),
            prop("interval_seconds", "integer", "Interval in seconds between runs (minimum 1)"),
            prop("chat_id", "string", "Target chat ID for delivering the result"),
            prop("session_id", "string", "Target session ID for delivering the result"),
            prop("payload", "string", "Extra data: reminder text, repo path, or shell command", required = false)
        ),
        toolDef(
            "list_tasks", "List all scheduled tasks with their status",
            prop("status", "string", "Filter by status: PENDING, RUNNING, COMPLETED, CANCELLED, or ALL (default ALL)", required = false)
        ),
        toolDef(
            "cancel_task", "Cancel a scheduled task by ID",
            prop("task_id", "string", "The task ID to cancel")
        ),
        toolDef(
            "get_results", "Get execution results for a specific task",
            prop("task_id", "string", "The task ID to get results for"),
            prop("last_n", "integer", "Only return last N results (default: all)", required = false)
        ),
        toolDef(
            "get_summary", "Get an aggregated summary of all task results within a time period",
            prop("hours", "integer", "Look back this many hours (default: 24)", required = false),
            prop("type", "string", "Filter by task type: REMINDER, GIT_STATUS, CUSTOM_COMMAND, or ALL (default ALL)", required = false)
        ),
        toolDef(
            "poll_notifications", "Poll for new task execution notifications (call periodically to receive alerts)"
        )
    )

    fun run() {
        executor.startAll()
        val utf8Out = PrintStream(System.out, true, "UTF-8")
        val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val response = handleMessage(line)
                if (response != null) {
                    utf8Out.println(response)
                    utf8Out.flush()
                }
            }
        } finally {
            executor.stopAll()
        }
    }

    internal fun handleMessage(line: String): String? {
        val msg = try {
            JSONObject(line)
        } catch (_: Exception) {
            return errorResponse(null, -32700, "Parse error")
        }
        val id = msg.opt("id")
        val method = msg.optString("method", "")
        val params = msg.optJSONObject("params") ?: JSONObject()

        if (id == null || id == JSONObject.NULL) return null

        return when (method) {
            "initialize" -> handleInitialize(id)
            "tools/list" -> handleListTools(id)
            "tools/call" -> handleCallTool(id, params)
            else -> errorResponse(id, -32601, "Method not found: $method")
        }
    }

    private fun handleInitialize(id: Any): String {
        val result = JSONObject()
            .put("protocolVersion", "2024-11-05")
            .put("capabilities", JSONObject().put("tools", JSONObject()))
            .put("serverInfo", JSONObject()
                .put("name", "Scheduler MCP Server")
                .put("version", "1.0.0"))
        return successResponse(id, result)
    }

    private fun handleListTools(id: Any): String {
        val toolsArray = JSONArray()
        for (tool in tools) toolsArray.put(tool)
        return successResponse(id, JSONObject().put("tools", toolsArray))
    }

    private fun handleCallTool(id: Any, params: JSONObject): String {
        val name = params.optString("name", "")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val result = executeTool(name, args)
        return successResponse(id, result)
    }

    internal fun executeTool(name: String, args: JSONObject): JSONObject {
        return try {
            when (name) {
                "schedule_once" -> execScheduleOnce(args)
                "schedule_recurring" -> execScheduleRecurring(args)
                "list_tasks" -> execListTasks(args)
                "cancel_task" -> execCancelTask(args)
                "get_results" -> execGetResults(args)
                "get_summary" -> execGetSummary(args)
                "poll_notifications" -> execPollNotifications()
                else -> toolError("Unknown tool: $name")
            }
        } catch (e: Exception) {
            toolError("Error: ${e.message}")
        }
    }

    // ───── Tool implementations ─────

    private fun execScheduleOnce(args: JSONObject): JSONObject {
        val type = parseTaskType(args.getString("type")) ?: return toolError("Invalid type. Use: REMINDER, GIT_STATUS, CUSTOM_COMMAND")
        val description = args.getString("description")
        val actionPrompt = args.optString("action_prompt", "")
        val delaySeconds = args.getInt("delay_seconds")
        val payload = args.optString("payload", "")
        val chatId = args.optString("chat_id", "")
        val sessionId = args.optString("session_id", "")

        if (delaySeconds < 1) return toolError("delay_seconds must be >= 1")

        val delayMs = delaySeconds.toLong() * 1000
        val now = System.currentTimeMillis()
        val task = ScheduledTask(
            type = type,
            scheduleType = TaskScheduleType.ONCE,
            description = description,
            actionPrompt = actionPrompt,
            payload = payload,
            chatId = chatId,
            sessionId = sessionId,
            delayMs = delayMs,
            createdAt = now,
            nextRunAt = now + delayMs
        )
        executor.schedule(task)
        return toolResult("Scheduled one-time ${type.name} task '${description}' (id: ${task.id}) — runs in $delaySeconds seconds")
    }

    private fun execScheduleRecurring(args: JSONObject): JSONObject {
        val type = parseTaskType(args.getString("type")) ?: return toolError("Invalid type. Use: REMINDER, GIT_STATUS, CUSTOM_COMMAND")
        val description = args.getString("description")
        val actionPrompt = args.optString("action_prompt", "")
        val intervalSeconds = args.getInt("interval_seconds")
        val payload = args.optString("payload", "")
        val chatId = args.optString("chat_id", "")
        val sessionId = args.optString("session_id", "")

        if (intervalSeconds < 1) return toolError("interval_seconds must be >= 1")

        val intervalMs = intervalSeconds.toLong() * 1000
        val now = System.currentTimeMillis()
        val task = ScheduledTask(
            type = type,
            scheduleType = TaskScheduleType.RECURRING,
            description = description,
            actionPrompt = actionPrompt,
            payload = payload,
            chatId = chatId,
            sessionId = sessionId,
            intervalMs = intervalMs,
            createdAt = now,
            nextRunAt = now + intervalMs
        )
        executor.schedule(task)
        return toolResult("Scheduled recurring ${type.name} task '${description}' (id: ${task.id}) — every $intervalSeconds seconds")
    }

    private fun execListTasks(args: JSONObject): JSONObject {
        val statusFilter = args.optString("status", "ALL").uppercase()
        val tasks = store.all().let { all ->
            if (statusFilter == "ALL") all
            else {
                val s = try { TaskStatus.valueOf(statusFilter) } catch (_: Exception) { return toolError("Invalid status filter") }
                all.filter { it.status == s }
            }
        }.sortedByDescending { it.createdAt }

        if (tasks.isEmpty()) return toolResult("No tasks found" + if (statusFilter != "ALL") " with status $statusFilter" else "")

        val sb = StringBuilder("Tasks (${tasks.size}):\n\n")
        for (t in tasks) {
            sb.append("  [${t.id}] ${t.status.name} | ${t.type.name} | ${t.scheduleType.name}\n")
            sb.append("    Description: ${t.description}\n")
            if (t.actionPrompt.isNotBlank()) sb.append("    Action prompt: ${t.actionPrompt}\n")
            if (t.payload.isNotBlank()) sb.append("    Payload: ${t.payload}\n")
            if (t.scheduleType == TaskScheduleType.RECURRING) {
                sb.append("    Interval: ${t.intervalMs / 1000}s\n")
            }
            sb.append("    Results: ${t.results.size}\n")
            sb.append("    Created: ${formatTime(t.createdAt)}\n\n")
        }
        return toolResult(sb.toString().trimEnd())
    }

    private fun execCancelTask(args: JSONObject): JSONObject {
        val taskId = args.getString("task_id")
        return if (executor.cancel(taskId)) {
            toolResult("Task $taskId cancelled successfully")
        } else {
            toolError("Task $taskId not found")
        }
    }

    private fun execGetResults(args: JSONObject): JSONObject {
        val taskId = args.getString("task_id")
        val lastN = args.optInt("last_n", 0)
        val task = store.get(taskId) ?: return toolError("Task $taskId not found")

        val results = if (lastN > 0) task.results.takeLast(lastN) else task.results
        if (results.isEmpty()) return toolResult("No results yet for task '${task.description}' (${task.id})")

        val sb = StringBuilder("Results for '${task.description}' (${task.id}), showing ${results.size}/${task.results.size}:\n\n")
        for ((i, r) in results.withIndex()) {
            sb.append("--- Run ${i + 1} at ${formatTime(r.timestamp)} ${if (r.isError) "[ERROR]" else "[OK]"} ---\n")
            sb.append(r.output)
            sb.append("\n\n")
        }
        return toolResult(sb.toString().trimEnd())
    }

    private fun execGetSummary(args: JSONObject): JSONObject {
        val hours = args.optInt("hours", 24)
        val typeFilter = args.optString("type", "ALL").uppercase()
        val cutoff = System.currentTimeMillis() - hours.toLong() * 3600 * 1000

        val tasks = store.all().let { all ->
            if (typeFilter == "ALL") all
            else {
                val t = parseTaskType(typeFilter) ?: return toolError("Invalid type filter")
                all.filter { it.type == t }
            }
        }

        val recentResults = tasks.flatMap { task ->
            task.results.filter { it.timestamp >= cutoff }.map { task to it }
        }.sortedByDescending { it.second.timestamp }

        val totalTasks = tasks.size
        val activeTasks = tasks.count { it.status == TaskStatus.PENDING || it.status == TaskStatus.RUNNING }
        val completedTasks = tasks.count { it.status == TaskStatus.COMPLETED }
        val cancelledTasks = tasks.count { it.status == TaskStatus.CANCELLED }
        val totalResults = recentResults.size
        val errors = recentResults.count { it.second.isError }

        val sb = StringBuilder()
        sb.append("=== Scheduler Summary (last ${hours}h) ===\n\n")
        sb.append("Tasks: $totalTasks total ($activeTasks active, $completedTasks completed, $cancelledTasks cancelled)\n")
        sb.append("Executions: $totalResults in the last ${hours}h ($errors errors)\n\n")

        if (recentResults.isNotEmpty()) {
            sb.append("--- Recent executions ---\n\n")
            for ((task, result) in recentResults.take(20)) {
                val status = if (result.isError) "ERROR" else "OK"
                sb.append("[${formatTime(result.timestamp)}] ${task.type.name}: ${task.description} — $status\n")
                val outputPreview = result.output.take(200)
                sb.append("  $outputPreview\n\n")
            }
        }

        // Upcoming scheduled tasks
        val upcoming = tasks.filter { it.status == TaskStatus.PENDING || it.status == TaskStatus.RUNNING }
            .sortedBy { it.nextRunAt }
        if (upcoming.isNotEmpty()) {
            sb.append("--- Upcoming ---\n\n")
            for (t in upcoming.take(10)) {
                val inSeconds = ((t.nextRunAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                sb.append("[${t.id}] ${t.type.name}: ${t.description} — in ~${inSeconds}s\n")
            }
        }

        return toolResult(sb.toString().trimEnd())
    }

    private fun execPollNotifications(): JSONObject {
        val notifications = executor.drainNotifications()
        if (notifications.isEmpty()) return toolResult("")

        val arr = JSONArray()
        for (n in notifications) {
            arr.put(JSONObject()
                .put("taskId", n.taskId)
                .put("taskType", n.taskType.name)
                .put("description", n.taskDescription)
                .put("output", n.output)
                .put("chatId", n.chatId)
                .put("sessionId", n.sessionId)
                .put("isError", n.isError)
                .put("timestamp", n.timestamp))
        }
        return toolResult(arr.toString())
    }

    // ───── Helpers ─────

    private fun parseTaskType(s: String): TaskType? = try { TaskType.valueOf(s.uppercase()) } catch (_: Exception) { null }

    private fun formatTime(ms: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        return String.format(
            "%04d-%02d-%02d %02d:%02d:%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND)
        )
    }

    // ───── JSON-RPC helpers (same pattern as GitMcpServer) ─────

    private fun successResponse(id: Any, result: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", result)
            .toString()

    private fun errorResponse(id: Any?, code: Int, message: String): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString()

    companion object {
        fun toolResult(text: String, isError: Boolean = false): JSONObject =
            JSONObject()
                .put("content", JSONArray().put(JSONObject()
                    .put("type", "text")
                    .put("text", text)))
                .put("isError", isError)

        fun toolError(message: String): JSONObject = toolResult(message, isError = true)

        data class PropDef(
            val name: String,
            val type: String,
            val description: String,
            val required: Boolean = true
        )

        fun prop(name: String, type: String, description: String, required: Boolean = true) =
            PropDef(name, type, description, required)

        fun toolDef(name: String, description: String, vararg props: PropDef): JSONObject {
            val properties = JSONObject()
            val requiredArr = JSONArray()
            for (p in props) {
                properties.put(p.name, JSONObject()
                    .put("type", p.type)
                    .put("description", p.description))
                if (p.required) requiredArr.put(p.name)
            }
            val schema = JSONObject()
                .put("type", "object")
                .put("properties", properties)
            if (requiredArr.length() > 0) schema.put("required", requiredArr)

            return JSONObject()
                .put("name", name)
                .put("description", description)
                .put("inputSchema", schema)
        }
    }
}

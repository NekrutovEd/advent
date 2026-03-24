package mcp.server

import org.json.JSONObject
import java.io.File
import kotlin.test.*

class SchedulerMcpServerTest {

    private fun createServer(): Pair<SchedulerMcpServer, File> {
        val tmpFile = File.createTempFile("scheduler-test-", ".json")
        tmpFile.deleteOnExit()
        tmpFile.writeText("[]")
        val store = TaskStore(tmpFile)
        return SchedulerMcpServer(store) to tmpFile
    }

    // ───── JSON-RPC protocol tests ─────

    @Test
    fun `initialize returns server info`() {
        val (server, file) = createServer()
        try {
            val request = jsonRpc(1, "initialize", JSONObject())
            val response = server.handleMessage(request)!!
            val json = JSONObject(response)
            assertEquals("2.0", json.getString("jsonrpc"))
            val result = json.getJSONObject("result")
            assertEquals("2024-11-05", result.getString("protocolVersion"))
            val serverInfo = result.getJSONObject("serverInfo")
            assertEquals("Scheduler MCP Server", serverInfo.getString("name"))
            assertEquals("1.0.0", serverInfo.getString("version"))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `tools list returns all scheduler tools`() {
        val (server, file) = createServer()
        try {
            val request = jsonRpc(2, "tools/list", JSONObject())
            val response = server.handleMessage(request)!!
            val json = JSONObject(response)
            val tools = json.getJSONObject("result").getJSONArray("tools")
            assertEquals(7, tools.length(), "Should have 7 scheduler tools")

            val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
            assertContains(names, "schedule_once")
            assertContains(names, "schedule_recurring")
            assertContains(names, "list_tasks")
            assertContains(names, "cancel_task")
            assertContains(names, "get_results")
            assertContains(names, "get_summary")
            assertContains(names, "poll_notifications")
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `notification returns null`() {
        val (server, file) = createServer()
        try {
            val notification = JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", "notifications/initialized")
                .toString()
            assertNull(server.handleMessage(notification))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `unknown method returns error`() {
        val (server, file) = createServer()
        try {
            val request = jsonRpc(99, "unknown/method", JSONObject())
            val response = server.handleMessage(request)!!
            val json = JSONObject(response)
            assertTrue(json.has("error"))
            assertEquals(-32601, json.getJSONObject("error").getInt("code"))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `invalid JSON returns parse error`() {
        val (server, file) = createServer()
        try {
            val response = server.handleMessage("not json")!!
            val json = JSONObject(response)
            assertTrue(json.has("error"))
            assertEquals(-32700, json.getJSONObject("error").getInt("code"))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    // ───── schedule_once tests ─────

    @Test
    fun `schedule_once creates a reminder task`() {
        val (server, file) = createServer()
        try {
            val result = server.executeTool("schedule_once", JSONObject()
                .put("type", "REMINDER")
                .put("description", "Test reminder")
                .put("delay_seconds", 5)
                .put("payload", "Don't forget!"))
            assertFalse(result.getBoolean("isError"), extractText(result))
            val text = extractText(result)
            assertTrue(text.contains("Scheduled one-time REMINDER"))
            assertTrue(text.contains("Test reminder"))
            assertTrue(text.contains("5 seconds"))

            // Verify it's in the store
            val tasks = server.store.all()
            assertEquals(1, tasks.size)
            assertEquals(TaskType.REMINDER, tasks[0].type)
            assertEquals(TaskScheduleType.ONCE, tasks[0].scheduleType)
            assertEquals("Test reminder", tasks[0].description)
            assertEquals("Don't forget!", tasks[0].payload)
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `schedule_once stores chat_id and session_id`() {
        val (server, file) = createServer()
        try {
            val result = server.executeTool("schedule_once", JSONObject()
                .put("type", "REMINDER")
                .put("description", "Targeted reminder")
                .put("delay_seconds", 5)
                .put("chat_id", "c1")
                .put("session_id", "s1"))
            assertFalse(result.getBoolean("isError"))

            val task = server.store.all()[0]
            assertEquals("c1", task.chatId)
            assertEquals("s1", task.sessionId)
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `schedule_once rejects zero or negative delay`() {
        val (server, file) = createServer()
        try {
            val result = server.executeTool("schedule_once", JSONObject()
                .put("type", "REMINDER")
                .put("description", "Bad")
                .put("delay_seconds", 0))
            assertTrue(result.getBoolean("isError"))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `schedule_once rejects invalid type`() {
        val (server, file) = createServer()
        try {
            val result = server.executeTool("schedule_once", JSONObject()
                .put("type", "INVALID")
                .put("description", "Bad")
                .put("delay_seconds", 1))
            assertTrue(result.getBoolean("isError"))
            assertTrue(extractText(result).contains("Invalid type"))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    // ───── schedule_recurring tests ─────

    @Test
    fun `schedule_recurring creates a git status task`() {
        val (server, file) = createServer()
        try {
            val result = server.executeTool("schedule_recurring", JSONObject()
                .put("type", "GIT_STATUS")
                .put("description", "Check repo status")
                .put("interval_seconds", 30)
                .put("payload", "/tmp/myrepo"))
            assertFalse(result.getBoolean("isError"), extractText(result))
            val text = extractText(result)
            assertTrue(text.contains("recurring GIT_STATUS"))
            assertTrue(text.contains("every 30 seconds"))

            val tasks = server.store.all()
            assertEquals(1, tasks.size)
            assertEquals(TaskScheduleType.RECURRING, tasks[0].scheduleType)
            assertEquals(30L * 1000, tasks[0].intervalMs)
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `schedule_recurring rejects zero interval`() {
        val (server, file) = createServer()
        try {
            val result = server.executeTool("schedule_recurring", JSONObject()
                .put("type", "REMINDER")
                .put("description", "Bad")
                .put("interval_seconds", 0))
            assertTrue(result.getBoolean("isError"))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    // ───── list_tasks tests ─────

    @Test
    fun `list_tasks shows empty when no tasks`() {
        val (server, file) = createServer()
        try {
            val result = server.executeTool("list_tasks", JSONObject())
            assertFalse(result.getBoolean("isError"))
            assertTrue(extractText(result).contains("No tasks found"))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `list_tasks shows created tasks`() {
        val (server, file) = createServer()
        try {
            server.executeTool("schedule_once", JSONObject()
                .put("type", "REMINDER")
                .put("description", "Task A")
                .put("delay_seconds", 10))
            server.executeTool("schedule_recurring", JSONObject()
                .put("type", "CUSTOM_COMMAND")
                .put("description", "Task B")
                .put("interval_seconds", 5)
                .put("payload", "echo hello"))

            val result = server.executeTool("list_tasks", JSONObject())
            assertFalse(result.getBoolean("isError"))
            val text = extractText(result)
            assertTrue(text.contains("Tasks (2)"))
            assertTrue(text.contains("Task A"))
            assertTrue(text.contains("Task B"))
            assertTrue(text.contains("REMINDER"))
            assertTrue(text.contains("CUSTOM_COMMAND"))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `list_tasks filters by status`() {
        val (server, file) = createServer()
        try {
            server.executeTool("schedule_once", JSONObject()
                .put("type", "REMINDER")
                .put("description", "Active task")
                .put("delay_seconds", 60))

            val result = server.executeTool("list_tasks", JSONObject().put("status", "COMPLETED"))
            assertFalse(result.getBoolean("isError"))
            assertTrue(extractText(result).contains("No tasks found"))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    // ───── cancel_task tests ─────

    @Test
    fun `cancel_task cancels existing task`() {
        val (server, file) = createServer()
        try {
            server.executeTool("schedule_once", JSONObject()
                .put("type", "REMINDER")
                .put("description", "Cancel me")
                .put("delay_seconds", 60))

            val taskId = server.store.all()[0].id

            val result = server.executeTool("cancel_task", JSONObject().put("task_id", taskId))
            assertFalse(result.getBoolean("isError"))
            assertTrue(extractText(result).contains("cancelled"))

            assertEquals(TaskStatus.CANCELLED, server.store.get(taskId)!!.status)
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `cancel_task returns error for unknown id`() {
        val (server, file) = createServer()
        try {
            val result = server.executeTool("cancel_task", JSONObject().put("task_id", "nonexistent"))
            assertTrue(result.getBoolean("isError"))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    // ───── get_results tests ─────

    @Test
    fun `get_results shows no results initially`() {
        val (server, file) = createServer()
        try {
            server.executeTool("schedule_once", JSONObject()
                .put("type", "REMINDER")
                .put("description", "My reminder")
                .put("delay_seconds", 60))
            val taskId = server.store.all()[0].id

            val result = server.executeTool("get_results", JSONObject().put("task_id", taskId))
            assertFalse(result.getBoolean("isError"))
            assertTrue(extractText(result).contains("No results yet"))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `get_results shows results after execution`() {
        val (server, file) = createServer()
        try {
            server.executeTool("schedule_once", JSONObject()
                .put("type", "REMINDER")
                .put("description", "Executed reminder")
                .put("delay_seconds", 999))
            val task = server.store.all()[0]

            // Manually trigger execution
            server.executor.executeTask(task)

            val result = server.executeTool("get_results", JSONObject().put("task_id", task.id))
            assertFalse(result.getBoolean("isError"))
            val text = extractText(result)
            assertTrue(text.contains("REMINDER: Executed reminder"))
            assertTrue(text.contains("[OK]"))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `get_results with last_n limits output`() {
        val (server, file) = createServer()
        try {
            server.executeTool("schedule_recurring", JSONObject()
                .put("type", "REMINDER")
                .put("description", "Repeated")
                .put("interval_seconds", 1))
            val task = server.store.all()[0]

            // Execute 3 times
            repeat(3) { server.executor.executeTask(task) }

            val result = server.executeTool("get_results", JSONObject()
                .put("task_id", task.id)
                .put("last_n", 2))
            assertFalse(result.getBoolean("isError"))
            val text = extractText(result)
            assertTrue(text.contains("showing 2/3"))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `get_results returns error for unknown task`() {
        val (server, file) = createServer()
        try {
            val result = server.executeTool("get_results", JSONObject().put("task_id", "nope"))
            assertTrue(result.getBoolean("isError"))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    // ───── get_summary tests ─────

    @Test
    fun `get_summary returns overview`() {
        val (server, file) = createServer()
        try {
            // Create some tasks with large delays so timers don't fire during test
            server.executeTool("schedule_once", JSONObject()
                .put("type", "REMINDER")
                .put("description", "Reminder 1")
                .put("delay_seconds", 999))
            server.executeTool("schedule_recurring", JSONObject()
                .put("type", "CUSTOM_COMMAND")
                .put("description", "Echo task")
                .put("interval_seconds", 10)
                .put("payload", "echo hello"))

            // Manually execute the reminder
            val reminderTask = server.store.all().first { it.type == TaskType.REMINDER }
            server.executor.executeTask(reminderTask)

            val result = server.executeTool("get_summary", JSONObject())
            assertFalse(result.getBoolean("isError"))
            val text = extractText(result)
            assertTrue(text.contains("Scheduler Summary"), "Should contain header, got: $text")
            assertTrue(text.contains("Tasks: 2 total"), "Should show 2 tasks, got: $text")
            assertTrue(text.contains("Executions: 1"), "Should show 1 execution, got: $text")
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `get_summary filters by type`() {
        val (server, file) = createServer()
        try {
            server.executeTool("schedule_once", JSONObject()
                .put("type", "REMINDER")
                .put("description", "R1")
                .put("delay_seconds", 60))
            server.executeTool("schedule_once", JSONObject()
                .put("type", "CUSTOM_COMMAND")
                .put("description", "C1")
                .put("delay_seconds", 60)
                .put("payload", "echo hi"))

            val result = server.executeTool("get_summary", JSONObject().put("type", "REMINDER"))
            assertFalse(result.getBoolean("isError"))
            val text = extractText(result)
            assertTrue(text.contains("Tasks: 1 total"))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    // ───── Task execution tests ─────

    @Test
    fun `reminder task produces correct output`() {
        val (server, file) = createServer()
        try {
            server.executeTool("schedule_once", JSONObject()
                .put("type", "REMINDER")
                .put("description", "Buy milk")
                .put("delay_seconds", 999)
                .put("payload", "From the store"))
            val task = server.store.all()[0]
            server.executor.executeTask(task)

            assertEquals(1, task.results.size)
            assertTrue(task.results[0].output.contains("REMINDER: Buy milk"))
            assertTrue(task.results[0].output.contains("From the store"))
            assertFalse(task.results[0].isError)
            assertEquals(TaskStatus.COMPLETED, task.status)
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `custom command task executes shell command`() {
        val (server, file) = createServer()
        try {
            server.executeTool("schedule_once", JSONObject()
                .put("type", "CUSTOM_COMMAND")
                .put("description", "Echo test")
                .put("delay_seconds", 999)
                .put("payload", "echo scheduler_works"))
            val task = server.store.all()[0]
            server.executor.executeTask(task)

            assertTrue(task.results.size >= 1)
            assertTrue(task.results.any { it.output.contains("scheduler_works") })
            assertTrue(task.results.all { !it.isError })
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `recurring task stays running after execution`() {
        val (server, file) = createServer()
        try {
            server.executeTool("schedule_recurring", JSONObject()
                .put("type", "REMINDER")
                .put("description", "Recurring")
                .put("interval_seconds", 10))
            val task = server.store.all()[0]
            server.executor.executeTask(task)

            // Recurring task should NOT be COMPLETED
            assertEquals(TaskStatus.RUNNING, task.status)
            assertEquals(1, task.results.size)
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    // ───── poll_notifications tests ─────

    @Test
    fun `poll_notifications returns empty when no tasks fired`() {
        val (server, file) = createServer()
        try {
            val result = server.executeTool("poll_notifications", JSONObject())
            assertFalse(result.getBoolean("isError"))
            assertEquals("", extractText(result))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `poll_notifications returns fired task output as JSON`() {
        val (server, file) = createServer()
        try {
            server.executeTool("schedule_once", JSONObject()
                .put("type", "REMINDER")
                .put("description", "Alert me")
                .put("delay_seconds", 999)
                .put("chat_id", "chat123")
                .put("session_id", "sess456")
                .put("payload", "Important!"))
            val task = server.store.all()[0]
            server.executor.executeTask(task)

            val result = server.executeTool("poll_notifications", JSONObject())
            assertFalse(result.getBoolean("isError"))
            val text = extractText(result)
            // Should be a JSON array
            val arr = org.json.JSONArray(text)
            assertEquals(1, arr.length())
            val n = arr.getJSONObject(0)
            assertEquals("REMINDER", n.getString("taskType"))
            assertEquals("Alert me", n.getString("description"))
            assertTrue(n.getString("output").contains("Important!"))
            assertEquals("chat123", n.getString("chatId"))
            assertEquals("sess456", n.getString("sessionId"))
            assertFalse(n.getBoolean("isError"))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `poll_notifications drains queue`() {
        val (server, file) = createServer()
        try {
            server.executeTool("schedule_recurring", JSONObject()
                .put("type", "REMINDER")
                .put("description", "Repeat")
                .put("interval_seconds", 10))
            val task = server.store.all()[0]
            server.executor.executeTask(task)
            server.executor.executeTask(task)

            // First poll gets both
            val result1 = server.executeTool("poll_notifications", JSONObject())
            assertFalse(result1.getBoolean("isError"))
            assertTrue(extractText(result1).isNotBlank())

            // Second poll is empty
            val result2 = server.executeTool("poll_notifications", JSONObject())
            assertFalse(result2.getBoolean("isError"))
            assertEquals("", extractText(result2))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `schedule_once stores action_prompt and uses it in notification`() {
        val (server, file) = createServer()
        try {
            server.executeTool("schedule_once", JSONObject()
                .put("type", "REMINDER")
                .put("description", "Tell a joke in 1 minute")
                .put("action_prompt", "Расскажи анекдот")
                .put("delay_seconds", 999)
                .put("chat_id", "c1")
                .put("session_id", "s1"))
            val task = server.store.all()[0]

            // Verify action_prompt stored
            assertEquals("Расскажи анекдот", task.actionPrompt)
            assertEquals("Tell a joke in 1 minute", task.description)

            // Execute and check notification uses action_prompt
            server.executor.executeTask(task)
            val result = server.executeTool("poll_notifications", JSONObject())
            val arr = org.json.JSONArray(extractText(result))
            val n = arr.getJSONObject(0)
            assertEquals("Расскажи анекдот", n.getString("description"))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `action_prompt falls back to description when empty`() {
        val (server, file) = createServer()
        try {
            server.executeTool("schedule_once", JSONObject()
                .put("type", "REMINDER")
                .put("description", "Fallback test")
                .put("delay_seconds", 999))
            val task = server.store.all()[0]
            server.executor.executeTask(task)

            val result = server.executeTool("poll_notifications", JSONObject())
            val arr = org.json.JSONArray(extractText(result))
            assertEquals("Fallback test", arr.getJSONObject(0).getString("description"))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `action_prompt persists across reload`() {
        val tmpFile = File.createTempFile("scheduler-action-", ".json")
        tmpFile.deleteOnExit()
        tmpFile.writeText("[]")
        try {
            val store1 = TaskStore(tmpFile)
            val server1 = SchedulerMcpServer(store1)
            server1.executeTool("schedule_once", JSONObject()
                .put("type", "REMINDER")
                .put("description", "Joke task")
                .put("action_prompt", "Tell a joke")
                .put("delay_seconds", 999))
            server1.executor.stopAll()

            val store2 = TaskStore(tmpFile)
            val task = store2.all()[0]
            assertEquals("Tell a joke", task.actionPrompt)
        } finally {
            tmpFile.delete()
        }
    }

    // ───── Persistence tests ─────

    @Test
    fun `tasks persist to JSON file`() {
        val tmpFile = File.createTempFile("scheduler-persist-", ".json")
        tmpFile.deleteOnExit()
        tmpFile.writeText("[]")

        try {
            // Create tasks with first server instance
            val store1 = TaskStore(tmpFile)
            val server1 = SchedulerMcpServer(store1)
            server1.executeTool("schedule_once", JSONObject()
                .put("type", "REMINDER")
                .put("description", "Persistent reminder")
                .put("delay_seconds", 60))
            server1.executor.stopAll()

            // Load with second server instance
            val store2 = TaskStore(tmpFile)
            val tasks = store2.all()
            assertEquals(1, tasks.size)
            assertEquals("Persistent reminder", tasks[0].description)
            assertEquals(TaskType.REMINDER, tasks[0].type)
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `results persist across reload`() {
        val tmpFile = File.createTempFile("scheduler-results-", ".json")
        tmpFile.deleteOnExit()
        tmpFile.writeText("[]")

        try {
            val store1 = TaskStore(tmpFile)
            val server1 = SchedulerMcpServer(store1)
            server1.executeTool("schedule_once", JSONObject()
                .put("type", "REMINDER")
                .put("description", "With result")
                .put("delay_seconds", 999))
            val task = store1.all()[0]
            server1.executor.executeTask(task)
            server1.executor.stopAll()

            // Reload
            val store2 = TaskStore(tmpFile)
            val reloaded = store2.all()[0]
            assertEquals(1, reloaded.results.size)
            assertTrue(reloaded.results[0].output.contains("REMINDER: With result"))
        } finally {
            tmpFile.delete()
        }
    }

    // ───── JSON-RPC full roundtrip ─────

    @Test
    fun `tools call via JSON-RPC roundtrip`() {
        val (server, file) = createServer()
        try {
            val params = JSONObject()
                .put("name", "schedule_once")
                .put("arguments", JSONObject()
                    .put("type", "REMINDER")
                    .put("description", "RPC test")
                    .put("delay_seconds", 10))
            val request = jsonRpc(42, "tools/call", params)
            val response = server.handleMessage(request)!!
            val json = JSONObject(response)
            assertEquals(42, json.getInt("id"))
            val result = json.getJSONObject("result")
            assertFalse(result.getBoolean("isError"))
            assertTrue(extractText(result).contains("RPC test"))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    @Test
    fun `unknown tool returns error`() {
        val (server, file) = createServer()
        try {
            val result = server.executeTool("nonexistent", JSONObject())
            assertTrue(result.getBoolean("isError"))
            assertTrue(extractText(result).contains("Unknown tool"))
        } finally {
            server.executor.stopAll()
            file.delete()
        }
    }

    // ───── Helpers ─────

    private fun jsonRpc(id: Int, method: String, params: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
            .toString()

    private fun extractText(result: JSONObject): String =
        result.getJSONArray("content").getJSONObject(0).getString("text")
}

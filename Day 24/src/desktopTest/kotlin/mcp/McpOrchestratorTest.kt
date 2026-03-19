package mcp

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import state.McpOrchestrator
import state.McpServerEntry

class McpOrchestratorTest {

    private fun createOrchestrator(): McpOrchestrator {
        return McpOrchestrator(clientFactory = { FakeMcpClient() })
    }

    @Test
    fun `addServer creates entry with label and command`() {
        val orch = createOrchestrator()
        val entry = orch.addServer("Git", "git-mcp", "--port 8080")

        assertEquals(1, orch.servers.size)
        assertEquals("Git", entry.label)
        assertEquals("git-mcp", entry.serverCommand)
        assertEquals("--port 8080", entry.serverArgs)
        assertFalse(entry.isConnected)
    }

    @Test
    fun `removeServer disconnects and removes`() = runTest {
        val orch = createOrchestrator()
        val entry = orch.addServer("Git", "git-mcp")
        entry.serverCommand = "git-mcp"
        entry.connect()
        assertTrue(entry.isConnected)

        orch.removeServer(entry.id)
        assertEquals(0, orch.servers.size)
        assertFalse(entry.isConnected) // was disconnected
    }

    @Test
    fun `allTools aggregates tools from all connected servers`() = runTest {
        val orch = createOrchestrator()

        // Server 1 with git tools
        val gitEntry = orch.addServer("Git", "git-mcp")
        val gitClient = getClientFromEntry(gitEntry)
        gitClient.toolsToReturn = listOf(
            McpTool("git_status", "Get git status"),
            McpTool("git_log", "Show git log")
        )
        gitEntry.connect()

        // Server 2 with pipeline tools
        val pipeEntry = orch.addServer("Pipeline", "pipe-mcp")
        val pipeClient = getClientFromEntry(pipeEntry)
        pipeClient.toolsToReturn = listOf(
            McpTool("search", "Search files"),
            McpTool("summarize", "Summarize text")
        )
        pipeEntry.connect()

        // Server 3 not connected
        orch.addServer("Scheduler", "sched-mcp")

        val allTools = orch.allTools
        assertEquals(4, allTools.size)
        assertTrue(allTools.any { it.name == "git_status" })
        assertTrue(allTools.any { it.name == "git_log" })
        assertTrue(allTools.any { it.name == "search" })
        assertTrue(allTools.any { it.name == "summarize" })
    }

    @Test
    fun `callTool routes to correct server`() = runTest {
        val orch = createOrchestrator()

        // Server 1: git tools
        val gitEntry = orch.addServer("Git", "git-mcp")
        val gitClient = getClientFromEntry(gitEntry)
        gitClient.toolsToReturn = listOf(McpTool("git_status", "Status"))
        gitClient.callToolResults["git_status"] = McpToolResult("On branch main")
        gitEntry.connect()

        // Server 2: pipeline tools
        val pipeEntry = orch.addServer("Pipeline", "pipe-mcp")
        val pipeClient = getClientFromEntry(pipeEntry)
        pipeClient.toolsToReturn = listOf(McpTool("search", "Search"))
        pipeClient.callToolResults["search"] = McpToolResult("Found 5 results")
        pipeEntry.connect()

        // Route git_status to Git server
        val gitResult = orch.callTool("git_status", "{}")
        assertEquals("On branch main", gitResult.content)

        // Route search to Pipeline server
        val pipeResult = orch.callTool("search", "{}")
        assertEquals("Found 5 results", pipeResult.content)
    }

    @Test
    fun `callTool returns error for unknown tool`() = runTest {
        val orch = createOrchestrator()
        val entry = orch.addServer("Git", "git-mcp")
        val client = getClientFromEntry(entry)
        client.toolsToReturn = listOf(McpTool("git_status", "Status"))
        entry.connect()

        val result = orch.callTool("nonexistent_tool", "{}")
        assertTrue(result.isError)
        assertTrue(result.content.contains("not found"))
    }

    @Test
    fun `connectedCount reflects actual connections`() = runTest {
        val orch = createOrchestrator()

        val entry1 = orch.addServer("Git", "git-mcp")
        val entry2 = orch.addServer("Pipeline", "pipe-mcp")
        orch.addServer("Scheduler", "sched-mcp")

        assertEquals(0, orch.connectedCount)

        entry1.connect()
        assertEquals(1, orch.connectedCount)

        entry2.connect()
        assertEquals(2, orch.connectedCount)

        entry1.disconnect()
        assertEquals(1, orch.connectedCount)
    }

    @Test
    fun `connectAll connects all servers with commands`() = runTest {
        val orch = createOrchestrator()

        orch.addServer("Git", "git-mcp")
        orch.addServer("Pipeline", "pipe-mcp")
        orch.addServer("Empty", "") // no command

        orch.connectAll()

        assertEquals(2, orch.connectedCount)
    }

    @Test
    fun `disconnectAll disconnects all`() = runTest {
        val orch = createOrchestrator()

        val e1 = orch.addServer("Git", "git-mcp")
        val e2 = orch.addServer("Pipeline", "pipe-mcp")
        e1.connect()
        e2.connect()

        assertEquals(2, orch.connectedCount)

        orch.disconnectAll()
        assertEquals(0, orch.connectedCount)
    }

    @Test
    fun `serverForTool finds correct server`() = runTest {
        val orch = createOrchestrator()

        val gitEntry = orch.addServer("Git", "git-mcp")
        val gitClient = getClientFromEntry(gitEntry)
        gitClient.toolsToReturn = listOf(McpTool("git_status", "Status"))
        gitEntry.connect()

        val pipeEntry = orch.addServer("Pipeline", "pipe-mcp")
        val pipeClient = getClientFromEntry(pipeEntry)
        pipeClient.toolsToReturn = listOf(McpTool("search", "Search"))
        pipeEntry.connect()

        assertEquals(gitEntry, orch.serverForTool("git_status"))
        assertEquals(pipeEntry, orch.serverForTool("search"))
        assertNull(orch.serverForTool("unknown"))
    }

    @Test
    fun `cross-server orchestration flow`() = runTest {
        val orch = createOrchestrator()

        // Set up Git server
        val gitEntry = orch.addServer("Git", "git-mcp")
        val gitClient = getClientFromEntry(gitEntry)
        gitClient.toolsToReturn = listOf(
            McpTool("git_status", "Get repository status"),
            McpTool("git_log", "Show commit history")
        )
        gitClient.callToolResults["git_status"] = McpToolResult("Modified: src/main.kt")
        gitClient.callToolResults["git_log"] = McpToolResult("abc123 Fix bug\ndef456 Add feature")
        gitEntry.connect()

        // Set up Pipeline server
        val pipeEntry = orch.addServer("Pipeline", "pipe-mcp")
        val pipeClient = getClientFromEntry(pipeEntry)
        pipeClient.toolsToReturn = listOf(
            McpTool("search", "Search files"),
            McpTool("summarize", "Summarize text"),
            McpTool("save_to_file", "Save to file")
        )
        pipeClient.callToolResults["search"] = McpToolResult("src/main.kt:10: TODO: fix this\nsrc/app.kt:20: TODO: refactor")
        pipeClient.callToolResults["summarize"] = McpToolResult("2 TODO items found in source files")
        pipeClient.callToolResults["save_to_file"] = McpToolResult("Saved to report.txt")
        pipeEntry.connect()

        // Set up Scheduler server
        val schedEntry = orch.addServer("Scheduler", "sched-mcp")
        val schedClient = getClientFromEntry(schedEntry)
        schedClient.toolsToReturn = listOf(
            McpTool("schedule_recurring", "Schedule recurring task")
        )
        schedClient.callToolResults["schedule_recurring"] = McpToolResult("Task scheduled: daily-todo-check")
        schedEntry.connect()

        // Simulate the orchestration flow:
        // Step 1: Check git status (Git server)
        val step1 = orch.callTool("git_status", """{"path": "."}""")
        assertEquals("Modified: src/main.kt", step1.content)
        assertEquals(gitEntry, orch.serverForTool("git_status"))

        // Step 2: Search for TODOs (Pipeline server)
        val step2 = orch.callTool("search", """{"directory": ".", "keyword": "TODO"}""")
        assertTrue(step2.content.contains("TODO"))
        assertEquals(pipeEntry, orch.serverForTool("search"))

        // Step 3: Summarize findings (Pipeline server)
        val step3 = orch.callTool("summarize", """{"text": "${step2.content}"}""")
        assertTrue(step3.content.contains("TODO"))

        // Step 4: Save report (Pipeline server)
        val step4 = orch.callTool("save_to_file", """{"path": "report.txt", "content": "${step3.content}"}""")
        assertTrue(step4.content.contains("Saved"))

        // Step 5: Schedule daily reminder (Scheduler server)
        val step5 = orch.callTool("schedule_recurring", """{"description": "Check TODOs", "interval_seconds": 86400}""")
        assertTrue(step5.content.contains("scheduled"))
        assertEquals(schedEntry, orch.serverForTool("schedule_recurring"))

        // Verify all 3 servers were used
        assertEquals(3, orch.connectedCount)
        assertEquals(6, orch.allTools.size) // 2 + 3 + 1 (git + pipeline + scheduler)
    }

    @Test
    fun `drainNotifications collects from all servers`() {
        val orch = createOrchestrator()
        val e1 = orch.addServer("S1", "cmd1")
        val e2 = orch.addServer("S2", "cmd2")

        e1.notifications.add(state.SchedulerNotification(taskId = "t1", description = "Task 1"))
        e2.notifications.add(state.SchedulerNotification(taskId = "t2", description = "Task 2"))

        val drained = orch.drainNotifications()
        assertEquals(2, drained.size)
        assertTrue(e1.notifications.isEmpty())
        assertTrue(e2.notifications.isEmpty())
    }

    // Helper: extract the FakeMcpClient from an entry via reflection
    // Since McpServerEntry takes a McpClientInterface, and our factory creates FakeMcpClient
    private fun getClientFromEntry(entry: McpServerEntry): FakeMcpClient {
        val field = McpServerEntry::class.java.getDeclaredField("client")
        field.isAccessible = true
        return field.get(entry) as FakeMcpClient
    }
}

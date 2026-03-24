package state

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskMemoryTest {

    @Test
    fun `toPromptContext returns empty for empty memory`() {
        val memory = TaskMemory()
        assertEquals("", memory.toPromptContext())
    }

    @Test
    fun `toPromptContext includes goal`() {
        val memory = TaskMemory().apply {
            goal = "Understand MCP architecture"
        }
        val ctx = memory.toPromptContext()
        assertTrue(ctx.contains("GOAL: Understand MCP architecture"))
        assertTrue(ctx.contains("[Task Memory"))
    }

    @Test
    fun `toPromptContext includes all sections`() {
        val memory = TaskMemory().apply {
            goal = "Build a chat system"
            clarifications.addAll(listOf("Uses WebSocket", "Port 8765"))
            constraints.addAll(listOf("Must support Android"))
            coveredTopics.addAll(listOf("Server setup", "Client connection"))
        }
        val ctx = memory.toPromptContext()
        assertTrue(ctx.contains("GOAL: Build a chat system"))
        assertTrue(ctx.contains("ESTABLISHED FACTS:"))
        assertTrue(ctx.contains("Uses WebSocket"))
        assertTrue(ctx.contains("Port 8765"))
        assertTrue(ctx.contains("USER CONSTRAINTS:"))
        assertTrue(ctx.contains("Must support Android"))
        assertTrue(ctx.contains("ALREADY COVERED:"))
        assertTrue(ctx.contains("Server setup"))
        assertTrue(ctx.contains("Client connection"))
        assertTrue(ctx.contains("Do NOT repeat already-covered topics"))
    }

    @Test
    fun `reset clears all fields`() {
        val memory = TaskMemory().apply {
            goal = "Test goal"
            clarifications.add("fact1")
            constraints.add("constraint1")
            coveredTopics.add("topic1")
            isExtracting = true
        }

        memory.reset()

        assertNull(memory.goal)
        assertTrue(memory.clarifications.isEmpty())
        assertTrue(memory.constraints.isEmpty())
        assertTrue(memory.coveredTopics.isEmpty())
        assertFalse(memory.isExtracting)
    }

    @Test
    fun `toPromptContext with only clarifications`() {
        val memory = TaskMemory().apply {
            clarifications.add("The system uses Kotlin")
        }
        val ctx = memory.toPromptContext()
        assertTrue(ctx.contains("ESTABLISHED FACTS:"))
        assertTrue(ctx.contains("The system uses Kotlin"))
        assertFalse(ctx.contains("GOAL:"))
    }

    @Test
    fun `toPromptContext with only constraints`() {
        val memory = TaskMemory().apply {
            constraints.add("Android only")
        }
        val ctx = memory.toPromptContext()
        assertTrue(ctx.contains("USER CONSTRAINTS:"))
        assertTrue(ctx.contains("Android only"))
        assertFalse(ctx.contains("GOAL:"))
        assertFalse(ctx.contains("ESTABLISHED FACTS:"))
    }
}

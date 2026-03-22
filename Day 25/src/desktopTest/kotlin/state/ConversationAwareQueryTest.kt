package state

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationAwareQueryTest {

    @Test
    fun `basic message without memory returns unchanged`() {
        val result = ConversationAwareQuery.build("What is WebSocket?")
        assertEquals("What is WebSocket?", result)
    }

    @Test
    fun `null task memory returns unchanged`() {
        val result = ConversationAwareQuery.build("Hello", null)
        assertEquals("Hello", result)
    }

    @Test
    fun `empty task memory returns unchanged`() {
        val memory = TaskMemory()
        val result = ConversationAwareQuery.build("What is WebSocket?", memory)
        assertEquals("What is WebSocket?", result)
    }

    @Test
    fun `goal keywords are added when not in message`() {
        val memory = TaskMemory().apply {
            goal = "Understanding MCP server architecture"
        }
        val result = ConversationAwareQuery.build("How does it connect?", memory)
        // Should be enriched with goal keywords
        assertTrue(result.contains("connect"), "Should contain original message")
        assertTrue(result.length > "How does it connect?".length, "Should be enriched")
    }

    @Test
    fun `goal keywords are not duplicated when already in message`() {
        val memory = TaskMemory().apply {
            goal = "Understanding WebSocket protocol"
        }
        val result = ConversationAwareQuery.build("What is WebSocket protocol?", memory)
        // Goal keywords overlap with message, so should not add much
        assertTrue(result.contains("WebSocket"))
    }

    @Test
    fun `short message gets clarification context`() {
        val memory = TaskMemory().apply {
            clarifications.add("The system uses Ktor framework for WebSocket server")
        }
        val result = ConversationAwareQuery.build("What port?", memory)
        // Short message should be enriched with last clarification keywords
        assertTrue(result.length > "What port?".length, "Short message should be enriched")
    }

    @Test
    fun `long message does not get clarification context`() {
        val memory = TaskMemory().apply {
            clarifications.add("Something about Ktor framework")
        }
        val longMessage = "Tell me about the WebSocket server port configuration and how it differs across environments"
        val result = ConversationAwareQuery.build(longMessage, memory)
        // Long message should NOT be enriched with clarifications (>4 words)
        assertEquals(longMessage, result)
    }

    @Test
    fun `constraint terms enrich the query`() {
        val memory = TaskMemory().apply {
            constraints.add("Must use Android platform only")
        }
        val result = ConversationAwareQuery.build("How does discovery work?", memory)
        assertTrue(result.contains("discovery"))
        // Constraint keywords (android, platform) should be added if not present
        assertTrue(result.length > "How does discovery work?".length)
    }

    @Test
    fun `extractKeywords filters stop words`() {
        val keywords = ConversationAwareQuery.extractKeywords("The app and the user are both connected")
        assertFalse(keywords.contains("the"))
        assertFalse(keywords.contains("and"))
        assertFalse(keywords.contains("are"))
        assertTrue(keywords.contains("app"))
        assertTrue(keywords.contains("user"))
        assertTrue(keywords.contains("connected"))
    }

    @Test
    fun `extractKeywords filters short words`() {
        val keywords = ConversationAwareQuery.extractKeywords("it is a big app")
        assertFalse(keywords.contains("it"))
        assertFalse(keywords.contains("is"))
        assertTrue(keywords.contains("big"))
        assertTrue(keywords.contains("app"))
    }
}

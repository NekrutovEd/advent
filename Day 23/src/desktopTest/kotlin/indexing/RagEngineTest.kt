package indexing

import org.junit.jupiter.api.Test
import state.RagChunk
import state.RagResult
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RagEngineTest {

    @Test
    fun `buildContext formats chunks correctly`() {
        val engine = RagEngine(apiKey = "test-key")
        val result = RagResult(
            chunks = listOf(
                RagChunk(
                    text = "RemoteClaude allows controlling agents from a smartphone",
                    source = "SYSTEM_OVERVIEW.md",
                    section = "Назначение",
                    score = 0.85f
                ),
                RagChunk(
                    text = "Plugin runs a Ktor WebSocket server on port 8765",
                    source = "SYSTEM_OVERVIEW.md",
                    section = "Компоненты",
                    score = 0.72f
                )
            ),
            queryTimeMs = 150
        )

        val context = engine.buildContext(result)
        assertTrue(context.contains("[RAG Context"))
        assertTrue(context.contains("SYSTEM_OVERVIEW.md"))
        assertTrue(context.contains("score:"), "Should contain score label")
        assertTrue(context.contains("Source 1:"))
        assertTrue(context.contains("Source 2:"))
        assertTrue(context.contains("RemoteClaude allows"))
        assertTrue(context.contains("Ktor WebSocket"))
    }

    @Test
    fun `buildContext returns empty for no results`() {
        val engine = RagEngine(apiKey = "test-key")
        val result = RagResult(chunks = emptyList())
        val context = engine.buildContext(result)
        assertEquals("", context)
    }

    @Test
    fun `isReady returns false when no index loaded`() {
        val engine = RagEngine(apiKey = "test-key")
        assertEquals(false, engine.isReady)
    }
}

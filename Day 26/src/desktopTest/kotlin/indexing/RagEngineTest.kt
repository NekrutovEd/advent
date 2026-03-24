package indexing

import org.junit.jupiter.api.Test
import state.RagChunk
import state.RagMode
import state.RagProvider
import state.RagResult
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `buildContext returns low confidence context for no results`() {
        val engine = RagEngine(apiKey = "test-key")
        val result = RagResult(chunks = emptyList())
        val context = engine.buildContext(result)
        assertTrue(context.contains("no relevant documents"), "Empty results should trigger low-confidence context")
        assertTrue(context.contains("rephrase"), "Should suggest rephrasing")
    }

    @Test
    fun `buildContext includes pipeline metadata when present`() {
        val engine = RagEngine(apiKey = "test-key")
        val result = RagResult(
            chunks = listOf(
                RagChunk("Some text", "doc.md", "intro", 0.8f)
            ),
            candidateCount = 15,
            effectiveQuery = "expanded query terms"
        )

        val context = engine.buildContext(result)
        assertTrue(context.contains("1 results selected from 15 candidates"))
        assertTrue(context.contains("Effective query: expanded query terms"))
    }

    @Test
    fun `buildContext omits pipeline metadata when not set`() {
        val engine = RagEngine(apiKey = "test-key")
        val result = RagResult(
            chunks = listOf(
                RagChunk("Some text", "doc.md", "intro", 0.8f)
            )
        )

        val context = engine.buildContext(result)
        assertFalse(context.contains("Pipeline:"))
        assertFalse(context.contains("Effective query:"))
    }

    @Test
    fun `isReady returns false when no index loaded`() {
        val engine = RagEngine(apiKey = "test-key")
        assertEquals(false, engine.isReady)
    }

    @Test
    fun `default config is accessible`() {
        val engine = RagEngine(apiKey = "test-key")
        val config = engine.config
        assertEquals(15, config.fetchTopK)
        assertEquals(5, config.finalTopK)
        assertEquals(RagMode.RERANKED, config.mode)
    }

    @Test
    fun `config is mutable`() {
        val engine = RagEngine(apiKey = "test-key")
        engine.config = RagConfig(fetchTopK = 20, finalTopK = 10)
        assertEquals(20, engine.config.fetchTopK)
        assertEquals(10, engine.config.finalTopK)
    }

    // ── Citation instruction tests ──────────────────────────────

    @Test
    fun `buildContext includes citation instructions`() {
        val engine = RagEngine(apiKey = "test-key")
        val result = RagResult(
            chunks = listOf(RagChunk("Some text", "doc.md", "intro", 0.8f))
        )
        val context = engine.buildContext(result)
        assertTrue(context.contains("SOURCES:"), "Should require sources section")
        assertTrue(context.contains("QUOTES:"), "Should require quotes section")
        assertTrue(context.contains("verbatim"), "Should require verbatim quotes")
    }

    @Test
    fun `buildContext low confidence triggers I-dont-know mode`() {
        val engine = RagEngine(apiKey = "test-key")
        val result = RagResult(
            chunks = listOf(RagChunk("Weak match", "doc.md", "s1", 0.2f)),
            lowConfidence = true
        )
        val context = engine.buildContext(result)
        assertTrue(context.contains("no relevant documents"), "Low confidence should trigger no-data context")
        assertTrue(context.contains("rephrase"), "Should ask user to rephrase")
        assertFalse(context.contains("Source 1:"), "Should NOT include chunk data in low-confidence mode")
    }

    @Test
    fun `confidenceThreshold has reasonable default`() {
        val engine = RagEngine(apiKey = "test-key")
        assertTrue(engine.confidenceThreshold in 0.1f..0.5f,
            "Confidence threshold should be between 0.1 and 0.5, got ${engine.confidenceThreshold}")
    }

    // ── 10-question citation validation ─────────────────────────

    /**
     * Helper: simulate a RAG result for a question and verify the context
     * contains required source, quote, and citation instruction sections.
     */
    private fun assertCitationStructure(
        questionLabel: String,
        chunks: List<RagChunk>,
        expectLowConfidence: Boolean = false
    ) {
        val engine = RagEngine(apiKey = "test-key")
        val topScore = chunks.maxOfOrNull { it.score } ?: 0f
        val result = RagResult(
            chunks = chunks,
            lowConfidence = expectLowConfidence || topScore < engine.confidenceThreshold
        )
        val context = engine.buildContext(result)

        if (expectLowConfidence || topScore < engine.confidenceThreshold) {
            assertTrue(context.contains("no relevant documents"),
                "$questionLabel: low-confidence result should trigger 'no relevant documents'")
            assertTrue(context.contains("rephrase") || context.contains("clarify"),
                "$questionLabel: should suggest rephrasing or clarifying")
        } else {
            // Must contain source references
            for ((i, chunk) in chunks.withIndex()) {
                assertTrue(context.contains("Source ${i + 1}: ${chunk.source}"),
                    "$questionLabel: must contain source reference for chunk ${i + 1}")
                assertTrue(context.contains(chunk.section),
                    "$questionLabel: must contain section '${chunk.section}'")
            }
            // Must contain chunk text (quote material)
            for (chunk in chunks) {
                assertTrue(context.contains(chunk.text),
                    "$questionLabel: must contain chunk text for quoting")
            }
            // Must contain citation instructions
            assertTrue(context.contains("SOURCES:"),
                "$questionLabel: must include SOURCES instruction")
            assertTrue(context.contains("QUOTES:"),
                "$questionLabel: must include QUOTES instruction")
            assertTrue(context.contains("verbatim"),
                "$questionLabel: must require verbatim excerpts")
        }
    }

    @Test
    fun `Q1 - WebSocket port question has sources and quotes`() {
        assertCitationStructure("Q1: WebSocket port", listOf(
            RagChunk("Plugin runs a Ktor WebSocket server on port 8765", "SYSTEM_OVERVIEW.md", "Components", 0.88f)
        ))
    }

    @Test
    fun `Q2 - mDNS discovery question has sources and quotes`() {
        assertCitationStructure("Q2: mDNS discovery", listOf(
            RagChunk("The app discovers the plugin via mDNS broadcast on the local network", "mdns_discovery.md", "Overview", 0.82f),
            RagChunk("NsdManager is used on Android to resolve _remoteclaude._tcp services", "mdns_discovery.md", "Android Side", 0.75f)
        ))
    }

    @Test
    fun `Q3 - push notifications question has sources and quotes`() {
        assertCitationStructure("Q3: Push notifications", listOf(
            RagChunk("FCM push notifications are sent when the agent produces output while the app is backgrounded", "push_notifications.md", "FCM Flow", 0.79f)
        ))
    }

    @Test
    fun `Q4 - terminal rendering question has sources and quotes`() {
        assertCitationStructure("Q4: Terminal rendering", listOf(
            RagChunk("Terminal output is rendered using a custom Compose canvas that interprets ANSI escape sequences", "terminal_renderer.md", "Rendering", 0.84f),
            RagChunk("Colors are mapped from ANSI 256-color palette to Material3 theme colors", "terminal_renderer.md", "Colors", 0.71f)
        ))
    }

    @Test
    fun `Q5 - agent orchestration question has sources and quotes`() {
        assertCitationStructure("Q5: Agent orchestration", listOf(
            RagChunk("McpOrchestrator routes tool calls across multiple MCP servers based on tool name prefixes", "agent_orchestration.md", "Routing", 0.86f),
            RagChunk("Cross-server flows are supported: output of one tool can be piped as input to another", "agent_orchestration.md", "Pipelines", 0.73f)
        ))
    }

    @Test
    fun `Q6 - Android app architecture has sources and quotes`() {
        assertCitationStructure("Q6: Android architecture", listOf(
            RagChunk("The Android app uses Jetpack Compose with a single-activity architecture", "android_app.md", "Architecture", 0.81f)
        ))
    }

    @Test
    fun `Q7 - chunking strategy question has sources and quotes`() {
        assertCitationStructure("Q7: Chunking strategy", listOf(
            RagChunk("Documents are split using two strategies: fixed-size (512 chars with overlap) and structural (by headings)", "ChunkingStrategy.kt", "strategies", 0.77f),
            RagChunk("Structural chunking detects Markdown headings and splits at ## boundaries", "ChunkingStrategy.kt", "structural", 0.69f)
        ))
    }

    @Test
    fun `Q8 - embedding model question has sources and quotes`() {
        assertCitationStructure("Q8: Embedding model", listOf(
            RagChunk("EmbeddingClient calls the OpenAI API with model text-embedding-3-small", "EmbeddingClient.kt", "embed", 0.83f)
        ))
    }

    @Test
    fun `Q9 - unrelated question triggers low confidence`() {
        assertCitationStructure("Q9: Unrelated question", listOf(
            RagChunk("Some barely matching text about something else", "random.md", "misc", 0.25f)
        ), expectLowConfidence = true)
    }

    @Test
    fun `Q10 - empty results trigger low confidence`() {
        assertCitationStructure("Q10: No results", emptyList(), expectLowConfidence = true)
    }

    // ── RagResult.lowConfidence flag ────────────────────────────

    @Test
    fun `RagResult lowConfidence is false by default`() {
        val result = RagResult(chunks = emptyList())
        assertFalse(result.lowConfidence)
    }

    @Test
    fun `RagResult lowConfidence can be set explicitly`() {
        val result = RagResult(chunks = listOf(RagChunk("text", "src", "s1", 0.3f)), lowConfidence = true)
        assertTrue(result.lowConfidence)
    }

    // ── Companion constants accessibility ──────────────────────

    @Test
    fun `citation instructions constant is non-empty`() {
        assertTrue(RagProvider.CITATION_INSTRUCTIONS.isNotBlank())
        assertTrue(RagProvider.CITATION_INSTRUCTIONS.contains("SOURCES"))
        assertTrue(RagProvider.CITATION_INSTRUCTIONS.contains("QUOTES"))
    }

    @Test
    fun `low confidence instructions constant is non-empty`() {
        assertTrue(RagProvider.LOW_CONFIDENCE_INSTRUCTIONS.isNotBlank())
        assertTrue(RagProvider.LOW_CONFIDENCE_INSTRUCTIONS.contains("rephrase"))
    }
}

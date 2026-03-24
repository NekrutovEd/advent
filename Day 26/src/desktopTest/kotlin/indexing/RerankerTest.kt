package indexing

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import state.RagChunk

class RerankerTest {

    // ── Tokenization ──────────────────────────────────────────

    @Test
    fun `tokenize splits text into lowercase terms`() {
        val tokens = Reranker.tokenize("Hello World Test")
        assertTrue("hello" in tokens)
        assertTrue("world" in tokens)
        assertTrue("test" in tokens)
    }

    @Test
    fun `tokenize filters short words and stop words`() {
        val tokens = Reranker.tokenize("the a I is big cat")
        assertFalse("the" in tokens)
        assertFalse("a" in tokens) // too short and stop word
        assertFalse("is" in tokens) // stop word
        assertTrue("big" in tokens)
        assertTrue("cat" in tokens)
    }

    @Test
    fun `tokenize handles special characters`() {
        val tokens = Reranker.tokenize("file_path.kt -> main()")
        assertTrue("file" in tokens)
        assertTrue("path" in tokens)
        assertTrue("main" in tokens)
    }

    // ── Keyword Overlap ──────────────────────────────────────

    @Test
    fun `keywordOverlap returns 1 when all query terms found`() {
        val queryTerms = setOf("websocket", "server", "port")
        val score = Reranker.keywordOverlap(queryTerms, "The WebSocket server runs on port 8765")
        assertEquals(1.0f, score, 0.01f)
    }

    @Test
    fun `keywordOverlap returns 0 when no terms match`() {
        val queryTerms = setOf("database", "sql")
        val score = Reranker.keywordOverlap(queryTerms, "The cat sat on the mat")
        assertEquals(0.0f, score, 0.01f)
    }

    @Test
    fun `keywordOverlap returns partial score`() {
        val queryTerms = setOf("websocket", "database")
        val score = Reranker.keywordOverlap(queryTerms, "WebSocket connection established")
        assertEquals(0.5f, score, 0.01f)
    }

    @Test
    fun `keywordOverlap handles substring matching`() {
        val queryTerms = setOf("embed")
        val score = Reranker.keywordOverlap(queryTerms, "The embedding model generates vectors")
        assertEquals(1.0f, score, 0.01f)
    }

    // ── Reranking ────────────────────────────────────────────

    @Test
    fun `rerank boosts chunks with keyword overlap`() {
        val chunks = listOf(
            // High cosine similarity but no keyword match
            RagChunk("Random text about weather", "a.md", "intro", 0.85f),
            // Lower cosine but strong keyword match
            RagChunk("WebSocket server handles connections on port 8765", "b.md", "arch", 0.70f),
            // Medium cosine with partial keyword match
            RagChunk("The server configuration is in settings", "c.md", "config", 0.75f)
        )

        val config = RagConfig(
            finalTopK = 3,
            semanticWeight = 0.5f,
            keywordWeight = 0.5f,
            minRerankScore = 0.0f,
            scoreGapThreshold = 1.0f // disable gap filter for this test
        )

        val result = Reranker.rerank("websocket server port", chunks, config)

        assertEquals(3, result.size)
        // The chunk with keyword overlap should be boosted
        assertTrue(result[0].source == "b.md",
            "Expected b.md (keyword match) to rank first but got ${result[0].source}")
    }

    @Test
    fun `rerank applies finalTopK limit`() {
        val chunks = (1..10).map {
            RagChunk("Chunk $it about something", "doc.md", "s$it", 0.5f + it * 0.03f)
        }

        val config = RagConfig(finalTopK = 3, minRerankScore = 0.0f, scoreGapThreshold = 1.0f)
        val result = Reranker.rerank("something", chunks, config)

        assertTrue(result.size <= 3)
    }

    @Test
    fun `rerank filters by minRerankScore`() {
        val chunks = listOf(
            RagChunk("Relevant content about API", "a.md", "s1", 0.8f),
            RagChunk("Completely unrelated text xyz abc", "b.md", "s2", 0.15f)
        )

        val config = RagConfig(
            finalTopK = 5,
            minRerankScore = 0.4f,
            semanticWeight = 0.7f,
            keywordWeight = 0.3f,
            scoreGapThreshold = 1.0f
        )
        val result = Reranker.rerank("API documentation", chunks, config)

        // The low-scoring irrelevant chunk should be filtered out
        assertTrue(result.all { it.score >= 0.4f },
            "Expected all results to have score >= 0.4, got: ${result.map { it.score }}")
    }

    @Test
    fun `rerank handles empty input`() {
        val result = Reranker.rerank("test query", emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `rerank returns chunks with updated combined scores`() {
        val chunks = listOf(
            RagChunk("Plugin WebSocket server code", "plugin.md", "s1", 0.8f)
        )

        val config = RagConfig(
            semanticWeight = 0.7f,
            keywordWeight = 0.3f,
            minRerankScore = 0.0f,
            scoreGapThreshold = 1.0f
        )
        val result = Reranker.rerank("websocket plugin", chunks, config)

        assertEquals(1, result.size)
        // Combined score should reflect both semantic and keyword components
        val expected = 0.7f * 0.8f + 0.3f * 1.0f // full keyword match
        assertEquals(expected, result[0].score, 0.05f)
    }

    // ── Score Gap Detection ─────────────────────────────────

    @Test
    fun `rerank drops results after significant score gap`() {
        val chunks = listOf(
            RagChunk("Very relevant content about WebSocket", "a.md", "s1", 0.90f),
            RagChunk("Also relevant WebSocket server", "b.md", "s2", 0.85f),
            RagChunk("Somewhat related server info", "c.md", "s3", 0.80f),
            // Big gap here
            RagChunk("Barely related text mention", "d.md", "s4", 0.30f),
            RagChunk("Completely unrelated stuff", "e.md", "s5", 0.25f)
        )

        val config = RagConfig(
            finalTopK = 10,
            semanticWeight = 1.0f,
            keywordWeight = 0.0f, // pure cosine for predictable gap
            minRerankScore = 0.0f,
            scoreGapThreshold = 0.3f // 30% of top score = gap limit ~0.27
        )

        val result = Reranker.rerank("websocket server", chunks, config)

        // Should include the first 3 (gap between them < 0.27)
        // Should stop before d.md (gap of 0.50 > 0.27)
        assertTrue(result.size <= 3,
            "Expected at most 3 results after gap filter, got ${result.size}: ${result.map { "${it.source}:${it.score}" }}")
    }

    // ── Query Rewriting ─────────────────────────────────────

    @Test
    fun `rewriteQuery expands camelCase identifiers`() {
        val rewritten = Reranker.rewriteQuery("How does ChatState work?")
        assertTrue(rewritten.contains("chat"))
        assertTrue(rewritten.contains("state"))
    }

    @Test
    fun `rewriteQuery expands snake_case identifiers`() {
        val rewritten = Reranker.rewriteQuery("What is api_key used for?")
        assertTrue(rewritten.contains("api"))
        assertTrue(rewritten.contains("key"))
    }

    @Test
    fun `rewriteQuery lowercases everything`() {
        val rewritten = Reranker.rewriteQuery("WebSocket SERVER Port")
        assertEquals(rewritten, rewritten.lowercase())
    }

    @Test
    fun `rewriteQuery preserves original query`() {
        val query = "How does the plugin connect?"
        val rewritten = Reranker.rewriteQuery(query)
        assertTrue(rewritten.contains(query.lowercase()))
    }

    // ── RagConfig ───────────────────────────────────────────

    @Test
    fun `default config has reasonable values`() {
        val config = RagConfig()
        assertTrue(config.fetchTopK > config.finalTopK,
            "fetchTopK (${config.fetchTopK}) should be greater than finalTopK (${config.finalTopK})")
        assertTrue(config.semanticWeight + config.keywordWeight > 0.99f,
            "Weights should sum to ~1.0")
        assertTrue(config.minScore in 0.0f..1.0f)
        assertTrue(config.minRerankScore in 0.0f..1.0f)
    }
}

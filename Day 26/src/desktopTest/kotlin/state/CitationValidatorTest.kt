package state

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CitationValidatorTest {

    private val sampleChunks = listOf(
        RagChunk(
            text = "Plugin runs a Ktor WebSocket server on port 8765",
            source = "SYSTEM_OVERVIEW.md",
            section = "Components",
            score = 0.88f
        ),
        RagChunk(
            text = "The app discovers the plugin via mDNS broadcast on the local network",
            source = "mdns_discovery.md",
            section = "Overview",
            score = 0.75f
        )
    )

    @Test
    fun `well-formed response with matching sources scores high`() {
        val response = """
            The plugin uses a WebSocket server on port 8765 for communication,
            and discovery happens via mDNS on the local network.

            Sources:
            - [SYSTEM_OVERVIEW.md (Components)]
            - [mdns_discovery.md (Overview)]

            Quotes:
            > "Plugin runs a Ktor WebSocket server on port 8765" — SYSTEM_OVERVIEW.md (Components)
            > "The app discovers the plugin via mDNS broadcast on the local network" — mdns_discovery.md (Overview)
        """.trimIndent()

        val result = CitationValidator.validate(response, sampleChunks)
        assertEquals(2, result.citedSources.size)
        assertEquals(2, result.verifiedSources.size)
        assertEquals(0, result.missingSources.size)
        assertEquals(2, result.quotes.size)
        assertEquals(2, result.verifiedQuotes.size)
        assertTrue(result.groundingScore >= 0.8f, "Grounding score should be >= 0.8, got ${result.groundingScore}")
        assertTrue(result.isFullyGrounded)
    }

    @Test
    fun `response with missing source has lower score`() {
        val response = """
            The system uses WebSocket and REST APIs.

            Sources:
            - [SYSTEM_OVERVIEW.md (Components)]
            - [rest_api.md (Endpoints)]

            Quotes:
            > "Plugin runs a Ktor WebSocket server on port 8765" — SYSTEM_OVERVIEW.md
        """.trimIndent()

        val result = CitationValidator.validate(response, sampleChunks)
        assertEquals(2, result.citedSources.size)
        assertEquals(1, result.verifiedSources.size)
        assertEquals(1, result.missingSources.size)
        assertTrue(result.missingSources[0].contains("rest_api"))
    }

    @Test
    fun `response with no sources or quotes section scores zero`() {
        val response = "The plugin uses WebSocket for communication."

        val result = CitationValidator.validate(response, sampleChunks)
        assertEquals(0, result.citedSources.size)
        assertEquals(0, result.quotes.size)
        assertEquals(0f, result.groundingScore)
        assertFalse(result.hasCitations)
    }

    @Test
    fun `response with only sources but no quotes`() {
        val response = """
            The plugin communicates via WebSocket.

            Sources:
            - [SYSTEM_OVERVIEW.md (Components)]
        """.trimIndent()

        val result = CitationValidator.validate(response, sampleChunks)
        assertEquals(1, result.citedSources.size)
        assertEquals(1, result.verifiedSources.size)
        assertEquals(0, result.quotes.size)
        // Should have partial score (source verified but no quotes)
        assertTrue(result.groundingScore > 0f)
        assertTrue(result.groundingScore < 0.8f)
    }

    @Test
    fun `empty chunks returns zero score`() {
        val response = """
            Some answer.

            Sources:
            - [doc.md]
        """.trimIndent()

        val result = CitationValidator.validate(response, emptyList())
        assertEquals(0f, result.groundingScore)
    }

    @Test
    fun `quotes with fuzzy matching are verified`() {
        val response = """
            Discovery uses mDNS.

            Sources:
            - [mdns_discovery.md (Overview)]

            Quotes:
            > "app discovers plugin via mDNS broadcast on local network" — mdns_discovery.md
        """.trimIndent()

        val result = CitationValidator.validate(response, sampleChunks)
        // Fuzzy match should still verify (enough words match)
        assertTrue(result.verifiedQuotes.isNotEmpty(), "Fuzzy quote should be verified")
    }

    @Test
    fun `summaryText formats correctly`() {
        val result = CitationResult(
            citedSources = listOf("a.md", "b.md"),
            verifiedSources = listOf("a.md"),
            missingSources = listOf("b.md"),
            quotes = listOf("quote1"),
            verifiedQuotes = listOf("quote1"),
            groundingScore = 0.6f
        )
        val summary = result.summaryText()
        assertTrue(summary.contains("1/2 sources"))
        assertTrue(summary.contains("1/1 quotes"))
        assertTrue(summary.contains("60%"))
    }

    @Test
    fun `parseSources handles various formats`() {
        val response = """
            Answer here.

            Sources:
            - [SYSTEM_OVERVIEW.md (Components)]
            - mdns_discovery.md (Overview)
        """.trimIndent()

        val sources = CitationValidator.parseSources(response)
        assertTrue(sources.isNotEmpty(), "Should parse at least one source")
    }

    @Test
    fun `parseQuotes handles quoted format`() {
        val response = """
            Answer.

            Quotes:
            > "Plugin runs a Ktor WebSocket server on port 8765" — SYSTEM_OVERVIEW.md
        """.trimIndent()

        val quotes = CitationValidator.parseQuotes(response)
        assertEquals(1, quotes.size)
        assertTrue(quotes[0].contains("Ktor WebSocket"))
    }

    @Test
    fun `hasCitations returns false when no sources cited`() {
        val result = CitationResult(
            citedSources = emptyList(),
            verifiedSources = emptyList(),
            missingSources = emptyList(),
            quotes = emptyList(),
            verifiedQuotes = emptyList(),
            groundingScore = 0f
        )
        assertFalse(result.hasCitations)
    }
}

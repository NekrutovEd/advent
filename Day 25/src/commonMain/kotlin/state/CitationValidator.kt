package state

/**
 * Validates that an LLM response properly cites sources from the RAG context.
 * Pure logic — no LLM calls. Parses the response for SOURCES/QUOTES sections
 * and cross-references against the provided RAG chunks.
 */
object CitationValidator {

    /**
     * Validate citations in an assistant response against RAG context.
     *
     * @param response The full assistant response text
     * @param ragChunks The chunks that were provided as RAG context
     * @return CitationResult with validation details
     */
    fun validate(response: String, ragChunks: List<RagChunk>): CitationResult {
        if (ragChunks.isEmpty()) {
            return CitationResult(
                citedSources = emptyList(),
                verifiedSources = emptyList(),
                missingSources = emptyList(),
                quotes = emptyList(),
                verifiedQuotes = emptyList(),
                groundingScore = 0f
            )
        }

        val citedSources = parseSources(response)
        val quotes = parseQuotes(response)

        // Verify cited sources against RAG chunks
        val availableSources = ragChunks.map { it.source.lowercase() }.toSet()
        val verified = citedSources.filter { cited ->
            availableSources.any { src -> src.contains(cited.lowercase()) || cited.lowercase().contains(src) }
        }
        val missing = citedSources.filter { cited ->
            availableSources.none { src -> src.contains(cited.lowercase()) || cited.lowercase().contains(src) }
        }

        // Verify quotes — check if they appear (approximately) in chunk texts
        val allChunkText = ragChunks.joinToString("\n") { it.text }.lowercase()
        val verifiedQuotes = quotes.filter { quote ->
            val normalized = quote.lowercase().trim()
            if (normalized.length < 10) return@filter false
            // Check if a significant substring matches
            allChunkText.contains(normalized) ||
                fuzzyContains(allChunkText, normalized)
        }

        // Calculate grounding score
        val sourceScore = if (citedSources.isEmpty()) 0f
        else verified.size.toFloat() / citedSources.size.toFloat()

        val quoteScore = if (quotes.isEmpty()) 0f
        else verifiedQuotes.size.toFloat() / quotes.size.toFloat()

        // Combined: 40% source verification + 40% quote verification + 20% presence bonus
        val presenceBonus = if (citedSources.isNotEmpty() && quotes.isNotEmpty()) 0.2f else 0f
        val groundingScore = (sourceScore * 0.4f + quoteScore * 0.4f + presenceBonus)
            .coerceIn(0f, 1f)

        return CitationResult(
            citedSources = citedSources,
            verifiedSources = verified,
            missingSources = missing,
            quotes = quotes,
            verifiedQuotes = verifiedQuotes,
            groundingScore = groundingScore
        )
    }

    /**
     * Parse "Sources:" section from the response.
     * Handles formats: "- [source]", "- source", "* source", "1. source"
     */
    internal fun parseSources(response: String): List<String> {
        // Find sources section — look for variations
        val sourcesPattern = Regex(
            """(?:^|\n)\s*(?:\*{0,2})(?:Sources?|SOURCES?|Источники?)(?:\*{0,2})\s*:?\s*\n([\s\S]*?)(?=\n\s*(?:\*{0,2})(?:Quotes?|QUOTES?|Цитаты?)|\n\n\n|\z)""",
            RegexOption.IGNORE_CASE
        )
        val match = sourcesPattern.find(response) ?: return emptyList()
        val sourcesBlock = match.groupValues[1]

        val sourceLinePattern = Regex("""[-*•]\s*\[?([^\]\n]+?)\]?\s*$""", RegexOption.MULTILINE)
        return sourceLinePattern.findAll(sourcesBlock)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    /**
     * Parse "Quotes:" section from the response.
     * Handles formats: > "quote" — source, > quote
     */
    internal fun parseQuotes(response: String): List<String> {
        // Find quotes section
        val quotesPattern = Regex(
            """(?:^|\n)\s*(?:\*{0,2})(?:Quotes?|QUOTES?|Цитаты?)(?:\*{0,2})\s*:?\s*\n([\s\S]*?)(?=\n\n\n|\z)""",
            RegexOption.IGNORE_CASE
        )
        val match = quotesPattern.find(response) ?: return emptyList()
        val quotesBlock = match.groupValues[1]

        val results = mutableListOf<String>()

        // Pattern 1: > "quoted text" — source
        val quotedPattern = Regex(""">\s*"([^"]+)"\s*(?:—|-)""")
        quotedPattern.findAll(quotesBlock).forEach { results.add(it.groupValues[1]) }

        // Pattern 2: > quoted text (without quotes)
        if (results.isEmpty()) {
            val unquotedPattern = Regex(""">\s*(.+?)(?:\s*(?:—|-)\s*\S+)?$""", RegexOption.MULTILINE)
            unquotedPattern.findAll(quotesBlock).forEach {
                val text = it.groupValues[1].trim().removeSurrounding("\"")
                if (text.isNotBlank()) results.add(text)
            }
        }

        return results
    }

    /**
     * Fuzzy containment check — allows minor differences (50% of words must match).
     */
    private fun fuzzyContains(haystack: String, needle: String): Boolean {
        val needleWords = needle.split(Regex("\\s+")).filter { it.length > 2 }
        if (needleWords.isEmpty()) return false
        val matchCount = needleWords.count { word -> haystack.contains(word) }
        return matchCount.toFloat() / needleWords.size.toFloat() >= 0.5f
    }
}

/**
 * Result of citation validation for a single response.
 */
data class CitationResult(
    /** All sources cited in the response */
    val citedSources: List<String>,
    /** Sources that were verified against RAG chunks */
    val verifiedSources: List<String>,
    /** Sources cited but not found in RAG chunks */
    val missingSources: List<String>,
    /** All quotes found in the response */
    val quotes: List<String>,
    /** Quotes that were verified against chunk text */
    val verifiedQuotes: List<String>,
    /** Overall grounding score 0.0-1.0 */
    val groundingScore: Float
) {
    val isFullyGrounded: Boolean get() = groundingScore >= 0.8f
    val hasCitations: Boolean get() = citedSources.isNotEmpty()

    fun summaryText(): String = buildString {
        append("${verifiedSources.size}/${citedSources.size} sources")
        if (quotes.isNotEmpty()) {
            append(", ${verifiedQuotes.size}/${quotes.size} quotes")
        }
        append(" (${"%.0f".format(groundingScore * 100)}%)")
    }
}

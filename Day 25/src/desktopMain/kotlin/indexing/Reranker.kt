package indexing

import state.RagChunk
import state.RagMode

/**
 * Configuration for the RAG retrieval pipeline.
 */
data class RagConfig(
    /** How many candidates to fetch from vector search (before reranking). */
    val fetchTopK: Int = 15,
    /** How many results to return after reranking/filtering. */
    val finalTopK: Int = 5,
    /** Minimum cosine similarity to keep a result (low for multilingual corpora). */
    val minScore: Float = 0.15f,
    /** Minimum combined score after reranking to keep. */
    val minRerankScore: Float = 0.15f,
    /** Weight of cosine similarity in combined score (0..1). */
    val semanticWeight: Float = 0.7f,
    /** Weight of keyword overlap in combined score (0..1). */
    val keywordWeight: Float = 0.3f,
    /** Drop results after a score gap larger than this fraction of the top score. */
    val scoreGapThreshold: Float = 0.4f,
    /** Search mode. */
    val mode: RagMode = RagMode.RERANKED
)

/**
 * Heuristic reranker that combines semantic similarity with keyword overlap
 * and applies score-gap detection to filter irrelevant trailing results.
 */
object Reranker {

    /**
     * Rerank chunks using a combination of their cosine similarity score
     * and keyword overlap with the query.
     *
     * @return reranked and filtered list of chunks, limited to [config.finalTopK]
     */
    fun rerank(
        query: String,
        chunks: List<RagChunk>,
        config: RagConfig = RagConfig()
    ): List<RagChunk> {
        if (chunks.isEmpty()) return emptyList()

        val queryTerms = tokenize(query)
        if (queryTerms.isEmpty()) {
            // No meaningful query terms — fall back to pure cosine ranking
            return chunks.take(config.finalTopK)
        }

        // Score each chunk: combined = α * cosine + β * keyword_overlap
        val scored = chunks.map { chunk ->
            val keywordScore = keywordOverlap(queryTerms, chunk.text)
            val combined = config.semanticWeight * chunk.score +
                    config.keywordWeight * keywordScore
            ScoredChunk(chunk, combined, keywordScore)
        }.sortedByDescending { it.combinedScore }

        // Apply minimum rerank score filter
        val filtered = scored.filter { it.combinedScore >= config.minRerankScore }
        if (filtered.isEmpty()) return emptyList()

        // Score-gap detection: drop results after a large gap
        val gapFiltered = applyScoreGap(filtered, config.scoreGapThreshold)

        // Return top-K with updated scores
        return gapFiltered.take(config.finalTopK).map { sc ->
            sc.chunk.copy(score = sc.combinedScore)
        }
    }

    /**
     * Lightweight query rewriting: expand the query with related terms
     * based on simple heuristics (no LLM call needed).
     *
     * Strategies:
     * - Lowercase normalization
     * - Split camelCase/snake_case identifiers
     * - Remove noise words
     */
    fun rewriteQuery(query: String): String {
        val parts = mutableListOf<String>()
        parts.add(query)

        // Split camelCase and snake_case identifiers found in the query
        val words = query.split("\\s+".toRegex())
        for (word in words) {
            // camelCase → separate words
            val camelParts = word.replace(Regex("([a-z])([A-Z])"), "$1 $2")
                .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
            if (camelParts != word) parts.add(camelParts)

            // snake_case → separate words
            if ('_' in word) {
                parts.add(word.replace('_', ' '))
            }
        }

        // Bilingual expansion: add English terms for Russian tech words
        val lower = query.lowercase()
        for ((ru, en) in BILINGUAL_MAP) {
            if (lower.contains(ru)) parts.add(en)
        }

        return parts.joinToString(" ").lowercase()
    }

    private val BILINGUAL_MAP = listOf(
        "архитектур" to "architecture components system",
        "систем" to "system overview",
        "компонент" to "components",
        "плагин" to "plugin",
        "приложени" to "application app",
        "сервер" to "server",
        "подключени" to "connection websocket",
        "уведомлени" to "notification push",
        "обнаружени" to "discovery mdns",
        "терминал" to "terminal",
        "протокол" to "protocol",
        "оркестр" to "orchestration agent",
        "агент" to "agent",
        "порт" to "port",
        "пуш" to "push notification",
        "вебсокет" to "websocket",
    )

    // ── Internal helpers ──────────────────────────────────────────

    private data class ScoredChunk(
        val chunk: RagChunk,
        val combinedScore: Float,
        val keywordScore: Float
    )

    /**
     * Tokenize text into lowercase terms, filtering out noise.
     */
    internal fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .split("\\s+".toRegex())
            .filter { it.length >= 2 }
            .filterNot { it in STOP_WORDS }
            .toSet()
    }

    /**
     * Compute keyword overlap score between query terms and chunk text.
     * Returns a value in 0..1 (fraction of query terms found in chunk).
     */
    internal fun keywordOverlap(queryTerms: Set<String>, chunkText: String): Float {
        if (queryTerms.isEmpty()) return 0f
        val chunkTerms = tokenize(chunkText)
        val matched = queryTerms.count { qt ->
            chunkTerms.any { ct -> ct.contains(qt) || qt.contains(ct) }
        }
        return matched.toFloat() / queryTerms.size
    }

    /**
     * Drop results after a significant score gap.
     * A gap is considered significant if it exceeds [threshold] * topScore.
     */
    private fun applyScoreGap(
        sorted: List<ScoredChunk>,
        threshold: Float
    ): List<ScoredChunk> {
        if (sorted.size <= 1) return sorted
        val topScore = sorted.first().combinedScore
        if (topScore <= 0f) return sorted

        val gapLimit = topScore * threshold
        val result = mutableListOf(sorted.first())

        for (i in 1 until sorted.size) {
            val gap = sorted[i - 1].combinedScore - sorted[i].combinedScore
            if (gap > gapLimit) break
            result.add(sorted[i])
        }
        return result
    }

    private val STOP_WORDS = setOf(
        "the", "is", "at", "which", "on", "a", "an", "and", "or", "but",
        "in", "with", "to", "for", "of", "it", "this", "that", "by",
        "from", "as", "be", "was", "are", "been", "has", "had", "do",
        "does", "did", "will", "would", "could", "should", "can", "may",
        "not", "no", "if", "then", "so", "up", "out", "about", "into",
        "how", "what", "when", "where", "who", "why",
        // Russian
        "и", "в", "на", "с", "по", "из", "за", "к", "от", "до",
        "не", "но", "что", "как", "это", "он", "она", "они", "мы",
        "вы", "его", "её", "их", "был", "для", "то", "же", "ли"
    )
}

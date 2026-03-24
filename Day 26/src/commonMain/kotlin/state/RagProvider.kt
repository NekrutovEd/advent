package state

/**
 * Result of a RAG search: relevant chunks found in the document index.
 */
data class RagResult(
    val chunks: List<RagChunk>,
    val queryTimeMs: Long = 0,
    /** Number of candidates fetched before reranking (0 = no reranking). */
    val candidateCount: Int = 0,
    /** The query actually used for search (may differ from original if rewritten). */
    val effectiveQuery: String = "",
    /** Whether the top result score is below the confidence threshold (triggers "I don't know" mode). */
    val lowConfidence: Boolean = false
)

data class RagChunk(
    val text: String,
    val source: String,
    val section: String,
    val score: Float
)

/**
 * RAG search mode — controls the retrieval pipeline.
 */
enum class RagMode(val label: String) {
    PLAIN("Plain"),
    RERANKED("Reranked"),
    FULL("Full (rewrite + rerank)")
}

/**
 * Interface for RAG (Retrieval-Augmented Generation) providers.
 * Implemented by platform-specific code that has access to embeddings and document index.
 */
interface RagProvider {
    /** Whether the index is loaded and ready for queries. */
    val isReady: Boolean

    /** Load or reload the document index from disk. */
    suspend fun loadIndex(indexPath: String)

    /** Search for chunks relevant to the given query (basic mode). */
    suspend fun search(query: String, topK: Int = 5, minScore: Float = 0.3f): RagResult

    /** Search with configurable mode: plain / reranked / full (query rewrite + rerank). */
    suspend fun search(query: String, mode: RagMode): RagResult = search(query)

    /** Confidence threshold: if top chunk score is below this, trigger "I don't know" mode.
     *  Set conservatively low to accommodate multilingual corpora (Russian+English embeddings). */
    val confidenceThreshold: Float get() = 0.2f

    /** Build a context string from RAG results, suitable for injection into the prompt. */
    fun buildContext(result: RagResult): String {
        if (result.chunks.isEmpty()) return buildLowConfidenceContext()
        if (result.lowConfidence) return buildLowConfidenceContext()
        return buildString {
            appendLine("[RAG Context — retrieved from document index]")
            if (result.candidateCount > 0) {
                appendLine("Pipeline: ${result.chunks.size} results selected from ${result.candidateCount} candidates")
            }
            if (result.effectiveQuery.isNotBlank()) {
                appendLine("Effective query: ${result.effectiveQuery}")
            }
            appendLine()
            result.chunks.forEachIndexed { i, chunk ->
                appendLine("--- Source ${i + 1}: ${chunk.source} (${chunk.section}) [score: ${"%.2f".format(chunk.score)}] ---")
                appendLine(chunk.text)
                appendLine()
            }
            appendLine("--- End of RAG context ---")
            appendLine()
            appendLine(CITATION_INSTRUCTIONS)
        }
    }

    private fun buildLowConfidenceContext(): String = buildString {
        appendLine("[RAG Context — no relevant documents found]")
        appendLine()
        appendLine(LOW_CONFIDENCE_INSTRUCTIONS)
    }

    companion object {
        /** Instructions injected into the prompt to enforce structured citations. */
        const val CITATION_INSTRUCTIONS = """IMPORTANT — you MUST follow these rules when answering:
1. ANSWER: Provide a clear answer to the user's question based ONLY on the RAG context above.
2. SOURCES: After your answer, include a "Sources:" section listing every source you used. Format each as:
   - [source_file (section/chunk_id)]
3. QUOTES: After sources, include a "Quotes:" section with verbatim excerpts from the context that support your answer. Format each as:
   > "exact quote from the chunk" — source_file (section)
4. Do NOT invent information not present in the context. If the context does not contain enough information, say so explicitly.
5. Every claim in your answer MUST be backed by at least one quote from the context."""

        /** Instructions for low-confidence / no-results mode. */
        const val LOW_CONFIDENCE_INSTRUCTIONS = """The document index did not return sufficiently relevant results for this query.
You MUST respond with:
1. A clear statement that you don't have enough information to answer this question from the available documents.
2. Suggest the user rephrase or clarify their question.
Do NOT attempt to answer from your general knowledge — only document-grounded answers are allowed in RAG mode."""
    }
}

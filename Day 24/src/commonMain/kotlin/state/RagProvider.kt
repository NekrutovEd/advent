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
    val effectiveQuery: String = ""
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

    /** Build a context string from RAG results, suitable for injection into the prompt. */
    fun buildContext(result: RagResult): String {
        if (result.chunks.isEmpty()) return ""
        return buildString {
            appendLine("[RAG Context — retrieved from document index]")
            if (result.candidateCount > 0) {
                appendLine("Pipeline: ${result.chunks.size} results selected from ${result.candidateCount} candidates")
            }
            if (result.effectiveQuery.isNotBlank()) {
                appendLine("Effective query: ${result.effectiveQuery}")
            }
            appendLine("The following excerpts are relevant to the user's question:")
            appendLine()
            result.chunks.forEachIndexed { i, chunk ->
                appendLine("--- Source ${i + 1}: ${chunk.source} (${chunk.section}) [score: ${"%.2f".format(chunk.score)}] ---")
                appendLine(chunk.text)
                appendLine()
            }
            appendLine("--- End of RAG context ---")
            appendLine("Use this context to inform your answer. Cite sources when applicable.")
        }
    }
}

package state

/**
 * Result of a RAG search: relevant chunks found in the document index.
 */
data class RagResult(
    val chunks: List<RagChunk>,
    val queryTimeMs: Long = 0
)

data class RagChunk(
    val text: String,
    val source: String,
    val section: String,
    val score: Float
)

/**
 * Interface for RAG (Retrieval-Augmented Generation) providers.
 * Implemented by platform-specific code that has access to embeddings and document index.
 */
interface RagProvider {
    /** Whether the index is loaded and ready for queries. */
    val isReady: Boolean

    /** Load or reload the document index from disk. */
    suspend fun loadIndex(indexPath: String)

    /** Search for chunks relevant to the given query. */
    suspend fun search(query: String, topK: Int = 5, minScore: Float = 0.3f): RagResult

    /** Build a context string from RAG results, suitable for injection into the prompt. */
    fun buildContext(result: RagResult): String {
        if (result.chunks.isEmpty()) return ""
        return buildString {
            appendLine("[RAG Context — retrieved from document index]")
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

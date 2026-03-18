package indexing

import state.RagChunk
import state.RagProvider
import state.RagResult

/**
 * Desktop RAG engine: loads a pre-built document index and provides
 * semantic search over its chunks using embeddings.
 */
class RagEngine(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com",
    private val embeddingModel: String = "text-embedding-3-small"
) : RagProvider {

    private var index: DocumentIndex? = null
    private var embeddingClient: EmbeddingClient? = null

    override val isReady: Boolean get() = index != null && index!!.size > 0

    override suspend fun loadIndex(indexPath: String) {
        val newIndex = DocumentIndex()
        newIndex.load(indexPath)
        index = newIndex
        if (embeddingClient == null) {
            embeddingClient = EmbeddingClient(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = embeddingModel
            )
        }
    }

    override suspend fun search(query: String, topK: Int, minScore: Float): RagResult {
        val idx = index ?: return RagResult(emptyList())
        val client = embeddingClient ?: return RagResult(emptyList())

        val startTime = System.currentTimeMillis()
        val queryEmbedding = client.embedSingle(query)
        val results = idx.search(queryEmbedding, topK, minScore)
        val elapsed = System.currentTimeMillis() - startTime

        return RagResult(
            chunks = results.map { sr ->
                RagChunk(
                    text = sr.chunk.text,
                    source = sr.chunk.metadata.source,
                    section = sr.chunk.metadata.section,
                    score = sr.score
                )
            },
            queryTimeMs = elapsed
        )
    }
}

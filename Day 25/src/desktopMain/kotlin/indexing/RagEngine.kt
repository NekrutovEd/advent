package indexing

import state.RagChunk
import state.RagMode
import state.RagProvider
import state.RagResult

/**
 * Desktop RAG engine: loads a pre-built document index and provides
 * semantic search over its chunks using embeddings.
 *
 * Supports three search modes:
 * - PLAIN: basic cosine similarity search
 * - RERANKED: fetch more candidates, then rerank with keyword overlap + score-gap filter
 * - FULL: rewrite query + reranked search
 */
class RagEngine(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com",
    private val embeddingModel: String = "text-embedding-3-small"
) : RagProvider {

    private var index: DocumentIndex? = null
    private var embeddingClient: EmbeddingClient? = null

    /** Default pipeline config — can be overridden per-search. */
    var config: RagConfig = RagConfig()

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

    /**
     * Basic search (backward-compatible): plain cosine similarity.
     */
    override suspend fun search(query: String, topK: Int, minScore: Float): RagResult {
        val idx = index ?: return RagResult(emptyList())
        val client = embeddingClient ?: return RagResult(emptyList())

        val startTime = System.currentTimeMillis()
        val queryEmbedding = client.embedSingle(query)
        val results = idx.search(queryEmbedding, topK, minScore)
        val elapsed = System.currentTimeMillis() - startTime

        val chunks = results.map { sr ->
            RagChunk(
                text = sr.chunk.text,
                source = sr.chunk.metadata.source,
                section = sr.chunk.metadata.section,
                score = sr.score
            )
        }
        val topScore = chunks.maxOfOrNull { it.score } ?: 0f
        return RagResult(
            chunks = chunks,
            queryTimeMs = elapsed,
            effectiveQuery = query,
            lowConfidence = topScore < confidenceThreshold
        )
    }

    /**
     * Mode-aware search with reranking pipeline.
     */
    override suspend fun search(query: String, mode: RagMode): RagResult {
        return when (mode) {
            RagMode.PLAIN -> search(query, config.finalTopK, config.minScore)
            RagMode.RERANKED -> searchReranked(query, rewriteQuery = false)
            RagMode.FULL -> searchReranked(query, rewriteQuery = true)
        }
    }

    /**
     * Two-stage search: (1) fetch wide candidate set, (2) rerank + filter.
     */
    private suspend fun searchReranked(query: String, rewriteQuery: Boolean): RagResult {
        val idx = index ?: return RagResult(emptyList())
        val client = embeddingClient ?: return RagResult(emptyList())

        val startTime = System.currentTimeMillis()

        // Stage 0: optional query rewrite
        val effectiveQuery = if (rewriteQuery) Reranker.rewriteQuery(query) else query

        // Stage 1: broad vector search — fetch more candidates than we need
        val queryEmbedding = client.embedSingle(effectiveQuery)
        val candidates = idx.search(queryEmbedding, config.fetchTopK, config.minScore)

        val candidateChunks = candidates.map { sr ->
            RagChunk(
                text = sr.chunk.text,
                source = sr.chunk.metadata.source,
                section = sr.chunk.metadata.section,
                score = sr.score
            )
        }

        // Stage 2: heuristic reranking
        val reranked = Reranker.rerank(query, candidateChunks, config)

        val elapsed = System.currentTimeMillis() - startTime

        val topScore = reranked.maxOfOrNull { it.score } ?: 0f
        return RagResult(
            chunks = reranked,
            queryTimeMs = elapsed,
            candidateCount = candidates.size,
            effectiveQuery = if (rewriteQuery && effectiveQuery != query) effectiveQuery else query,
            lowConfidence = topScore < confidenceThreshold
        )
    }
}

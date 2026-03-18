package indexing

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A single indexed entry: chunk + its embedding vector.
 */
data class IndexEntry(
    val chunk: DocumentChunk,
    val embedding: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IndexEntry) return false
        return chunk == other.chunk && embedding.contentEquals(other.embedding)
    }
    override fun hashCode() = chunk.hashCode() * 31 + embedding.contentHashCode()
}

/**
 * Search result with similarity score.
 */
data class SearchResult(
    val chunk: DocumentChunk,
    val score: Float
)

/**
 * JSON-based document index with vector similarity search.
 * Stores chunks, metadata, and embeddings in a single JSON file.
 */
class DocumentIndex {

    private val entries = mutableListOf<IndexEntry>()
    private var indexName = "default"
    private var embeddingDimension = 0

    val size: Int get() = entries.size

    /**
     * Add entries to the index.
     */
    fun addAll(newEntries: List<IndexEntry>) {
        if (newEntries.isNotEmpty()) {
            embeddingDimension = newEntries.first().embedding.size
        }
        entries.addAll(newEntries)
    }

    /**
     * Search the index using cosine similarity.
     * Returns top-k results sorted by descending similarity.
     */
    fun search(queryEmbedding: FloatArray, topK: Int = 5, minScore: Float = 0f): List<SearchResult> {
        return entries
            .map { entry ->
                SearchResult(entry.chunk, cosineSimilarity(queryEmbedding, entry.embedding))
            }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(topK)
    }

    /**
     * Get statistics about the index.
     */
    fun stats(): IndexStats {
        val bySource = entries.groupBy { it.chunk.metadata.source }
        val byStrategy = entries.groupBy { it.chunk.metadata.strategy }
        val avgChunkLen = if (entries.isEmpty()) 0.0
            else entries.map { it.chunk.text.length }.average()

        return IndexStats(
            totalChunks = entries.size,
            totalDocuments = bySource.size,
            embeddingDimension = embeddingDimension,
            avgChunkLength = avgChunkLen,
            chunksByStrategy = byStrategy.mapValues { it.value.size },
            chunksBySource = bySource.mapValues { it.value.size },
            indexName = indexName
        )
    }

    /**
     * Save the index to a JSON file.
     */
    fun save(path: String) {
        val file = File(path)
        file.parentFile?.mkdirs()

        val root = JSONObject()
        root.put("index_name", indexName)
        root.put("embedding_dimension", embeddingDimension)
        root.put("total_chunks", entries.size)
        root.put("created_at", System.currentTimeMillis())

        val entriesArray = JSONArray()
        for (entry in entries) {
            val obj = JSONObject()
            obj.put("text", entry.chunk.text)

            val meta = JSONObject()
            meta.put("chunk_id", entry.chunk.metadata.chunkId)
            meta.put("source", entry.chunk.metadata.source)
            meta.put("title", entry.chunk.metadata.title)
            meta.put("section", entry.chunk.metadata.section)
            meta.put("strategy", entry.chunk.metadata.strategy)
            meta.put("char_offset", entry.chunk.metadata.charOffset)
            meta.put("chunk_index", entry.chunk.metadata.chunkIndex)
            obj.put("metadata", meta)

            // Store embedding as base64-encoded floats for compactness
            val embArray = JSONArray()
            for (f in entry.embedding) embArray.put(f.toDouble())
            obj.put("embedding", embArray)

            entriesArray.put(obj)
        }
        root.put("entries", entriesArray)

        file.writeText(root.toString(2), Charsets.UTF_8)
    }

    /**
     * Load the index from a JSON file.
     */
    fun load(path: String) {
        val file = File(path)
        if (!file.exists()) throw RuntimeException("Index file not found: $path")

        val root = JSONObject(file.readText(Charsets.UTF_8))
        indexName = root.optString("index_name", "default")
        embeddingDimension = root.optInt("embedding_dimension", 0)

        val entriesArray = root.getJSONArray("entries")
        entries.clear()

        for (i in 0 until entriesArray.length()) {
            val obj = entriesArray.getJSONObject(i)
            val text = obj.getString("text")
            val meta = obj.getJSONObject("metadata")

            val chunk = DocumentChunk(
                text = text,
                metadata = ChunkMetadata(
                    chunkId = meta.getString("chunk_id"),
                    source = meta.getString("source"),
                    title = meta.getString("title"),
                    section = meta.getString("section"),
                    strategy = meta.getString("strategy"),
                    charOffset = meta.optInt("char_offset", 0),
                    chunkIndex = meta.optInt("chunk_index", 0)
                )
            )

            val embArray = obj.getJSONArray("embedding")
            val embedding = FloatArray(embArray.length()) { j -> embArray.getDouble(j).toFloat() }

            entries.add(IndexEntry(chunk, embedding))
        }
    }

    fun clear() {
        entries.clear()
        embeddingDimension = 0
    }

    fun setName(name: String) {
        indexName = name
    }

    fun getEntries(): List<IndexEntry> = entries.toList()
}

data class IndexStats(
    val totalChunks: Int,
    val totalDocuments: Int,
    val embeddingDimension: Int,
    val avgChunkLength: Double,
    val chunksByStrategy: Map<String, Int>,
    val chunksBySource: Map<String, Int>,
    val indexName: String
)

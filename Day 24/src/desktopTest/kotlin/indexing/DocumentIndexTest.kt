package indexing

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DocumentIndexTest {

    @Test
    fun `index stores and retrieves entries`() {
        val index = DocumentIndex()
        val entries = listOf(
            IndexEntry(
                chunk = DocumentChunk(
                    text = "Hello world",
                    metadata = ChunkMetadata("id1", "/a.txt", "a.txt", "intro", "fixed", 0, 0)
                ),
                embedding = floatArrayOf(1f, 0f, 0f)
            ),
            IndexEntry(
                chunk = DocumentChunk(
                    text = "Goodbye world",
                    metadata = ChunkMetadata("id2", "/b.txt", "b.txt", "outro", "fixed", 0, 0)
                ),
                embedding = floatArrayOf(0f, 1f, 0f)
            )
        )
        index.addAll(entries)
        assertEquals(2, index.size)
    }

    @Test
    fun `search returns results sorted by similarity`() {
        val index = DocumentIndex()
        val entries = listOf(
            IndexEntry(
                chunk = DocumentChunk("Close match", ChunkMetadata("id1", "/a.txt", "a.txt", "s1", "fixed", 0, 0)),
                embedding = floatArrayOf(0.9f, 0.1f, 0f)
            ),
            IndexEntry(
                chunk = DocumentChunk("Far match", ChunkMetadata("id2", "/b.txt", "b.txt", "s2", "fixed", 0, 1)),
                embedding = floatArrayOf(0f, 0f, 1f)
            ),
            IndexEntry(
                chunk = DocumentChunk("Medium match", ChunkMetadata("id3", "/c.txt", "c.txt", "s3", "fixed", 0, 2)),
                embedding = floatArrayOf(0.5f, 0.5f, 0f)
            )
        )
        index.addAll(entries)

        val query = floatArrayOf(1f, 0f, 0f) // Most similar to "Close match"
        val results = index.search(query, topK = 3)

        assertEquals(3, results.size)
        assertEquals("Close match", results[0].chunk.text)
        assertTrue(results[0].score > results[1].score)
    }

    @Test
    fun `search respects min_score filter`() {
        val index = DocumentIndex()
        index.addAll(listOf(
            IndexEntry(
                chunk = DocumentChunk("High", ChunkMetadata("id1", "/a.txt", "a.txt", "s", "fixed", 0, 0)),
                embedding = floatArrayOf(1f, 0f)
            ),
            IndexEntry(
                chunk = DocumentChunk("Low", ChunkMetadata("id2", "/b.txt", "b.txt", "s", "fixed", 0, 1)),
                embedding = floatArrayOf(-1f, 0f)
            )
        ))

        val results = index.search(floatArrayOf(1f, 0f), topK = 10, minScore = 0.5f)
        assertEquals(1, results.size)
        assertEquals("High", results[0].chunk.text)
    }

    @Test
    fun `search with top_k limits results`() {
        val index = DocumentIndex()
        val entries = (0..9).map { i ->
            val emb = FloatArray(3) { if (it == 0) 1f else 0f }
            IndexEntry(
                chunk = DocumentChunk("Chunk $i", ChunkMetadata("id$i", "/d.txt", "d.txt", "s$i", "fixed", 0, i)),
                embedding = emb
            )
        }
        index.addAll(entries)

        val results = index.search(floatArrayOf(1f, 0f, 0f), topK = 3)
        assertEquals(3, results.size)
    }

    @Test
    fun `stats reports correct counts`() {
        val index = DocumentIndex()
        index.addAll(listOf(
            IndexEntry(
                chunk = DocumentChunk("A", ChunkMetadata("id1", "/a.txt", "a.txt", "s", "fixed", 0, 0)),
                embedding = floatArrayOf(1f, 0f)
            ),
            IndexEntry(
                chunk = DocumentChunk("B", ChunkMetadata("id2", "/a.txt", "a.txt", "s", "structural", 0, 1)),
                embedding = floatArrayOf(0f, 1f)
            ),
            IndexEntry(
                chunk = DocumentChunk("C", ChunkMetadata("id3", "/b.txt", "b.txt", "s", "fixed", 0, 0)),
                embedding = floatArrayOf(0.5f, 0.5f)
            )
        ))

        val stats = index.stats()
        assertEquals(3, stats.totalChunks)
        assertEquals(2, stats.totalDocuments)
        assertEquals(2, stats.embeddingDimension)
        assertEquals(2, stats.chunksByStrategy["fixed"])
        assertEquals(1, stats.chunksByStrategy["structural"])
    }

    @Test
    fun `save and load preserves index`(@TempDir tempDir: File) {
        val index = DocumentIndex()
        index.setName("test-index")
        index.addAll(listOf(
            IndexEntry(
                chunk = DocumentChunk(
                    "Test content here",
                    ChunkMetadata("id1", "/docs/test.md", "test.md", "intro", "fixed", 42, 0)
                ),
                embedding = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
            ),
            IndexEntry(
                chunk = DocumentChunk(
                    "More content",
                    ChunkMetadata("id2", "/docs/test.md", "test.md", "body", "structural", 100, 1)
                ),
                embedding = floatArrayOf(0.5f, 0.6f, 0.7f, 0.8f)
            )
        ))

        val path = File(tempDir, "test-index.json").absolutePath
        index.save(path)

        // Load into new index
        val loaded = DocumentIndex()
        loaded.load(path)

        assertEquals(2, loaded.size)
        val stats = loaded.stats()
        assertEquals("test-index", stats.indexName)
        assertEquals(4, stats.embeddingDimension)

        // Verify search still works
        val results = loaded.search(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), topK = 1)
        assertEquals(1, results.size)
        assertEquals("Test content here", results[0].chunk.text)
        assertEquals("intro", results[0].chunk.metadata.section)
        assertEquals(42, results[0].chunk.metadata.charOffset)
    }

    @Test
    fun `clear empties the index`() {
        val index = DocumentIndex()
        index.addAll(listOf(
            IndexEntry(
                chunk = DocumentChunk("X", ChunkMetadata("id", "/x.txt", "x.txt", "s", "fixed", 0, 0)),
                embedding = floatArrayOf(1f)
            )
        ))
        assertEquals(1, index.size)

        index.clear()
        assertEquals(0, index.size)
    }
}

class CosineSimilarityTest {

    @Test
    fun `identical vectors have similarity 1`() {
        val v = floatArrayOf(1f, 2f, 3f)
        assertEquals(1f, cosineSimilarity(v, v), 0.001f)
    }

    @Test
    fun `orthogonal vectors have similarity 0`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        assertEquals(0f, cosineSimilarity(a, b), 0.001f)
    }

    @Test
    fun `opposite vectors have similarity -1`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(-1f, 0f)
        assertEquals(-1f, cosineSimilarity(a, b), 0.001f)
    }

    @Test
    fun `similarity is between -1 and 1`() {
        val a = floatArrayOf(0.3f, 0.7f, 0.1f)
        val b = floatArrayOf(0.9f, 0.2f, 0.5f)
        val sim = cosineSimilarity(a, b)
        assertTrue(sim in -1f..1f)
    }
}

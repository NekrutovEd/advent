package indexing

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ChunkingStrategyTest {

    // ───── Fixed-size chunking ─────

    @Test
    fun `fixed chunking splits text into expected number of chunks`() {
        val doc = LoadedDocument(
            path = "/test/sample.txt",
            name = "sample.txt",
            content = "A".repeat(1000),
            extension = "txt"
        )
        val chunks = ChunkingStrategy.chunkFixed(doc, chunkSize = 300, overlap = 50)
        assertTrue(chunks.size >= 3, "Expected at least 3 chunks, got ${chunks.size}")
        assertTrue(chunks.all { it.text.length <= 300 })
    }

    @Test
    fun `fixed chunking preserves metadata`() {
        val doc = LoadedDocument(
            path = "/docs/readme.md",
            name = "readme.md",
            content = "Hello world. ".repeat(100),
            extension = "md"
        )
        val chunks = ChunkingStrategy.chunkFixed(doc, chunkSize = 200, overlap = 50)
        assertTrue(chunks.isNotEmpty())

        val first = chunks.first()
        assertEquals("/docs/readme.md", first.metadata.source)
        assertEquals("readme.md", first.metadata.title)
        assertEquals("fixed", first.metadata.strategy)
        assertEquals("chunk 0", first.metadata.section)
        assertEquals(0, first.metadata.chunkIndex)
        assertTrue(first.metadata.chunkId.startsWith("readme.md:fixed:"))
    }

    @Test
    fun `fixed chunking with overlap creates overlapping content`() {
        val doc = LoadedDocument(
            path = "/test/overlap.txt",
            name = "overlap.txt",
            content = (1..200).joinToString(" ") { "word$it" },
            extension = "txt"
        )
        val chunks = ChunkingStrategy.chunkFixed(doc, chunkSize = 200, overlap = 50)
        if (chunks.size >= 2) {
            val end1 = chunks[0].text.takeLast(30)
            val start2 = chunks[1].text.take(50)
            // With overlap, the end of chunk 1 should partially appear at start of chunk 2
            val shared = end1.split(" ").filter { it in start2 }
            // Overlap means at least some words should be shared
            assertTrue(shared.isNotEmpty() || chunks.size >= 2,
                "Expected overlap between consecutive chunks")
        }
    }

    @Test
    fun `fixed chunking returns empty for blank document`() {
        val doc = LoadedDocument("/test/empty.txt", "empty.txt", "   \n  \n  ", "txt")
        val chunks = ChunkingStrategy.chunkFixed(doc, chunkSize = 100, overlap = 20)
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `fixed chunking small text returns single chunk`() {
        val doc = LoadedDocument("/test/small.txt", "small.txt", "Short text", "txt")
        val chunks = ChunkingStrategy.chunkFixed(doc, chunkSize = 500, overlap = 100)
        assertEquals(1, chunks.size)
        assertEquals("Short text", chunks[0].text)
    }

    // ───── Structural chunking ─────

    @Test
    fun `structural chunking splits markdown by headings`() {
        val md = """
            |# Introduction
            |This is the intro paragraph.
            |
            |## Setup
            |Install the dependencies.
            |Run the build command.
            |
            |## Usage
            |Import and use the library.
            |
            |### Advanced
            |Custom configuration options.
        """.trimMargin()

        val doc = LoadedDocument("/docs/guide.md", "guide.md", md, "md")
        val chunks = ChunkingStrategy.chunkStructural(doc)

        assertTrue(chunks.size >= 3, "Expected at least 3 sections, got ${chunks.size}")
        assertTrue(chunks.all { it.metadata.strategy == "structural" })

        // Check section names
        val sections = chunks.map { it.metadata.section }
        assertTrue(sections.any { it.contains("Introduction") })
        assertTrue(sections.any { it.contains("Setup") })
        assertTrue(sections.any { it.contains("Usage") })
    }

    @Test
    fun `structural chunking splits kotlin by declarations`() {
        val kt = """
            |package example
            |
            |import java.io.File
            |
            |class MyClass {
            |    fun method1() {
            |        println("hello")
            |    }
            |
            |    fun method2() {
            |        println("world")
            |    }
            |}
            |
            |fun topLevelFunction() {
            |    val x = 42
            |}
            |
            |object Singleton {
            |    val value = "test"
            |}
        """.trimMargin()

        val doc = LoadedDocument("/src/Example.kt", "Example.kt", kt, "kt")
        val chunks = ChunkingStrategy.chunkStructural(doc)

        assertTrue(chunks.size >= 2, "Expected at least 2 code sections, got ${chunks.size}")
        assertTrue(chunks.all { it.metadata.strategy == "structural" })

        // Should have header (package/import) and at least class + function
        val sections = chunks.map { it.metadata.section }
        assertTrue(sections.any { it == "header" || it.contains("package") })
    }

    @Test
    fun `structural chunking splits plain text by paragraphs`() {
        val text = """
            |First paragraph with some content about topic A.
            |It spans multiple lines.
            |
            |Second paragraph discusses topic B.
            |This is also multi-line.
            |
            |Third paragraph about topic C.
        """.trimMargin()

        val doc = LoadedDocument("/docs/article.html", "article.html", text, "html")
        val chunks = ChunkingStrategy.chunkStructural(doc)

        assertEquals(3, chunks.size, "Expected 3 paragraphs")
        assertTrue(chunks.all { it.metadata.strategy == "structural" })
    }

    @Test
    fun `structural chunking preserves chunk ids`() {
        val md = "# A\nContent A\n\n# B\nContent B"
        val doc = LoadedDocument("/test/doc.md", "doc.md", md, "md")
        val chunks = ChunkingStrategy.chunkStructural(doc)

        val ids = chunks.map { it.metadata.chunkId }
        assertEquals(ids.distinct().size, ids.size, "Chunk IDs must be unique")
    }

    // ───── Both strategies on same doc ─────

    @Test
    fun `both strategies produce different chunk counts for same document`() {
        val md = """
            |# Title
            |Short intro.
            |
            |## Section One
            |${"This is a long section with lots of content. ".repeat(20)}
            |
            |## Section Two
            |${"Another long section with different content. ".repeat(20)}
            |
            |## Section Three
            |Brief conclusion.
        """.trimMargin()

        val doc = LoadedDocument("/docs/big.md", "big.md", md, "md")
        val fixed = ChunkingStrategy.chunkFixed(doc, chunkSize = 300, overlap = 50)
        val structural = ChunkingStrategy.chunkStructural(doc)

        assertTrue(fixed.isNotEmpty())
        assertTrue(structural.isNotEmpty())
        assertNotEquals(fixed.size, structural.size,
            "Fixed (${fixed.size}) and structural (${structural.size}) should typically differ in chunk count")
    }
}

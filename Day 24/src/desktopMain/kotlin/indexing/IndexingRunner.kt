package indexing

import java.io.File

/**
 * Standalone runner: loads documents, compares chunking strategies,
 * generates embeddings, builds and saves the index, runs test queries.
 *
 * Usage: gradlew runIndexing -PapiKey=... [-PbaseUrl=...] [-Pmodel=...]
 */
fun main() {
    val apiKey = System.getProperty("apiKey") ?: System.getenv("OPENAI_API_KEY")
    ?: error("Provide apiKey via -PapiKey=... or OPENAI_API_KEY env var")
    val baseUrl = System.getProperty("baseUrl") ?: System.getenv("EMBEDDING_BASE_URL") ?: "https://api.openai.com"
    val model = System.getProperty("embModel") ?: System.getenv("EMBEDDING_MODEL") ?: "text-embedding-3-small"
    val corpusDir = System.getProperty("corpusDir") ?: "docs/corpus"

    println("=== Document Indexing Pipeline ===")
    println("Corpus: $corpusDir")
    println("API: $baseUrl  Model: $model")
    println()

    // ─── Step 1: Load documents ───
    val dir = File(corpusDir)
    require(dir.exists() && dir.isDirectory) { "Corpus directory not found: $corpusDir" }

    val allowedExts = setOf("md", "txt", "kt", "java", "py", "js", "ts")
    val docs = dir.walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in allowedExts }
        .map { f ->
            LoadedDocument(
                path = f.absolutePath.replace('\\', '/'),
                name = f.name,
                content = f.readText(Charsets.UTF_8),
                extension = f.extension.lowercase()
            )
        }
        .filter { it.content.isNotBlank() }
        .toList()

    val totalChars = docs.sumOf { it.content.length.toLong() }
    println("Loaded ${docs.size} documents, $totalChars chars (~${totalChars / 2000} pages)")
    println()

    // ─── Step 2: Compare chunking strategies ───
    println("=== Chunking Strategy Comparison ===")
    println()

    val fixedChunks = docs.flatMap { ChunkingStrategy.chunkFixed(it, chunkSize = 500, overlap = 100) }
    val structuralChunks = docs.flatMap { ChunkingStrategy.chunkStructural(it) }

    printStrategyStats("Fixed-size (500 chars, 100 overlap)", fixedChunks)
    printStrategyStats("Structural (headings / code declarations)", structuralChunks)

    // ─── Step 3: Generate embeddings for both strategies ───
    val allChunks = fixedChunks + structuralChunks
    println("=== Generating Embeddings ===")
    println("Total chunks to embed: ${allChunks.size}")

    val embClient = EmbeddingClient(baseUrl = baseUrl, apiKey = apiKey, model = model)
    val startTime = System.currentTimeMillis()
    val embeddings = embClient.embed(allChunks.map { it.text })
    val elapsed = System.currentTimeMillis() - startTime

    println("Generated ${embeddings.size} embeddings in ${elapsed}ms")
    println("Dimension: ${embeddings.firstOrNull()?.size ?: 0}")
    println()

    // ─── Step 4: Build and save index ───
    val index = DocumentIndex()
    index.setName("ai-advent-corpus")
    index.addAll(allChunks.zip(embeddings).map { (c, e) -> IndexEntry(c, e) })

    val indexPath = "document-index.json"
    index.save(indexPath)
    val indexSize = File(indexPath).length()
    println("Index saved: $indexPath (${indexSize / 1024} KB)")
    println()

    // ─── Step 5: Test queries ───
    println("=== Test Searches ===")
    val queries = listOf(
        "MCP server JSON-RPC protocol",
        "how to generate embeddings",
        "Android app architecture",
        "Git operations and commands",
        "task scheduling and cron"
    )

    for (query in queries) {
        println("\nQuery: \"$query\"")
        val qEmb = embClient.embedSingle(query)
        val results = index.search(qEmb, topK = 3, minScore = 0.1f)
        for ((i, r) in results.withIndex()) {
            println("  ${i + 1}. [${r.chunk.metadata.strategy}] ${r.chunk.metadata.title} / ${r.chunk.metadata.section}")
            println("     Score: ${"%.4f".format(r.score)} | ${r.chunk.text.take(80).replace('\n', ' ')}...")
        }
    }

    // ─── Step 6: Write report ───
    val report = buildReport(docs, fixedChunks, structuralChunks, embeddings, elapsed, queries, index, embClient)
    File("report.txt").writeText(report, Charsets.UTF_8)
    println("\n\nReport saved: report.txt")
}

private fun printStrategyStats(name: String, chunks: List<DocumentChunk>) {
    val lengths = chunks.map { it.text.length }
    println("── $name ──")
    println("  Chunks: ${chunks.size}")
    println("  Avg length: ${if (lengths.isEmpty()) 0 else lengths.average().toInt()} chars")
    println("  Min: ${lengths.minOrNull() ?: 0}  Max: ${lengths.maxOrNull() ?: 0}")
    println("  By source: ${chunks.groupBy { it.metadata.title }.map { "${it.key}: ${it.value.size}" }.joinToString(", ")}")
    println()
}

private fun buildReport(
    docs: List<LoadedDocument>,
    fixedChunks: List<DocumentChunk>,
    structuralChunks: List<DocumentChunk>,
    embeddings: List<FloatArray>,
    embeddingTimeMs: Long,
    queries: List<String>,
    index: DocumentIndex,
    embClient: EmbeddingClient
): String {
    val sb = StringBuilder()
    sb.appendLine("╔══════════════════════════════════════════════════════════╗")
    sb.appendLine("║       Day 21: Document Indexing Pipeline — Report       ║")
    sb.appendLine("╚══════════════════════════════════════════════════════════╝")
    sb.appendLine()

    // Corpus
    sb.appendLine("═══ Corpus ═══")
    sb.appendLine("Documents: ${docs.size}")
    sb.appendLine("Total characters: ${docs.sumOf { it.content.length.toLong() }}")
    sb.appendLine("Estimated pages: ~${docs.sumOf { it.content.length.toLong() } / 2000}")
    sb.appendLine()
    sb.appendLine("Files:")
    for (doc in docs.sortedByDescending { it.content.length }) {
        sb.appendLine("  ${doc.name.padEnd(30)} ${doc.extension.padEnd(4)} ${doc.content.length} chars")
    }
    sb.appendLine()

    // Strategy comparison
    sb.appendLine("═══ Chunking Strategy Comparison ═══")
    sb.appendLine()
    val totalText = docs.sumOf { it.content.length.toLong() }

    // Fixed
    val fLens = fixedChunks.map { it.text.length }
    sb.appendLine("Strategy 1: Fixed-size (chunk_size=500, overlap=100)")
    sb.appendLine("  Chunks:      ${fixedChunks.size}")
    sb.appendLine("  Avg length:  ${fLens.average().toInt()} chars")
    sb.appendLine("  Min / Max:   ${fLens.min()} / ${fLens.max()}")
    sb.appendLine("  Median:      ${fLens.sorted()[fLens.size / 2]}")
    sb.appendLine("  Coverage:    ${fixedChunks.sumOf { it.text.length.toLong() }} chars (${fixedChunks.sumOf { it.text.length.toLong() } * 100 / totalText}%)")
    sb.appendLine()

    // Structural
    val sLens = structuralChunks.map { it.text.length }
    sb.appendLine("Strategy 2: Structural (headings / code declarations / paragraphs)")
    sb.appendLine("  Chunks:      ${structuralChunks.size}")
    sb.appendLine("  Avg length:  ${sLens.average().toInt()} chars")
    sb.appendLine("  Min / Max:   ${sLens.min()} / ${sLens.max()}")
    sb.appendLine("  Median:      ${sLens.sorted()[sLens.size / 2]}")
    sb.appendLine("  Coverage:    ${structuralChunks.sumOf { it.text.length.toLong() }} chars (${structuralChunks.sumOf { it.text.length.toLong() } * 100 / totalText}%)")
    sb.appendLine()

    // Sections from structural
    sb.appendLine("  Unique sections (top 20):")
    val sections = structuralChunks.map { it.metadata.section }.distinct()
    for (sec in sections.take(20)) {
        sb.appendLine("    - $sec")
    }
    if (sections.size > 20) sb.appendLine("    ... and ${sections.size - 20} more")
    sb.appendLine()

    // Verdict
    sb.appendLine("Verdict:")
    sb.appendLine("  Fixed-size: ${fixedChunks.size} uniform chunks — good for consistent embedding quality,")
    sb.appendLine("    but may split mid-sentence or mid-function. Overlap mitigates boundary losses.")
    sb.appendLine("  Structural: ${structuralChunks.size} semantically coherent chunks — preserves document")
    sb.appendLine("    structure, but sizes vary widely (${sLens.min()}–${sLens.max()} chars).")
    sb.appendLine("  Combining both strategies in the index gives the best retrieval quality —")
    sb.appendLine("    fixed chunks handle long prose, structural chunks handle code and headings.")
    sb.appendLine()

    // Embeddings
    sb.appendLine("═══ Embeddings ═══")
    sb.appendLine("Model: text-embedding-3-small")
    sb.appendLine("Total embeddings: ${embeddings.size}")
    sb.appendLine("Dimension: ${embeddings.firstOrNull()?.size ?: 0}")
    sb.appendLine("Generation time: ${embeddingTimeMs}ms")
    sb.appendLine("Avg time per chunk: ${"%.1f".format(embeddingTimeMs.toDouble() / embeddings.size)}ms")
    sb.appendLine()

    // Index
    sb.appendLine("═══ Index ═══")
    val stats = index.stats()
    sb.appendLine("Total entries: ${stats.totalChunks}")
    sb.appendLine("Documents: ${stats.totalDocuments}")
    sb.appendLine("By strategy: ${stats.chunksByStrategy}")
    sb.appendLine()

    // Search results
    sb.appendLine("═══ Test Search Results ═══")
    for (query in queries) {
        sb.appendLine()
        sb.appendLine("Query: \"$query\"")
        val qEmb = embClient.embedSingle(query)
        val results = index.search(qEmb, topK = 3, minScore = 0.1f)
        for ((i, r) in results.withIndex()) {
            sb.appendLine("  ${i + 1}. [${r.chunk.metadata.strategy.padEnd(10)}] score=${"%.4f".format(r.score)} | ${r.chunk.metadata.title} / ${r.chunk.metadata.section}")
            sb.appendLine("     ${r.chunk.text.take(100).replace('\n', ' ')}")
        }
    }

    return sb.toString()
}

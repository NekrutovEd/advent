package mcp.server

import indexing.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintStream

/**
 * MCP server for document indexing and retrieval.
 * Communicates via JSON-RPC 2.0 over stdin/stdout.
 *
 * Tools:
 *   load_documents   — scan directory for text files
 *   index_documents  — chunk + embed + store index
 *   search_index     — semantic search over indexed documents
 *   get_index_stats  — show index statistics
 *   compare_chunking — compare fixed vs structural chunking strategies
 */
fun main() {
    val server = IndexingMcpServer()
    server.run()
}

class IndexingMcpServer {

    private val index = DocumentIndex()
    private var lastLoadedDocs = emptyList<LoadedDocument>()
    private var embeddingClient: EmbeddingClient? = null

    // Supported file extensions for document loading
    private val TEXT_EXTENSIONS = setOf("md", "txt", "kt", "java", "py", "js", "ts", "json", "xml", "html", "css", "yml", "yaml", "toml", "properties", "gradle", "bat", "sh")

    private val tools = listOf(
        toolDef(
            "load_documents", "Scan a directory for text documents. Returns list of found files with sizes.",
            prop("directory", "string", "Directory path to scan for documents"),
            prop("extensions", "string", "Comma-separated file extensions to include (default: md,txt,kt,java,py,js,ts)", required = false),
            prop("max_file_size_kb", "integer", "Maximum file size in KB (default: 500)", required = false),
            prop("recursive", "boolean", "Scan subdirectories (default: true)", required = false)
        ),
        toolDef(
            "index_documents", "Index loaded documents: chunk them, generate embeddings, and store the index. " +
                "Requires load_documents to be called first and an API key for embeddings.",
            prop("strategy", "string", "Chunking strategy: 'fixed', 'structural', or 'both' (default: both)"),
            prop("api_key", "string", "API key for the embeddings provider"),
            prop("base_url", "string", "Base URL for the embeddings API (default: https://api.openai.com)", required = false),
            prop("model", "string", "Embedding model name (default: text-embedding-3-small)", required = false),
            prop("chunk_size", "integer", "Chunk size in characters for fixed strategy (default: 500)", required = false),
            prop("overlap", "integer", "Overlap in characters for fixed strategy (default: 100)", required = false),
            prop("save_path", "string", "Path to save the index JSON file (default: ./document-index.json)", required = false)
        ),
        toolDef(
            "search_index", "Search the document index using semantic similarity. Returns top matching chunks.",
            prop("query", "string", "Search query text"),
            prop("top_k", "integer", "Number of results to return (default: 5)", required = false),
            prop("min_score", "number", "Minimum similarity score 0.0-1.0 (default: 0.0)", required = false),
            prop("strategy_filter", "string", "Filter by chunking strategy: 'fixed' or 'structural' (default: all)", required = false),
            prop("api_key", "string", "API key for embedding the query"),
            prop("base_url", "string", "Base URL for the embeddings API (default: https://api.openai.com)", required = false),
            prop("model", "string", "Embedding model name (default: text-embedding-3-small)", required = false)
        ),
        toolDef(
            "get_index_stats", "Get statistics about the current document index: chunk counts, sources, strategies."
        ),
        toolDef(
            "compare_chunking", "Compare fixed-size vs structural chunking strategies on loaded documents. " +
                "Shows chunk count, average size, size distribution, and overlap analysis.",
            prop("chunk_size", "integer", "Chunk size for fixed strategy (default: 500)", required = false),
            prop("overlap", "integer", "Overlap for fixed strategy (default: 100)", required = false)
        ),
        toolDef(
            "load_index", "Load a previously saved index from a JSON file.",
            prop("path", "string", "Path to the index JSON file")
        )
    )

    fun run() {
        val utf8Out = PrintStream(System.out, true, "UTF-8")
        val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val response = handleMessage(line)
            if (response != null) {
                utf8Out.println(response)
                utf8Out.flush()
            }
        }
    }

    internal fun handleMessage(line: String): String? {
        val msg = try { JSONObject(line) } catch (_: Exception) {
            return errorResponse(null, -32700, "Parse error")
        }
        val id = msg.opt("id")
        val method = msg.optString("method", "")
        val params = msg.optJSONObject("params") ?: JSONObject()

        if (id == null || id == JSONObject.NULL) return null

        return when (method) {
            "initialize" -> handleInitialize(id)
            "tools/list" -> handleListTools(id)
            "tools/call" -> handleCallTool(id, params)
            else -> errorResponse(id, -32601, "Method not found: $method")
        }
    }

    private fun handleInitialize(id: Any): String {
        val result = JSONObject()
            .put("protocolVersion", "2024-11-05")
            .put("capabilities", JSONObject().put("tools", JSONObject()))
            .put("serverInfo", JSONObject()
                .put("name", "Document Indexing MCP Server")
                .put("version", "1.0.0"))
        return successResponse(id, result)
    }

    private fun handleListTools(id: Any): String {
        val toolsArray = JSONArray()
        for (tool in tools) toolsArray.put(tool)
        return successResponse(id, JSONObject().put("tools", toolsArray))
    }

    private fun handleCallTool(id: Any, params: JSONObject): String {
        val name = params.optString("name", "")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val result = executeTool(name, args)
        return successResponse(id, result)
    }

    internal fun executeTool(name: String, args: JSONObject): JSONObject {
        return try {
            when (name) {
                "load_documents" -> execLoadDocuments(args)
                "index_documents" -> execIndexDocuments(args)
                "search_index" -> execSearchIndex(args)
                "get_index_stats" -> execGetIndexStats()
                "compare_chunking" -> execCompareChunking(args)
                "load_index" -> execLoadIndex(args)
                else -> toolError("Unknown tool: $name")
            }
        } catch (e: Exception) {
            toolError("Error: ${e.message}")
        }
    }

    // ───── load_documents ─────

    internal fun execLoadDocuments(args: JSONObject): JSONObject {
        val directory = args.getString("directory")
        val extensionsStr = args.optString("extensions", "")
        val maxSizeKb = args.optInt("max_file_size_kb", 500).coerceIn(1, 10000)
        val recursive = args.optBoolean("recursive", true)

        val allowedExts = if (extensionsStr.isNotBlank()) {
            extensionsStr.split(",").map { it.trim().removePrefix(".").lowercase() }.toSet()
        } else TEXT_EXTENSIONS

        val dir = File(directory)
        if (!dir.exists() || !dir.isDirectory) {
            return toolError("Directory not found: $directory")
        }

        val maxBytes = maxSizeKb * 1024L
        val files = if (recursive) dir.walkTopDown() else dir.walkTopDown().maxDepth(1)
        val docs = mutableListOf<LoadedDocument>()
        var skippedCount = 0
        var totalChars = 0L

        files.filter { it.isFile }
            .filter { f -> f.extension.lowercase() in allowedExts }
            .filter { f ->
                if (f.length() > maxBytes) { skippedCount++; false } else true
            }
            .forEach { f ->
                try {
                    val content = f.readText(Charsets.UTF_8)
                    if (content.isNotBlank()) {
                        docs.add(LoadedDocument(
                            path = f.absolutePath.replace('\\', '/'),
                            name = f.name,
                            content = content,
                            extension = f.extension.lowercase()
                        ))
                        totalChars += content.length
                    }
                } catch (_: Exception) {
                    skippedCount++
                }
            }

        lastLoadedDocs = docs

        val sb = StringBuilder()
        sb.appendLine("Loaded ${docs.size} documents from $directory")
        sb.appendLine("Total: ${totalChars} characters (~${totalChars / 2000} pages)")
        if (skippedCount > 0) sb.appendLine("Skipped: $skippedCount files (too large or unreadable)")
        sb.appendLine()

        // Show by extension
        val byExt = docs.groupBy { it.extension }
        sb.appendLine("By type:")
        for ((ext, group) in byExt.entries.sortedByDescending { it.value.size }) {
            val chars = group.sumOf { it.content.length.toLong() }
            sb.appendLine("  .$ext: ${group.size} files, ${chars} chars")
        }
        sb.appendLine()

        // Show file list (truncated)
        sb.appendLine("Files:")
        for (doc in docs.take(50)) {
            sb.appendLine("  ${doc.name} (${doc.content.length} chars)")
        }
        if (docs.size > 50) sb.appendLine("  ... and ${docs.size - 50} more")

        return toolResult(sb.toString().trimEnd())
    }

    // ───── index_documents ─────

    internal fun execIndexDocuments(args: JSONObject): JSONObject {
        if (lastLoadedDocs.isEmpty()) {
            return toolError("No documents loaded. Call load_documents first.")
        }

        val strategy = args.optString("strategy", "both")
        val apiKey = args.getString("api_key")
        val baseUrl = args.optString("base_url", "https://api.openai.com")
        val model = args.optString("model", "text-embedding-3-small")
        val chunkSize = args.optInt("chunk_size", 500).coerceIn(100, 5000)
        val overlap = args.optInt("overlap", 100).coerceIn(0, chunkSize / 2)
        val savePath = args.optString("save_path", "./document-index.json")

        embeddingClient = EmbeddingClient(baseUrl = baseUrl, apiKey = apiKey, model = model)

        val log = StringBuilder()
        log.appendLine("=== Document Indexing Pipeline ===")
        log.appendLine()

        // Step 1: Chunking
        log.appendLine("Step 1: Chunking ${lastLoadedDocs.size} documents (strategy: $strategy)...")
        val allChunks = mutableListOf<DocumentChunk>()

        for (doc in lastLoadedDocs) {
            when (strategy) {
                "fixed" -> allChunks.addAll(ChunkingStrategy.chunkFixed(doc, chunkSize, overlap))
                "structural" -> allChunks.addAll(ChunkingStrategy.chunkStructural(doc))
                "both" -> {
                    allChunks.addAll(ChunkingStrategy.chunkFixed(doc, chunkSize, overlap))
                    allChunks.addAll(ChunkingStrategy.chunkStructural(doc))
                }
                else -> return toolError("Unknown strategy: $strategy. Use 'fixed', 'structural', or 'both'.")
            }
        }

        log.appendLine("  Created ${allChunks.size} chunks")
        val byStrategy = allChunks.groupBy { it.metadata.strategy }
        for ((s, chunks) in byStrategy) {
            val avgLen = chunks.map { it.text.length }.average()
            log.appendLine("    $s: ${chunks.size} chunks, avg ${avgLen.toInt()} chars")
        }
        log.appendLine()

        // Step 2: Generate embeddings
        log.appendLine("Step 2: Generating embeddings ($model)...")
        val texts = allChunks.map { it.text }
        val startTime = System.currentTimeMillis()
        val embeddings = embeddingClient!!.embed(texts)
        val elapsed = System.currentTimeMillis() - startTime
        log.appendLine("  Generated ${embeddings.size} embeddings in ${elapsed}ms")
        if (embeddings.isNotEmpty()) {
            log.appendLine("  Dimension: ${embeddings.first().size}")
        }
        log.appendLine()

        // Step 3: Build index
        log.appendLine("Step 3: Building index...")
        index.clear()
        index.setName("doc-index-${System.currentTimeMillis()}")
        val indexEntries = allChunks.zip(embeddings).map { (chunk, emb) -> IndexEntry(chunk, emb) }
        index.addAll(indexEntries)
        log.appendLine("  Index built: ${index.size} entries")
        log.appendLine()

        // Step 4: Save
        log.appendLine("Step 4: Saving to $savePath...")
        index.save(savePath)
        val fileSize = File(savePath).length()
        log.appendLine("  Saved: ${fileSize / 1024} KB")
        log.appendLine()

        log.appendLine("=== Indexing complete ===")
        return toolResult(log.toString().trimEnd())
    }

    // ───── search_index ─────

    internal fun execSearchIndex(args: JSONObject): JSONObject {
        if (index.size == 0) {
            return toolError("Index is empty. Call index_documents or load_index first.")
        }

        val query = args.getString("query")
        val topK = args.optInt("top_k", 5).coerceIn(1, 50)
        val minScore = args.optDouble("min_score", 0.0).toFloat()
        val strategyFilter = args.optString("strategy_filter", "")
        val apiKey = args.getString("api_key")
        val baseUrl = args.optString("base_url", "https://api.openai.com")
        val model = args.optString("model", "text-embedding-3-small")

        val client = embeddingClient ?: EmbeddingClient(baseUrl = baseUrl, apiKey = apiKey, model = model)
        embeddingClient = client

        // Embed query
        val queryEmbedding = client.embedSingle(query)

        // Search
        var results = index.search(queryEmbedding, topK = topK * 2, minScore = minScore)

        // Apply strategy filter
        if (strategyFilter.isNotBlank()) {
            results = results.filter { it.chunk.metadata.strategy == strategyFilter }
        }
        results = results.take(topK)

        if (results.isEmpty()) {
            return toolResult("No results found for: '$query' (min_score=$minScore)")
        }

        val sb = StringBuilder()
        sb.appendLine("Search results for: '$query'")
        sb.appendLine("Found ${results.size} matches:")
        sb.appendLine()

        for ((i, result) in results.withIndex()) {
            val m = result.chunk.metadata
            sb.appendLine("─── Result ${i + 1} (score: ${"%.4f".format(result.score)}) ───")
            sb.appendLine("Source: ${m.title} | Section: ${m.section}")
            sb.appendLine("Strategy: ${m.strategy} | Chunk: ${m.chunkId}")
            sb.appendLine("File: ${m.source}")
            sb.appendLine()
            // Show text preview (first 300 chars)
            val preview = if (result.chunk.text.length > 300) {
                result.chunk.text.take(300) + "..."
            } else result.chunk.text
            sb.appendLine(preview)
            sb.appendLine()
        }

        return toolResult(sb.toString().trimEnd())
    }

    // ───── get_index_stats ─────

    internal fun execGetIndexStats(): JSONObject {
        if (index.size == 0) {
            return toolResult("Index is empty. No documents indexed yet.")
        }

        val stats = index.stats()
        val sb = StringBuilder()
        sb.appendLine("=== Index Statistics ===")
        sb.appendLine("Name: ${stats.indexName}")
        sb.appendLine("Total chunks: ${stats.totalChunks}")
        sb.appendLine("Total documents: ${stats.totalDocuments}")
        sb.appendLine("Embedding dimension: ${stats.embeddingDimension}")
        sb.appendLine("Avg chunk length: ${stats.avgChunkLength.toInt()} chars")
        sb.appendLine()

        sb.appendLine("By strategy:")
        for ((s, count) in stats.chunksByStrategy) {
            sb.appendLine("  $s: $count chunks")
        }
        sb.appendLine()

        sb.appendLine("By source:")
        for ((source, count) in stats.chunksBySource.entries.sortedByDescending { it.value }) {
            val shortName = source.substringAfterLast('/')
            sb.appendLine("  $shortName: $count chunks")
        }

        return toolResult(sb.toString().trimEnd())
    }

    // ───── compare_chunking ─────

    internal fun execCompareChunking(args: JSONObject): JSONObject {
        if (lastLoadedDocs.isEmpty()) {
            return toolError("No documents loaded. Call load_documents first.")
        }

        val chunkSize = args.optInt("chunk_size", 500).coerceIn(100, 5000)
        val overlap = args.optInt("overlap", 100).coerceIn(0, chunkSize / 2)

        val sb = StringBuilder()
        sb.appendLine("=== Chunking Strategy Comparison ===")
        sb.appendLine("Documents: ${lastLoadedDocs.size}")
        sb.appendLine("Total text: ${lastLoadedDocs.sumOf { it.content.length }} characters")
        sb.appendLine()

        // Generate chunks with both strategies
        val fixedChunks = lastLoadedDocs.flatMap { ChunkingStrategy.chunkFixed(it, chunkSize, overlap) }
        val structuralChunks = lastLoadedDocs.flatMap { ChunkingStrategy.chunkStructural(it) }

        val fixedLengths = fixedChunks.map { it.text.length }
        val structLengths = structuralChunks.map { it.text.length }

        // Strategy 1: Fixed-size
        sb.appendLine("── Strategy 1: Fixed-size (chunk_size=$chunkSize, overlap=$overlap) ──")
        sb.appendLine("  Chunks: ${fixedChunks.size}")
        sb.appendLine("  Avg length: ${if (fixedLengths.isEmpty()) 0 else fixedLengths.average().toInt()} chars")
        sb.appendLine("  Min length: ${fixedLengths.minOrNull() ?: 0} chars")
        sb.appendLine("  Max length: ${fixedLengths.maxOrNull() ?: 0} chars")
        sb.appendLine("  Median: ${median(fixedLengths)} chars")
        sb.appendLine("  Std dev: ${stdDev(fixedLengths).toInt()} chars")
        sb.appendLine()
        sb.appendLine("  Size distribution:")
        printHistogram(sb, fixedLengths, "  ")
        sb.appendLine()

        // Strategy 2: Structural
        sb.appendLine("── Strategy 2: Structural (headings / code declarations / paragraphs) ──")
        sb.appendLine("  Chunks: ${structuralChunks.size}")
        sb.appendLine("  Avg length: ${if (structLengths.isEmpty()) 0 else structLengths.average().toInt()} chars")
        sb.appendLine("  Min length: ${structLengths.minOrNull() ?: 0} chars")
        sb.appendLine("  Max length: ${structLengths.maxOrNull() ?: 0} chars")
        sb.appendLine("  Median: ${median(structLengths)} chars")
        sb.appendLine("  Std dev: ${stdDev(structLengths).toInt()} chars")
        sb.appendLine()
        sb.appendLine("  Size distribution:")
        printHistogram(sb, structLengths, "  ")
        sb.appendLine()

        // Section coverage for structural
        val sections = structuralChunks.map { it.metadata.section }.distinct()
        sb.appendLine("  Unique sections: ${sections.size}")
        for (sec in sections.take(20)) {
            sb.appendLine("    - $sec")
        }
        if (sections.size > 20) sb.appendLine("    ... and ${sections.size - 20} more")
        sb.appendLine()

        // Comparison summary
        sb.appendLine("── Summary ──")
        sb.appendLine("  Fixed chunks are ${if (fixedLengths.isNotEmpty()) "uniform (~$chunkSize chars)" else "none"}")
        sb.appendLine("  Structural chunks vary in size but preserve document structure")
        sb.appendLine()

        val fixedCoverage = fixedChunks.sumOf { it.text.length.toLong() }
        val structCoverage = structuralChunks.sumOf { it.text.length.toLong() }
        val totalText = lastLoadedDocs.sumOf { it.content.length.toLong() }
        sb.appendLine("  Text coverage:")
        sb.appendLine("    Fixed:      $fixedCoverage chars (${"%.1f".format(fixedCoverage * 100.0 / totalText)}% of original)")
        sb.appendLine("    Structural: $structCoverage chars (${"%.1f".format(structCoverage * 100.0 / totalText)}% of original)")
        sb.appendLine()

        // Pros/Cons
        sb.appendLine("  Fixed-size pros:")
        sb.appendLine("    + Predictable chunk sizes for consistent embedding quality")
        sb.appendLine("    + Overlap ensures no information lost at boundaries")
        sb.appendLine("    + Good for uniform text (articles, prose)")
        sb.appendLine("  Fixed-size cons:")
        sb.appendLine("    - May split mid-sentence or mid-function")
        sb.appendLine("    - No semantic boundary awareness")
        sb.appendLine()
        sb.appendLine("  Structural pros:")
        sb.appendLine("    + Preserves semantic boundaries (headings, functions)")
        sb.appendLine("    + Better for code and structured documents")
        sb.appendLine("    + Each chunk is self-contained and meaningful")
        sb.appendLine("  Structural cons:")
        sb.appendLine("    - Highly variable chunk sizes")
        sb.appendLine("    - Very large sections may exceed embedding context")
        sb.appendLine("    - No overlap between adjacent sections")

        return toolResult(sb.toString().trimEnd())
    }

    // ───── load_index ─────

    internal fun execLoadIndex(args: JSONObject): JSONObject {
        val path = args.getString("path")
        val file = File(path)
        if (!file.exists()) return toolError("Index file not found: $path")

        index.load(path)
        val stats = index.stats()
        return toolResult("Index loaded: ${stats.totalChunks} chunks from ${stats.totalDocuments} documents (dim=${stats.embeddingDimension})")
    }

    // ───── Helpers ─────

    private fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun stdDev(values: List<Int>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return Math.sqrt(variance)
    }

    private fun printHistogram(sb: StringBuilder, values: List<Int>, indent: String) {
        if (values.isEmpty()) {
            sb.appendLine("${indent}(no data)")
            return
        }
        val buckets = listOf(0..99, 100..249, 250..499, 500..999, 1000..1999, 2000..4999, 5000..Int.MAX_VALUE)
        val labels = listOf("0-99", "100-249", "250-499", "500-999", "1000-1999", "2000-4999", "5000+")
        val maxBar = 30

        val counts = buckets.map { range -> values.count { it in range } }
        val maxCount = counts.maxOrNull() ?: 1

        for ((i, label) in labels.withIndex()) {
            val count = counts[i]
            if (count > 0) {
                val bar = "#".repeat((count * maxBar / maxCount.coerceAtLeast(1)).coerceAtLeast(1))
                sb.appendLine("$indent  ${label.padEnd(10)} $bar $count")
            }
        }
    }

    // ───── JSON-RPC helpers ─────

    private fun successResponse(id: Any, result: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", result)
            .toString()

    private fun errorResponse(id: Any?, code: Int, message: String): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString()

    companion object {
        fun toolResult(text: String, isError: Boolean = false): JSONObject =
            JSONObject()
                .put("content", JSONArray().put(JSONObject()
                    .put("type", "text")
                    .put("text", text)))
                .put("isError", isError)

        fun toolError(message: String): JSONObject = toolResult(message, isError = true)

        data class PropDef(
            val name: String,
            val type: String,
            val description: String,
            val required: Boolean = true
        )

        fun prop(name: String, type: String, description: String, required: Boolean = true) =
            PropDef(name, type, description, required)

        fun toolDef(name: String, description: String, vararg props: PropDef): JSONObject {
            val properties = JSONObject()
            val requiredArr = JSONArray()
            for (p in props) {
                properties.put(p.name, JSONObject()
                    .put("type", p.type)
                    .put("description", p.description))
                if (p.required) requiredArr.put(p.name)
            }
            val schema = JSONObject()
                .put("type", "object")
                .put("properties", properties)
            if (requiredArr.length() > 0) schema.put("required", requiredArr)

            return JSONObject()
                .put("name", name)
                .put("description", description)
                .put("inputSchema", schema)
        }
    }
}

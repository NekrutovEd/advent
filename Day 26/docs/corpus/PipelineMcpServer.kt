package mcp.server

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintStream

/**
 * MCP server for tool composition pipelines.
 * Communicates via JSON-RPC 2.0 over stdin/stdout.
 *
 * Atomic tools: search, summarize, save_to_file
 * Pipeline tool: run_pipeline (chains search → summarize → save_to_file)
 */
fun main() {
    val server = PipelineMcpServer()
    server.run()
}

class PipelineMcpServer {

    // Detects search result lines: "path/file.kt:123: content"
    private val SEARCH_LINE_RE = Regex("""^(.+?):(\d+):\s*(.+)$""")

    private val tools = listOf(
        toolDef(
            "search", "Search files in a directory by keyword. Returns matching lines with file paths and line numbers.",
            prop("directory", "string", "Directory path to search in"),
            prop("keyword", "string", "Keyword or phrase to search for (case-insensitive)"),
            prop("file_pattern", "string", "File extension filter, e.g. '.txt', '.kt', '.md' (default: all files)", required = false),
            prop("max_results", "integer", "Maximum number of matching lines to return (default: 50)", required = false)
        ),
        toolDef(
            "summarize", "Summarize text by extracting the most relevant sentences. Uses extractive summarization based on keyword frequency.",
            prop("text", "string", "The text to summarize"),
            prop("max_sentences", "integer", "Maximum number of sentences in the summary (default: 5)", required = false),
            prop("focus_keyword", "string", "Optional keyword to bias sentence selection towards", required = false)
        ),
        toolDef(
            "save_to_file", "Save text content to a file. Creates parent directories if needed.",
            prop("path", "string", "File path to save to"),
            prop("content", "string", "Text content to write"),
            prop("append", "boolean", "Append to existing file instead of overwriting (default: false)", required = false)
        ),
        toolDef(
            "run_pipeline", "Run a complete pipeline: search → summarize → save_to_file. Searches for a keyword in files, summarizes the results, and saves the summary to a file.",
            prop("directory", "string", "Directory path to search in"),
            prop("keyword", "string", "Keyword or phrase to search for"),
            prop("output_path", "string", "File path to save the summary to"),
            prop("file_pattern", "string", "File extension filter (default: all files)", required = false),
            prop("max_results", "integer", "Maximum search results before summarization (default: 50)", required = false),
            prop("max_sentences", "integer", "Maximum sentences in the summary (default: 5)", required = false),
            prop("append", "boolean", "Append to output file (default: false)", required = false)
        ),
        toolDef(
            "read_file", "Read the contents of a file. Use this to retrieve previously saved summaries or any text file.",
            prop("path", "string", "File path to read"),
            prop("max_lines", "integer", "Maximum number of lines to return (default: 200)", required = false),
            prop("offset", "integer", "Skip this many lines from the beginning (default: 0)", required = false)
        ),
        toolDef(
            "list_pipelines", "List available pipeline templates and recent pipeline execution history"
        )
    )

    // Pipeline execution history
    internal val pipelineHistory = mutableListOf<PipelineExecution>()

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
        val msg = try {
            JSONObject(line)
        } catch (_: Exception) {
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
                .put("name", "Pipeline MCP Server")
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
                "search" -> execSearch(args)
                "summarize" -> execSummarize(args)
                "save_to_file" -> execSaveToFile(args)
                "read_file" -> execReadFile(args)
                "run_pipeline" -> execRunPipeline(args)
                "list_pipelines" -> execListPipelines()
                else -> toolError("Unknown tool: $name")
            }
        } catch (e: Exception) {
            toolError("Error: ${e.message}")
        }
    }

    // ───── search ─────

    internal fun execSearch(args: JSONObject): JSONObject {
        val directory = args.getString("directory")
        val keyword = args.getString("keyword")
        val filePattern = args.optString("file_pattern", "")
        val maxResults = args.optInt("max_results", 50).coerceIn(1, 500)

        val dir = File(directory)
        if (!dir.exists() || !dir.isDirectory) {
            return toolError("Directory not found: $directory")
        }

        val keywordLower = keyword.lowercase()
        val matches = mutableListOf<String>()
        var filesScanned = 0

        dir.walkTopDown()
            .filter { it.isFile }
            .filter { f ->
                if (filePattern.isBlank()) true
                else f.name.endsWith(filePattern, ignoreCase = true)
            }
            .forEach { file ->
                if (matches.size >= maxResults) return@forEach
                filesScanned++
                try {
                    file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEachIndexed { idx, line ->
                            if (matches.size < maxResults && line.lowercase().contains(keywordLower)) {
                                val relativePath = file.relativeTo(dir).path
                                matches.add("$relativePath:${idx + 1}: ${line.trim()}")
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Skip binary/unreadable files
                }
            }

        if (matches.isEmpty()) {
            return toolResult("No matches found for '$keyword' in $directory ($filesScanned files scanned)")
        }

        val sb = StringBuilder()
        sb.appendLine("Found ${matches.size} matches for '$keyword' ($filesScanned files scanned):")
        sb.appendLine()
        matches.forEach { sb.appendLine(it) }
        return toolResult(sb.toString().trimEnd())
    }

    // ───── summarize ─────

    internal fun execSummarize(args: JSONObject): JSONObject {
        val text = args.getString("text")
        val maxSentences = args.optInt("max_sentences", 5).coerceIn(1, 50)
        val focusKeyword = args.optString("focus_keyword", "").lowercase()

        if (text.isBlank()) return toolError("Text is empty, nothing to summarize")

        // Detect search results format (lines like "file.kt:12: content")
        val lines = text.lines().filter { it.isNotBlank() }
        val isSearchResult = lines.count { SEARCH_LINE_RE.containsMatchIn(it) } > lines.size / 2

        return if (isSearchResult) {
            summarizeSearchResults(lines, maxSentences, focusKeyword)
        } else {
            summarizeProse(text, lines, maxSentences, focusKeyword)
        }
    }

    /** Summarize search results: group by file, show top files with representative lines. */
    private fun summarizeSearchResults(
        lines: List<String>,
        maxEntries: Int,
        focusKeyword: String
    ): JSONObject {
        // Parse search lines into (file, lineNo, content)
        data class Match(val file: String, val lineNo: String, val content: String)

        val header = lines.firstOrNull { it.startsWith("Found ") } ?: ""
        val matches = lines.mapNotNull { line ->
            val m = SEARCH_LINE_RE.find(line) ?: return@mapNotNull null
            Match(m.groupValues[1], m.groupValues[2], m.groupValues[3].trim())
        }

        if (matches.isEmpty()) return toolResult("No structured matches to summarize.\n\n${lines.take(maxEntries).joinToString("\n")}")

        // Group by file
        val byFile = matches.groupBy { it.file }

        // Score files: more matches = more important, deduplicate content
        val fileScores = byFile.map { (file, fileMatches) ->
            val uniqueContents = fileMatches.map { it.content.lowercase().trim() }.distinct()
            val keywordDensity = if (focusKeyword.isNotBlank()) {
                uniqueContents.count { it.contains(focusKeyword) }.toDouble() / uniqueContents.size.coerceAtLeast(1)
            } else 1.0
            file to (uniqueContents.size * (1.0 + keywordDensity))
        }.sortedByDescending { it.second }

        val sb = StringBuilder()
        if (header.isNotBlank()) {
            sb.appendLine(header)
            sb.appendLine()
        }
        sb.appendLine("${byFile.size} files, ${matches.size} matches total:")
        sb.appendLine()

        // Show top files with their unique lines
        var entriesLeft = maxEntries
        for ((file, _) in fileScores) {
            if (entriesLeft <= 0) break
            val fileMatches = byFile[file]!!
            val uniqueLines = fileMatches.distinctBy { it.content.lowercase().trim() }
            val toShow = uniqueLines.take(entriesLeft.coerceAtMost(3))

            sb.appendLine("  $file (${fileMatches.size} matches):")
            for (m in toShow) {
                sb.appendLine("    :${m.lineNo}  ${m.content}")
            }
            if (uniqueLines.size > toShow.size) {
                sb.appendLine("    ... and ${uniqueLines.size - toShow.size} more unique lines")
            }
            sb.appendLine()
            entriesLeft -= toShow.size
        }

        val shown = fileScores.takeWhile { entriesLeft < maxEntries; true }.count()
        if (shown < byFile.size) {
            sb.appendLine("... and ${byFile.size - shown} more files")
        }

        return toolResult(sb.toString().trimEnd())
    }

    /** Summarize prose text: split by lines first, fall back to sentence splitting. */
    private fun summarizeProse(
        text: String,
        lines: List<String>,
        maxSentences: Int,
        focusKeyword: String
    ): JSONObject {
        // Use lines as units — each meaningful line is a "sentence"
        val units = lines.filter { it.length > 3 }

        if (units.size <= maxSentences) {
            return toolResult("Summary (text already short, ${units.size} lines):\n\n$text")
        }

        val wordFreq = buildWordFrequency(text)
        val scored = units.mapIndexed { idx, line ->
            val score = scoreLine(line, wordFreq, focusKeyword, idx, units.size)
            line to score
        }

        val topIndices = scored.indices
            .sortedByDescending { scored[it].second }
            .take(maxSentences)
            .sorted()

        val summary = topIndices.joinToString("\n") { units[it].trim() }

        val result = StringBuilder()
        result.appendLine("Summary ($maxSentences of ${units.size} lines):")
        result.appendLine()
        result.append(summary)
        return toolResult(result.toString())
    }

    private fun buildWordFrequency(text: String): Map<String, Int> {
        val words = text.lowercase().split(Regex("[\\s,;:!?()\\[\\]{}\"']+"))
            .filter { it.length > 2 }
        val freq = mutableMapOf<String, Int>()
        for (w in words) freq[w] = (freq[w] ?: 0) + 1
        return freq
    }

    internal fun scoreLine(
        line: String,
        wordFreq: Map<String, Int>,
        focusKeyword: String,
        index: Int,
        totalLines: Int
    ): Double {
        val words = line.lowercase().split(Regex("[\\s,;:!?()\\[\\]{}\"']+"))
            .filter { it.length > 2 }
        if (words.isEmpty()) return 0.0

        // Base score: average word frequency (common words = less informative)
        val avgFreq = words.sumOf { wordFreq[it] ?: 0 }.toDouble() / words.size
        // Prefer lines with moderate frequency — not too common, not too rare
        var score = 1.0 / (1.0 + kotlin.math.abs(avgFreq - 3.0))

        // Bonus for focus keyword
        if (focusKeyword.isNotBlank()) {
            val keywordCount = words.count { it.contains(focusKeyword) }
            score += keywordCount * 2.0
        }

        // Position bonus
        if (index == 0) score += 1.5
        if (index == totalLines - 1) score += 0.5

        // Length bonus: prefer informative lines (not too short, not too long)
        if (words.size in 4..30) score += 1.0

        // Uniqueness bonus: longer lines carry more context
        score += (line.length.coerceAtMost(120).toDouble() / 120.0) * 0.5

        return score
    }

    // ───── save_to_file ─────

    internal fun execSaveToFile(args: JSONObject): JSONObject {
        val path = args.getString("path")
        val content = args.getString("content")
        val append = args.optBoolean("append", false)

        val file = File(path)
        try {
            file.parentFile?.mkdirs()
            if (append) {
                file.appendText(content, Charsets.UTF_8)
            } else {
                file.writeText(content, Charsets.UTF_8)
            }
            val action = if (append) "Appended" else "Saved"
            return toolResult("$action ${content.length} characters to $path")
        } catch (e: Exception) {
            return toolError("Failed to save file: ${e.message}")
        }
    }

    // ───── read_file ─────

    internal fun execReadFile(args: JSONObject): JSONObject {
        val path = args.getString("path")
        val maxLines = args.optInt("max_lines", 200).coerceIn(1, 5000)
        val offset = args.optInt("offset", 0).coerceAtLeast(0)

        val file = File(path)
        if (!file.exists()) return toolError("File not found: $path")
        if (!file.isFile) return toolError("Not a file: $path")

        return try {
            val lines = file.readLines(Charsets.UTF_8)
            val total = lines.size
            val sliced = lines.drop(offset).take(maxLines)

            if (sliced.isEmpty()) {
                return toolResult("File is empty or offset ($offset) exceeds line count ($total): $path")
            }

            val sb = StringBuilder()
            sb.appendLine("File: $path ($total lines, showing ${offset + 1}..${offset + sliced.size})")
            sb.appendLine()
            sliced.forEachIndexed { i, line ->
                sb.appendLine("${offset + i + 1}: $line")
            }
            if (offset + sliced.size < total) {
                sb.appendLine()
                sb.appendLine("... ${total - offset - sliced.size} more lines (use offset=${offset + sliced.size} to continue)")
            }
            toolResult(sb.toString().trimEnd())
        } catch (e: Exception) {
            toolError("Failed to read file: ${e.message}")
        }
    }

    // ───── run_pipeline ─────

    internal fun execRunPipeline(args: JSONObject): JSONObject {
        val startTime = System.currentTimeMillis()

        val directory = args.getString("directory")
        val keyword = args.getString("keyword")
        val outputPath = args.getString("output_path")
        val filePattern = args.optString("file_pattern", "")
        val maxResults = args.optInt("max_results", 50)
        val maxSentences = args.optInt("max_sentences", 5)
        val append = args.optBoolean("append", false)

        val log = StringBuilder()
        log.appendLine("=== Pipeline: search → summarize → save_to_file ===")
        log.appendLine()

        // Step 1: Search
        log.appendLine("▸ Step 1/3: Searching for '$keyword' in $directory...")
        val searchArgs = JSONObject()
            .put("directory", directory)
            .put("keyword", keyword)
            .put("max_results", maxResults)
        if (filePattern.isNotBlank()) searchArgs.put("file_pattern", filePattern)

        val searchResult = execSearch(searchArgs)
        val searchText = extractText(searchResult)
        val searchError = searchResult.optBoolean("isError", false)
        log.appendLine("  ${if (searchError) "✗ FAILED" else "✓ Done"}: ${searchText.lines().firstOrNull() ?: "empty"}")
        log.appendLine()

        if (searchError || searchText.isBlank() || searchText.startsWith("No matches found")) {
            log.appendLine("Pipeline stopped: search returned no data.")
            recordExecution(keyword, directory, outputPath, false, System.currentTimeMillis() - startTime)
            return toolResult(log.toString().trimEnd())
        }

        // Step 2: Summarize
        log.appendLine("▸ Step 2/3: Summarizing ${searchText.length} characters...")
        val summarizeArgs = JSONObject()
            .put("text", searchText)
            .put("max_sentences", maxSentences)
            .put("focus_keyword", keyword)

        val summarizeResult = execSummarize(summarizeArgs)
        val summaryText = extractText(summarizeResult)
        val summarizeError = summarizeResult.optBoolean("isError", false)
        log.appendLine("  ${if (summarizeError) "✗ FAILED" else "✓ Done"}: ${summaryText.lines().firstOrNull() ?: "empty"}")
        log.appendLine()

        if (summarizeError || summaryText.isBlank()) {
            log.appendLine("Pipeline stopped: summarization failed.")
            recordExecution(keyword, directory, outputPath, false, System.currentTimeMillis() - startTime)
            return toolResult(log.toString().trimEnd())
        }

        // Step 3: Save to file
        log.appendLine("▸ Step 3/3: Saving summary to $outputPath...")
        val header = "# Search Summary: '$keyword'\n# Source: $directory\n# Date: ${formatTimestamp(System.currentTimeMillis())}\n\n"
        val saveArgs = JSONObject()
            .put("path", outputPath)
            .put("content", header + summaryText + "\n")
            .put("append", append)

        val saveResult = execSaveToFile(saveArgs)
        val saveText = extractText(saveResult)
        val saveError = saveResult.optBoolean("isError", false)
        log.appendLine("  ${if (saveError) "✗ FAILED" else "✓ Done"}: $saveText")
        log.appendLine()

        val elapsed = System.currentTimeMillis() - startTime
        val success = !saveError
        recordExecution(keyword, directory, outputPath, success, elapsed)

        log.appendLine("=== Pipeline ${if (success) "completed successfully" else "failed"} (${elapsed}ms) ===")
        return toolResult(log.toString().trimEnd())
    }

    // ───── list_pipelines ─────

    private fun execListPipelines(): JSONObject {
        val sb = StringBuilder()
        sb.appendLine("Available pipeline templates:")
        sb.appendLine()
        sb.appendLine("  1. search → summarize → save_to_file")
        sb.appendLine("     Use 'run_pipeline' tool to execute this chain.")
        sb.appendLine("     Searches files by keyword, summarizes results, saves to file.")
        sb.appendLine()
        sb.appendLine("Individual tools can also be composed manually:")
        sb.appendLine("  • search — find text in files")
        sb.appendLine("  • summarize — extract key sentences from text")
        sb.appendLine("  • save_to_file — write text to a file")
        sb.appendLine()

        if (pipelineHistory.isEmpty()) {
            sb.appendLine("No pipeline executions yet.")
        } else {
            sb.appendLine("Recent executions (${pipelineHistory.size}):")
            sb.appendLine()
            for (exec in pipelineHistory.takeLast(10).reversed()) {
                val status = if (exec.success) "✓" else "✗"
                sb.appendLine("  [$status] '${exec.keyword}' in ${exec.directory} → ${exec.outputPath} (${exec.elapsedMs}ms, ${formatTimestamp(exec.timestamp)})")
            }
        }
        return toolResult(sb.toString().trimEnd())
    }

    // ───── Helpers ─────

    private fun extractText(result: JSONObject): String {
        val content = result.optJSONArray("content") ?: return ""
        if (content.length() == 0) return ""
        return content.getJSONObject(0).optString("text", "")
    }

    private fun recordExecution(keyword: String, directory: String, outputPath: String, success: Boolean, elapsedMs: Long) {
        pipelineHistory.add(PipelineExecution(
            keyword = keyword,
            directory = directory,
            outputPath = outputPath,
            success = success,
            elapsedMs = elapsedMs
        ))
        // Keep only last 50 executions
        while (pipelineHistory.size > 50) pipelineHistory.removeAt(0)
    }

    private fun formatTimestamp(ms: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return String.format(
            "%04d-%02d-%02d %02d:%02d:%02d",
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND)
        )
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

data class PipelineExecution(
    val keyword: String,
    val directory: String,
    val outputPath: String,
    val success: Boolean,
    val elapsedMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

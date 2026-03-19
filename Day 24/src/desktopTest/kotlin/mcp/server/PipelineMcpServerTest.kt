package mcp.server

import org.json.JSONObject
import java.io.File
import kotlin.test.*

class PipelineMcpServerTest {

    private fun createServer() = PipelineMcpServer()

    private fun jsonRpc(id: Any, method: String, params: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
            .toString()

    private fun callTool(server: PipelineMcpServer, toolName: String, args: JSONObject): JSONObject {
        val params = JSONObject().put("name", toolName).put("arguments", args)
        val request = jsonRpc(1, "tools/call", params)
        val response = server.handleMessage(request)!!
        return JSONObject(response).getJSONObject("result")
    }

    private fun extractText(result: JSONObject): String {
        val content = result.getJSONArray("content")
        return content.getJSONObject(0).getString("text")
    }

    // ───── JSON-RPC protocol tests ─────

    @Test
    fun `initialize returns server info`() {
        val server = createServer()
        val request = jsonRpc(1, "initialize", JSONObject())
        val response = server.handleMessage(request)!!
        val json = JSONObject(response)
        assertEquals("2.0", json.getString("jsonrpc"))
        val result = json.getJSONObject("result")
        assertEquals("2024-11-05", result.getString("protocolVersion"))
        val serverInfo = result.getJSONObject("serverInfo")
        assertEquals("Pipeline MCP Server", serverInfo.getString("name"))
        assertEquals("1.0.0", serverInfo.getString("version"))
    }

    @Test
    fun `tools list returns all pipeline tools`() {
        val server = createServer()
        val request = jsonRpc(2, "tools/list", JSONObject())
        val response = server.handleMessage(request)!!
        val json = JSONObject(response)
        val tools = json.getJSONObject("result").getJSONArray("tools")
        assertEquals(6, tools.length(), "Should have 6 pipeline tools")

        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertContains(names, "search")
        assertContains(names, "summarize")
        assertContains(names, "save_to_file")
        assertContains(names, "read_file")
        assertContains(names, "run_pipeline")
        assertContains(names, "list_pipelines")
    }

    @Test
    fun `parse error returns JSON-RPC error`() {
        val server = createServer()
        val response = server.handleMessage("not json")!!
        val json = JSONObject(response)
        assertTrue(json.has("error"))
        assertEquals(-32700, json.getJSONObject("error").getInt("code"))
    }

    @Test
    fun `unknown method returns error`() {
        val server = createServer()
        val request = jsonRpc(1, "unknown/method", JSONObject())
        val response = server.handleMessage(request)!!
        val json = JSONObject(response)
        assertTrue(json.has("error"))
        assertEquals(-32601, json.getJSONObject("error").getInt("code"))
    }

    @Test
    fun `notification without id returns null`() {
        val server = createServer()
        val msg = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").toString()
        assertNull(server.handleMessage(msg))
    }

    // ───── search tool tests ─────

    @Test
    fun `search finds keyword in files`() {
        val server = createServer()
        val tmpDir = createTempSearchDir(
            "hello.txt" to "Hello world\nThis is a test\nHello again",
            "other.txt" to "No match here"
        )
        try {
            val args = JSONObject().put("directory", tmpDir.absolutePath).put("keyword", "hello")
            val result = callTool(server, "search", args)
            val text = extractText(result)
            assertFalse(result.getBoolean("isError"))
            assertTrue(text.contains("Found 2 matches"), "Should find 2 matches: $text")
            assertTrue(text.contains("hello.txt:1:"), "Should include file:line: $text")
            assertTrue(text.contains("hello.txt:3:"), "Should include second match: $text")
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `search with file pattern filters by extension`() {
        val server = createServer()
        val tmpDir = createTempSearchDir(
            "code.kt" to "fun main() { println(\"test\") }",
            "readme.md" to "This is a test readme"
        )
        try {
            val args = JSONObject()
                .put("directory", tmpDir.absolutePath)
                .put("keyword", "test")
                .put("file_pattern", ".kt")
            val result = callTool(server, "search", args)
            val text = extractText(result)
            assertFalse(result.getBoolean("isError"))
            assertTrue(text.contains("Found 1 match"), "Should find only 1 match in .kt: $text")
            assertTrue(text.contains("code.kt"), "Should match the .kt file")
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `search returns error for non-existent directory`() {
        val server = createServer()
        val args = JSONObject().put("directory", "/nonexistent/dir/xyz").put("keyword", "test")
        val result = callTool(server, "search", args)
        assertTrue(result.getBoolean("isError"))
        assertTrue(extractText(result).contains("Directory not found"))
    }

    @Test
    fun `search respects max_results`() {
        val server = createServer()
        val lines = (1..20).joinToString("\n") { "line $it with keyword match" }
        val tmpDir = createTempSearchDir("big.txt" to lines)
        try {
            val args = JSONObject()
                .put("directory", tmpDir.absolutePath)
                .put("keyword", "keyword")
                .put("max_results", 3)
            val result = callTool(server, "search", args)
            val text = extractText(result)
            assertTrue(text.contains("Found 3 matches"), "Should cap at 3: $text")
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `search returns no matches message`() {
        val server = createServer()
        val tmpDir = createTempSearchDir("a.txt" to "nothing here")
        try {
            val args = JSONObject().put("directory", tmpDir.absolutePath).put("keyword", "zzzzz")
            val result = callTool(server, "search", args)
            val text = extractText(result)
            assertFalse(result.getBoolean("isError"))
            assertTrue(text.contains("No matches found"), text)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    // ───── summarize tool tests ─────

    @Test
    fun `summarize extracts top lines from prose`() {
        val server = createServer()
        val text = listOf(
            "Kotlin is a modern language",
            "It runs on the JVM",
            "Kotlin supports coroutines for async programming",
            "The language was created by JetBrains",
            "Kotlin is used for Android development",
            "It has null safety built in",
            "Kotlin is concise and expressive",
            "Many developers love Kotlin"
        ).joinToString("\n")
        val args = JSONObject().put("text", text).put("max_sentences", 3)
        val result = callTool(server, "summarize", args)
        val summary = extractText(result)
        assertFalse(result.getBoolean("isError"))
        assertTrue(summary.contains("Summary (3 of 8 lines)"), "Should indicate 3 of 8 lines: $summary")
    }

    @Test
    fun `summarize with focus keyword biases selection`() {
        val server = createServer()
        val text = listOf(
            "Apples are red fruits",
            "Bananas are yellow fruits",
            "Kotlin is a programming language for the JVM",
            "Kotlin supports multiplatform development",
            "Kotlin has coroutines for concurrency",
            "Oranges are citrus fruits"
        ).joinToString("\n")
        val args = JSONObject().put("text", text).put("max_sentences", 2).put("focus_keyword", "kotlin")
        val result = callTool(server, "summarize", args)
        val summary = extractText(result)
        assertTrue(summary.lowercase().contains("kotlin"), "Focus keyword should appear in summary: $summary")
    }

    @Test
    fun `summarize handles search results format`() {
        val server = createServer()
        val text = listOf(
            "Found 5 matches for 'coroutines' (10 files scanned):",
            "",
            "state/ChatState.kt:3: import kotlinx.coroutines.core",
            "state/ChatState.kt:45: val scope = CoroutineScope(Dispatchers.IO)",
            "build.gradle.kts:39: implementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2\")",
            "build.gradle.kts:57: implementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2\")",
            "Main.kt:10: import kotlinx.coroutines.launch"
        ).joinToString("\n")
        val args = JSONObject().put("text", text).put("max_sentences", 5).put("focus_keyword", "coroutines")
        val result = callTool(server, "summarize", args)
        val summary = extractText(result)
        assertFalse(result.getBoolean("isError"))
        // Should group by file, not produce "coroutines. coroutines. coroutines."
        assertTrue(summary.contains("files"), "Should mention files: $summary")
        assertTrue(summary.contains("matches"), "Should mention matches: $summary")
        assertFalse(summary.matches(Regex(".*coroutines\\.\\s*coroutines\\..*")), "Should NOT repeat bare keyword: $summary")
    }

    @Test
    fun `summarize returns original text when short`() {
        val server = createServer()
        val text = "Short text line"
        val args = JSONObject().put("text", text).put("max_sentences", 5)
        val result = callTool(server, "summarize", args)
        val summary = extractText(result)
        assertTrue(summary.contains("already short"), "Should note text is already short: $summary")
    }

    @Test
    fun `summarize errors on empty text`() {
        val server = createServer()
        val args = JSONObject().put("text", "")
        val result = callTool(server, "summarize", args)
        assertTrue(result.getBoolean("isError"))
        assertTrue(extractText(result).contains("empty"))
    }

    // ───── save_to_file tool tests ─────

    @Test
    fun `save_to_file writes content`() {
        val server = createServer()
        val tmpFile = File.createTempFile("pipeline-test-", ".txt")
        tmpFile.deleteOnExit()
        try {
            val args = JSONObject().put("path", tmpFile.absolutePath).put("content", "Hello Pipeline!")
            val result = callTool(server, "save_to_file", args)
            assertFalse(result.getBoolean("isError"))
            assertEquals("Hello Pipeline!", tmpFile.readText())
            assertTrue(extractText(result).contains("Saved"))
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `save_to_file appends content`() {
        val server = createServer()
        val tmpFile = File.createTempFile("pipeline-test-", ".txt")
        tmpFile.deleteOnExit()
        tmpFile.writeText("First line\n")
        try {
            val args = JSONObject()
                .put("path", tmpFile.absolutePath)
                .put("content", "Second line\n")
                .put("append", true)
            val result = callTool(server, "save_to_file", args)
            assertFalse(result.getBoolean("isError"))
            assertEquals("First line\nSecond line\n", tmpFile.readText())
            assertTrue(extractText(result).contains("Appended"))
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `save_to_file creates parent directories`() {
        val server = createServer()
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "pipeline-test-${System.currentTimeMillis()}")
        val tmpFile = File(tmpDir, "sub/deep/file.txt")
        try {
            val args = JSONObject().put("path", tmpFile.absolutePath).put("content", "Deep file")
            val result = callTool(server, "save_to_file", args)
            assertFalse(result.getBoolean("isError"))
            assertTrue(tmpFile.exists())
            assertEquals("Deep file", tmpFile.readText())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    // ───── read_file tool tests ─────

    @Test
    fun `read_file returns file contents`() {
        val server = createServer()
        val tmpFile = File.createTempFile("pipeline-read-", ".txt")
        tmpFile.deleteOnExit()
        tmpFile.writeText("Line 1\nLine 2\nLine 3\n")
        try {
            val args = JSONObject().put("path", tmpFile.absolutePath)
            val result = callTool(server, "read_file", args)
            val text = extractText(result)
            assertFalse(result.getBoolean("isError"))
            assertTrue(text.contains("3 lines"), "Should show line count: $text")
            assertTrue(text.contains("1: Line 1"), "Should have line numbers: $text")
            assertTrue(text.contains("2: Line 2"), text)
            assertTrue(text.contains("3: Line 3"), text)
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `read_file with offset and max_lines`() {
        val server = createServer()
        val tmpFile = File.createTempFile("pipeline-read-", ".txt")
        tmpFile.deleteOnExit()
        tmpFile.writeText((1..10).joinToString("\n") { "Line $it" })
        try {
            val args = JSONObject()
                .put("path", tmpFile.absolutePath)
                .put("offset", 3)
                .put("max_lines", 2)
            val result = callTool(server, "read_file", args)
            val text = extractText(result)
            assertFalse(result.getBoolean("isError"))
            assertTrue(text.contains("4: Line 4"), "Should start from offset: $text")
            assertTrue(text.contains("5: Line 5"), text)
            assertFalse(text.contains("3: Line 3"), "Should not include before offset")
            assertFalse(text.contains("6: Line 6"), "Should not exceed max_lines")
            assertTrue(text.contains("more lines"), "Should indicate remaining: $text")
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `read_file errors for missing file`() {
        val server = createServer()
        val args = JSONObject().put("path", "/nonexistent/file.txt")
        val result = callTool(server, "read_file", args)
        assertTrue(result.getBoolean("isError"))
        assertTrue(extractText(result).contains("File not found"))
    }

    @Test
    fun `read_file after save_to_file roundtrip`() {
        val server = createServer()
        val tmpFile = File.createTempFile("pipeline-roundtrip-", ".txt")
        tmpFile.deleteOnExit()
        try {
            // Save
            val saveArgs = JSONObject().put("path", tmpFile.absolutePath).put("content", "Saved content here")
            callTool(server, "save_to_file", saveArgs)

            // Read back
            val readArgs = JSONObject().put("path", tmpFile.absolutePath)
            val result = callTool(server, "read_file", readArgs)
            val text = extractText(result)
            assertFalse(result.getBoolean("isError"))
            assertTrue(text.contains("Saved content here"), "Should read back what was saved: $text")
        } finally {
            tmpFile.delete()
        }
    }

    // ───── run_pipeline tool tests ─────

    @Test
    fun `run_pipeline chains search-summarize-save`() {
        val server = createServer()
        val tmpDir = createTempSearchDir(
            "doc1.txt" to "Kotlin is a modern language for the JVM.\nKotlin supports multiplatform development.\nKotlin has coroutines for async.\nKotlin is concise.\nKotlin is safe.\nKotlin is expressive.\nKotlin is interoperable with Java.",
            "doc2.txt" to "Java is an older language.\nJava runs on the JVM too."
        )
        val outputFile = File.createTempFile("pipeline-output-", ".txt")
        outputFile.deleteOnExit()
        try {
            val args = JSONObject()
                .put("directory", tmpDir.absolutePath)
                .put("keyword", "kotlin")
                .put("output_path", outputFile.absolutePath)
                .put("max_sentences", 3)
            val result = callTool(server, "run_pipeline", args)
            val text = extractText(result)

            assertFalse(result.getBoolean("isError"))
            assertTrue(text.contains("Step 1/3"), "Should log step 1: $text")
            assertTrue(text.contains("Step 2/3"), "Should log step 2: $text")
            assertTrue(text.contains("Step 3/3"), "Should log step 3: $text")
            assertTrue(text.contains("completed successfully"), "Should complete: $text")

            // Verify output file
            val saved = outputFile.readText()
            assertTrue(saved.contains("Search Summary"), "Output should have header: $saved")
            assertTrue(saved.lowercase().contains("kotlin"), "Output should contain keyword: $saved")
        } finally {
            tmpDir.deleteRecursively()
            outputFile.delete()
        }
    }

    @Test
    fun `run_pipeline stops when search finds nothing`() {
        val server = createServer()
        val tmpDir = createTempSearchDir("empty.txt" to "nothing here")
        val outputFile = File(System.getProperty("java.io.tmpdir"), "pipeline-empty-${System.currentTimeMillis()}.txt")
        try {
            val args = JSONObject()
                .put("directory", tmpDir.absolutePath)
                .put("keyword", "zzzznotfound")
                .put("output_path", outputFile.absolutePath)
            val result = callTool(server, "run_pipeline", args)
            val text = extractText(result)
            assertTrue(text.contains("search returned no data"), "Should stop at search: $text")
            assertFalse(outputFile.exists(), "Output file should not be created")
        } finally {
            tmpDir.deleteRecursively()
            outputFile.delete()
        }
    }

    @Test
    fun `run_pipeline records execution history`() {
        val server = createServer()
        val tmpDir = createTempSearchDir("test.txt" to "hello world test")
        val outputFile = File.createTempFile("pipeline-hist-", ".txt")
        outputFile.deleteOnExit()
        try {
            val args = JSONObject()
                .put("directory", tmpDir.absolutePath)
                .put("keyword", "hello")
                .put("output_path", outputFile.absolutePath)
            callTool(server, "run_pipeline", args)
            assertEquals(1, server.pipelineHistory.size)
            assertTrue(server.pipelineHistory[0].success)
            assertEquals("hello", server.pipelineHistory[0].keyword)
        } finally {
            tmpDir.deleteRecursively()
            outputFile.delete()
        }
    }

    // ───── list_pipelines tool tests ─────

    @Test
    fun `list_pipelines shows templates and empty history`() {
        val server = createServer()
        val result = server.executeTool("list_pipelines", JSONObject())
        val text = extractText(result)
        assertFalse(result.getBoolean("isError"))
        assertTrue(text.contains("search → summarize → save_to_file"))
        assertTrue(text.contains("No pipeline executions yet"))
    }

    @Test
    fun `list_pipelines shows execution history`() {
        val server = createServer()
        server.pipelineHistory.add(PipelineExecution("test", "/tmp", "/tmp/out.txt", true, 42))
        val result = server.executeTool("list_pipelines", JSONObject())
        val text = extractText(result)
        assertTrue(text.contains("Recent executions (1)"), text)
        assertTrue(text.contains("'test'"), text)
    }

    // ───── unknown tool test ─────

    @Test
    fun `unknown tool returns error`() {
        val server = createServer()
        val result = server.executeTool("nonexistent", JSONObject())
        assertTrue(result.getBoolean("isError"))
        assertTrue(extractText(result).contains("Unknown tool"))
    }

    // ───── Data flow validation ─────

    @Test
    fun `pipeline correctly passes data between steps`() {
        val server = createServer()
        // Create directory with specific content
        val tmpDir = createTempSearchDir(
            "data.txt" to "The pipeline pattern is powerful.\nPipelines compose tools sequentially.\nEach step transforms the data.\nPipelines enable automation.\nThe output flows through each stage.\nPipelines reduce manual work."
        )
        val outputFile = File.createTempFile("pipeline-flow-", ".txt")
        outputFile.deleteOnExit()
        try {
            val args = JSONObject()
                .put("directory", tmpDir.absolutePath)
                .put("keyword", "pipeline")
                .put("output_path", outputFile.absolutePath)
                .put("max_sentences", 2)
            val result = callTool(server, "run_pipeline", args)
            assertFalse(result.getBoolean("isError"))

            val saved = outputFile.readText()
            // Header present
            assertTrue(saved.startsWith("# Search Summary: 'pipeline'"), "Should have correct header")
            // Summary should be shorter than raw search results
            assertTrue(saved.length < 500, "Summary should be concise, got ${saved.length} chars")
            // Should contain the keyword
            assertTrue(saved.lowercase().contains("pipeline"), "Summary should contain keyword")
        } finally {
            tmpDir.deleteRecursively()
            outputFile.delete()
        }
    }

    // ───── Full JSON-RPC roundtrip ─────

    @Test
    fun `full JSON-RPC roundtrip for search tool`() {
        val server = createServer()
        val tmpDir = createTempSearchDir("file.kt" to "fun hello() = println(\"world\")")
        try {
            val params = JSONObject()
                .put("name", "search")
                .put("arguments", JSONObject()
                    .put("directory", tmpDir.absolutePath)
                    .put("keyword", "hello"))
            val request = jsonRpc(42, "tools/call", params)
            val response = server.handleMessage(request)!!
            val json = JSONObject(response)
            assertEquals("2.0", json.getString("jsonrpc"))
            assertEquals(42, json.getInt("id"))
            val result = json.getJSONObject("result")
            val text = result.getJSONArray("content").getJSONObject(0).getString("text")
            assertTrue(text.contains("Found 1 match"), text)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    // ───── Helper ─────

    private fun createTempSearchDir(vararg files: Pair<String, String>): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "pipeline-search-${System.currentTimeMillis()}")
        dir.mkdirs()
        for ((name, content) in files) {
            val f = File(dir, name)
            f.parentFile?.mkdirs()
            f.writeText(content, Charsets.UTF_8)
        }
        return dir
    }
}

package indexing

import mcp.server.IndexingMcpServer
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File

class IndexingMcpServerTest {

    private val server = IndexingMcpServer()

    @Test
    fun `initialize returns server info`() {
        val msg = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""
        val response = server.handleMessage(msg)
        assertNotNull(response)
        val json = JSONObject(response!!)
        val result = json.getJSONObject("result")
        assertEquals("2024-11-05", result.getString("protocolVersion"))
        val info = result.getJSONObject("serverInfo")
        assertEquals("Document Indexing MCP Server", info.getString("name"))
    }

    @Test
    fun `tools list returns all 6 tools`() {
        val msg = """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""
        val response = server.handleMessage(msg)
        assertNotNull(response)
        val json = JSONObject(response!!)
        val tools = json.getJSONObject("result").getJSONArray("tools")
        assertEquals(6, tools.length())

        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.toSet()
        assertTrue("load_documents" in names)
        assertTrue("index_documents" in names)
        assertTrue("search_index" in names)
        assertTrue("get_index_stats" in names)
        assertTrue("compare_chunking" in names)
        assertTrue("load_index" in names)
    }

    @Test
    fun `load_documents scans directory`(@TempDir tempDir: File) {
        // Create test files
        File(tempDir, "readme.md").writeText("# Title\nSome content\n\n## Section\nMore content")
        File(tempDir, "code.kt").writeText("package test\n\nfun main() {\n    println(\"hello\")\n}")
        File(tempDir, "data.txt").writeText("Line 1\nLine 2\nLine 3")
        File(tempDir, "binary.bin").writeBytes(byteArrayOf(0, 1, 2, 3)) // should be skipped

        val args = JSONObject().put("directory", tempDir.absolutePath)
        val result = server.executeTool("load_documents", args)
        val text = extractText(result)

        assertTrue(text.contains("Loaded 3 documents"), "Expected 3 docs: $text")
        assertTrue(text.contains(".md:"))
        assertTrue(text.contains(".kt:"))
    }

    @Test
    fun `load_documents with extension filter`(@TempDir tempDir: File) {
        File(tempDir, "a.md").writeText("markdown content")
        File(tempDir, "b.kt").writeText("kotlin content")
        File(tempDir, "c.txt").writeText("text content")

        val args = JSONObject()
            .put("directory", tempDir.absolutePath)
            .put("extensions", "md,txt")
        val result = server.executeTool("load_documents", args)
        val text = extractText(result)

        assertTrue(text.contains("Loaded 2 documents"), "Expected 2 docs: $text")
    }

    @Test
    fun `load_documents returns error for nonexistent directory`() {
        val args = JSONObject().put("directory", "/nonexistent/path/xyz")
        val result = server.executeTool("load_documents", args)
        assertTrue(result.optBoolean("isError", false))
    }

    @Test
    fun `compare_chunking requires loaded documents`() {
        val result = server.executeTool("compare_chunking", JSONObject())
        assertTrue(result.optBoolean("isError", false))
    }

    @Test
    fun `compare_chunking produces report after loading`(@TempDir tempDir: File) {
        val md = buildString {
            appendLine("# Introduction")
            appendLine("This is a comprehensive guide to testing.")
            appendLine()
            appendLine("## Getting Started")
            repeat(30) { appendLine("Setup instruction line $it with detailed explanation.") }
            appendLine()
            appendLine("## Advanced Topics")
            repeat(30) { appendLine("Advanced topic line $it with complex details.") }
            appendLine()
            appendLine("## Conclusion")
            appendLine("Summary of the guide.")
        }
        File(tempDir, "guide.md").writeText(md)

        val kt = buildString {
            appendLine("package example")
            appendLine()
            appendLine("class Calculator {")
            repeat(20) { appendLine("    fun method$it() = $it * 2") }
            appendLine("}")
            appendLine()
            appendLine("fun helper() {")
            appendLine("    println(\"help\")")
            appendLine("}")
        }
        File(tempDir, "calc.kt").writeText(kt)

        // Load first
        server.executeTool("load_documents", JSONObject().put("directory", tempDir.absolutePath))

        // Compare
        val result = server.executeTool("compare_chunking", JSONObject().put("chunk_size", 300))
        val text = extractText(result)

        assertTrue(text.contains("Strategy 1: Fixed-size"), text)
        assertTrue(text.contains("Strategy 2: Structural"), text)
        assertTrue(text.contains("Summary"), text)
        assertTrue(text.contains("Text coverage"), text)
    }

    @Test
    fun `get_index_stats on empty index`() {
        val result = server.executeTool("get_index_stats", JSONObject())
        val text = extractText(result)
        assertTrue(text.contains("empty"), text)
    }

    @Test
    fun `index_documents without loading returns error`() {
        val args = JSONObject()
            .put("strategy", "fixed")
            .put("api_key", "test-key")
        val result = server.executeTool("index_documents", args)
        assertTrue(result.optBoolean("isError", false))
    }

    @Test
    fun `search_index on empty index returns error`() {
        val args = JSONObject()
            .put("query", "test")
            .put("api_key", "test-key")
        val result = server.executeTool("search_index", args)
        assertTrue(result.optBoolean("isError", false))
    }

    @Test
    fun `unknown tool returns error`() {
        val result = server.executeTool("nonexistent_tool", JSONObject())
        assertTrue(result.optBoolean("isError", false))
    }

    @Test
    fun `notification messages return null`() {
        val msg = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        val response = server.handleMessage(msg)
        assertNull(response)
    }

    private fun extractText(result: JSONObject): String {
        val content = result.optJSONArray("content") ?: return ""
        if (content.length() == 0) return ""
        return content.getJSONObject(0).optString("text", "")
    }
}

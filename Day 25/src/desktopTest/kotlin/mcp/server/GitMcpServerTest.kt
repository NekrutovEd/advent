package mcp.server

import org.json.JSONObject
import kotlin.test.*

class GitMcpServerTest {

    private val server = GitMcpServer()

    // ───── JSON-RPC protocol tests ─────

    @Test
    fun `initialize returns server info`() {
        val request = jsonRpc(1, "initialize", JSONObject())
        val response = server.handleMessage(request)!!
        val json = JSONObject(response)
        assertEquals("2.0", json.getString("jsonrpc"))
        assertEquals(1, json.getInt("id"))
        val result = json.getJSONObject("result")
        assertEquals("2024-11-05", result.getString("protocolVersion"))
        val serverInfo = result.getJSONObject("serverInfo")
        assertEquals("Git MCP Server", serverInfo.getString("name"))
        assertEquals("1.0.0", serverInfo.getString("version"))
    }

    @Test
    fun `tools list returns all git tools`() {
        val request = jsonRpc(2, "tools/list", JSONObject())
        val response = server.handleMessage(request)!!
        val json = JSONObject(response)
        val tools = json.getJSONObject("result").getJSONArray("tools")
        assertTrue(tools.length() >= 20, "Should have at least 20 git tools, got ${tools.length()}")

        // Check some known tools exist
        val names = (0 until tools.length()).map {
            tools.getJSONObject(it).getString("name")
        }
        assertContains(names, "git_init")
        assertContains(names, "git_status")
        assertContains(names, "git_commit")
        assertContains(names, "git_log")
        assertContains(names, "git_diff")
        assertContains(names, "git_branch_list")
        assertContains(names, "git_checkout")
        assertContains(names, "git_push")
        assertContains(names, "git_pull")
        assertContains(names, "git_stash")
        assertContains(names, "git_tag")
        assertContains(names, "git_reset")
        assertContains(names, "git_remote")
        assertContains(names, "git_show")
        assertContains(names, "git_rev_parse")
    }

    @Test
    fun `tools have input schemas with required fields`() {
        val request = jsonRpc(1, "tools/list", JSONObject())
        val response = server.handleMessage(request)!!
        val tools = JSONObject(response).getJSONObject("result").getJSONArray("tools")

        val commitTool = (0 until tools.length())
            .map { tools.getJSONObject(it) }
            .first { it.getString("name") == "git_commit" }

        val schema = commitTool.getJSONObject("inputSchema")
        assertEquals("object", schema.getString("type"))
        val props = schema.getJSONObject("properties")
        assertTrue(props.has("path"))
        assertTrue(props.has("message"))

        val required = (0 until schema.getJSONArray("required").length())
            .map { schema.getJSONArray("required").getString(it) }
        assertContains(required, "path")
        assertContains(required, "message")
    }

    @Test
    fun `notification returns null`() {
        // Notifications have no id
        val notification = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "notifications/initialized")
            .toString()
        assertNull(server.handleMessage(notification))
    }

    @Test
    fun `unknown method returns error`() {
        val request = jsonRpc(99, "unknown/method", JSONObject())
        val response = server.handleMessage(request)!!
        val json = JSONObject(response)
        assertTrue(json.has("error"))
        assertEquals(-32601, json.getJSONObject("error").getInt("code"))
    }

    @Test
    fun `invalid JSON returns parse error`() {
        val response = server.handleMessage("not json at all")!!
        val json = JSONObject(response)
        assertTrue(json.has("error"))
        assertEquals(-32700, json.getJSONObject("error").getInt("code"))
    }

    // ───── Tool execution tests (with a temp git repo) ─────

    @Test
    fun `git_init creates repository`() {
        val tmpDir = createTempDir("git-mcp-test-")
        try {
            val result = server.executeTool("git_init", JSONObject().put("path", tmpDir.absolutePath))
            assertFalse(result.getBoolean("isError"), extractText(result))
            assertTrue(java.io.File(tmpDir, ".git").exists(), ".git directory should exist")
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `git_status shows clean repo`() {
        val tmpDir = initTempRepo()
        try {
            val result = server.executeTool("git_status", JSONObject().put("path", tmpDir.absolutePath))
            assertFalse(result.getBoolean("isError"), extractText(result))
            val text = extractText(result)
            assertTrue(text.contains("##"), "Status should show branch info, got: $text")
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `git_add and git_commit workflow`() {
        val tmpDir = initTempRepo()
        try {
            // Create a file
            java.io.File(tmpDir, "hello.txt").writeText("Hello, Git MCP!")

            // Add
            val addResult = server.executeTool("git_add", JSONObject()
                .put("path", tmpDir.absolutePath)
                .put("files", "hello.txt"))
            assertFalse(addResult.getBoolean("isError"), extractText(addResult))

            // Commit
            val commitResult = server.executeTool("git_commit", JSONObject()
                .put("path", tmpDir.absolutePath)
                .put("message", "Initial commit"))
            assertFalse(commitResult.getBoolean("isError"), extractText(commitResult))
            assertTrue(extractText(commitResult).contains("Initial commit"))

            // Log
            val logResult = server.executeTool("git_log", JSONObject()
                .put("path", tmpDir.absolutePath))
            assertFalse(logResult.getBoolean("isError"), extractText(logResult))
            assertTrue(extractText(logResult).contains("Initial commit"))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `git_diff shows changes`() {
        val tmpDir = initTempRepo()
        try {
            val file = java.io.File(tmpDir, "data.txt")
            file.writeText("line1\n")
            runGitCmd(tmpDir, "add", ".")
            runGitCmd(tmpDir, "commit", "-m", "first")

            file.appendText("line2\n")

            val result = server.executeTool("git_diff", JSONObject()
                .put("path", tmpDir.absolutePath))
            assertFalse(result.getBoolean("isError"))
            assertTrue(extractText(result).contains("+line2"))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `git_branch_create and git_branch_list`() {
        val tmpDir = initTempRepoWithCommit()
        try {
            // Create branch
            val createResult = server.executeTool("git_branch_create", JSONObject()
                .put("path", tmpDir.absolutePath)
                .put("name", "feature"))
            assertFalse(createResult.getBoolean("isError"), extractText(createResult))

            // List branches
            val listResult = server.executeTool("git_branch_list", JSONObject()
                .put("path", tmpDir.absolutePath))
            assertFalse(listResult.getBoolean("isError"))
            val text = extractText(listResult)
            assertTrue(text.contains("feature"), "Should list feature branch, got: $text")
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `git_checkout switches branch`() {
        val tmpDir = initTempRepoWithCommit()
        try {
            runGitCmd(tmpDir, "branch", "dev")
            val result = server.executeTool("git_checkout", JSONObject()
                .put("path", tmpDir.absolutePath)
                .put("ref", "dev"))
            assertFalse(result.getBoolean("isError"), extractText(result))

            // Verify branch
            val revResult = server.executeTool("git_rev_parse", JSONObject()
                .put("path", tmpDir.absolutePath)
                .put("query", "branch"))
            assertEquals("dev", extractText(revResult).trim())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `git_checkout with create flag`() {
        val tmpDir = initTempRepoWithCommit()
        try {
            val result = server.executeTool("git_checkout", JSONObject()
                .put("path", tmpDir.absolutePath)
                .put("ref", "new-branch")
                .put("create", true))
            assertFalse(result.getBoolean("isError"), extractText(result))

            val revResult = server.executeTool("git_rev_parse", JSONObject()
                .put("path", tmpDir.absolutePath)
                .put("query", "branch"))
            assertEquals("new-branch", extractText(revResult).trim())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `git_tag create and list`() {
        val tmpDir = initTempRepoWithCommit()
        try {
            val createResult = server.executeTool("git_tag", JSONObject()
                .put("path", tmpDir.absolutePath)
                .put("action", "create")
                .put("name", "v1.0"))
            assertFalse(createResult.getBoolean("isError"), extractText(createResult))

            val listResult = server.executeTool("git_tag", JSONObject()
                .put("path", tmpDir.absolutePath)
                .put("action", "list"))
            assertFalse(listResult.getBoolean("isError"))
            assertTrue(extractText(listResult).contains("v1.0"))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `git_rev_parse queries`() {
        val tmpDir = initTempRepoWithCommit()
        try {
            // is_repo
            val isRepo = server.executeTool("git_rev_parse", JSONObject()
                .put("path", tmpDir.absolutePath)
                .put("query", "is_repo"))
            assertEquals("true", extractText(isRepo).trim())

            // root
            val root = server.executeTool("git_rev_parse", JSONObject()
                .put("path", tmpDir.absolutePath)
                .put("query", "root"))
            assertFalse(root.getBoolean("isError"))

            // head (should be a SHA)
            val head = server.executeTool("git_rev_parse", JSONObject()
                .put("path", tmpDir.absolutePath)
                .put("query", "head"))
            assertTrue(extractText(head).trim().matches(Regex("[0-9a-f]{40}")))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `git_reset unstages files`() {
        val tmpDir = initTempRepoWithCommit()
        try {
            val file = java.io.File(tmpDir, "new.txt")
            file.writeText("new content")
            runGitCmd(tmpDir, "add", "new.txt")

            val result = server.executeTool("git_reset", JSONObject()
                .put("path", tmpDir.absolutePath)
                .put("mode", "mixed")
                .put("files", "new.txt"))
            assertFalse(result.getBoolean("isError"), extractText(result))

            // Verify it's unstaged
            val status = server.executeTool("git_status", JSONObject()
                .put("path", tmpDir.absolutePath))
            val statusText = extractText(status)
            assertTrue(statusText.contains("??") || statusText.contains("new.txt"),
                "File should be untracked/unstaged, got: $statusText")
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `git_show displays commit details`() {
        val tmpDir = initTempRepoWithCommit()
        try {
            val result = server.executeTool("git_show", JSONObject()
                .put("path", tmpDir.absolutePath)
                .put("stat", true))
            assertFalse(result.getBoolean("isError"))
            assertTrue(extractText(result).contains("init"))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `git_commit with all flag`() {
        val tmpDir = initTempRepoWithCommit()
        try {
            java.io.File(tmpDir, "README.txt").writeText("updated")
            val result = server.executeTool("git_commit", JSONObject()
                .put("path", tmpDir.absolutePath)
                .put("message", "auto-add commit")
                .put("all", true))
            assertFalse(result.getBoolean("isError"), extractText(result))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `unknown tool returns error`() {
        val result = server.executeTool("nonexistent", JSONObject())
        assertTrue(result.getBoolean("isError"))
        assertTrue(extractText(result).contains("Unknown tool"))
    }

    @Test
    fun `tools call via JSON-RPC`() {
        val tmpDir = initTempRepo()
        try {
            val params = JSONObject()
                .put("name", "git_status")
                .put("arguments", JSONObject().put("path", tmpDir.absolutePath))
            val request = jsonRpc(10, "tools/call", params)
            val response = server.handleMessage(request)!!
            val json = JSONObject(response)
            assertEquals(10, json.getInt("id"))
            val result = json.getJSONObject("result")
            assertFalse(result.getBoolean("isError"))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    // ───── Helpers ─────

    private fun jsonRpc(id: Int, method: String, params: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
            .toString()

    private fun extractText(result: JSONObject): String =
        result.getJSONArray("content").getJSONObject(0).getString("text")

    private fun initTempRepo(): java.io.File {
        val dir = createTempDir("git-mcp-test-")
        runGitCmd(dir, "init")
        runGitCmd(dir, "config", "user.email", "test@test.com")
        runGitCmd(dir, "config", "user.name", "Test")
        return dir
    }

    private fun initTempRepoWithCommit(): java.io.File {
        val dir = initTempRepo()
        java.io.File(dir, "README.txt").writeText("init")
        runGitCmd(dir, "add", ".")
        runGitCmd(dir, "commit", "-m", "init")
        return dir
    }

    private fun runGitCmd(dir: java.io.File, vararg args: String) {
        val pb = ProcessBuilder(buildList { add("git"); addAll(args) })
        pb.directory(dir)
        pb.redirectErrorStream(true)
        val p = pb.start()
        p.inputStream.readBytes()
        p.waitFor()
    }

    @Suppress("DEPRECATION")
    private fun createTempDir(prefix: String): java.io.File =
        kotlin.io.createTempDir(prefix)
}

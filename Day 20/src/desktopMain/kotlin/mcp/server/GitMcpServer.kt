package mcp.server

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintStream
import java.util.concurrent.TimeUnit

/**
 * MCP server for Git operations.
 * Communicates via JSON-RPC 2.0 over stdin/stdout.
 *
 * Supported tools: git_init, git_clone, git_status, git_add, git_commit,
 * git_log, git_diff, git_branch_list, git_branch_create, git_branch_delete,
 * git_checkout, git_merge, git_push, git_pull, git_stash, git_tag,
 * git_reset, git_remote, git_show, git_rev_parse.
 */
fun main() {
    val server = GitMcpServer()
    server.run()
}

class GitMcpServer {

    private val tools = listOf(
        toolDef(
            "git_init", "Initialize a new Git repository",
            prop("path", "string", "Directory to initialize (created if missing)")
        ),
        toolDef(
            "git_clone", "Clone a remote repository",
            prop("url", "string", "Repository URL to clone"),
            prop("path", "string", "Destination directory", required = false)
        ),
        toolDef(
            "git_status", "Show working tree status",
            prop("path", "string", "Repository path")
        ),
        toolDef(
            "git_add", "Add files to the staging area",
            prop("path", "string", "Repository path"),
            prop("files", "string", "Files to add (space-separated, or '.' for all)")
        ),
        toolDef(
            "git_commit", "Create a commit with the staged changes",
            prop("path", "string", "Repository path"),
            prop("message", "string", "Commit message"),
            prop("all", "boolean", "Stage all modified/deleted files before committing (-a)", required = false)
        ),
        toolDef(
            "git_log", "Show commit log",
            prop("path", "string", "Repository path"),
            prop("count", "integer", "Number of commits to show (default 10)", required = false),
            prop("oneline", "boolean", "One-line format (default true)", required = false),
            prop("branch", "string", "Branch or ref to show log for", required = false)
        ),
        toolDef(
            "git_diff", "Show changes between commits, staging area, and working tree",
            prop("path", "string", "Repository path"),
            prop("staged", "boolean", "Show staged changes (--cached)", required = false),
            prop("file", "string", "Limit diff to specific file", required = false),
            prop("ref", "string", "Diff against a specific ref (commit/branch)", required = false)
        ),
        toolDef(
            "git_branch_list", "List branches",
            prop("path", "string", "Repository path"),
            prop("all", "boolean", "Show remote branches too (-a)", required = false)
        ),
        toolDef(
            "git_branch_create", "Create a new branch",
            prop("path", "string", "Repository path"),
            prop("name", "string", "Branch name"),
            prop("start_point", "string", "Starting commit/branch", required = false)
        ),
        toolDef(
            "git_branch_delete", "Delete a branch",
            prop("path", "string", "Repository path"),
            prop("name", "string", "Branch name"),
            prop("force", "boolean", "Force delete (-D)", required = false)
        ),
        toolDef(
            "git_checkout", "Switch branches or restore working tree files",
            prop("path", "string", "Repository path"),
            prop("ref", "string", "Branch name, tag, or commit to check out"),
            prop("create", "boolean", "Create new branch (-b)", required = false)
        ),
        toolDef(
            "git_merge", "Merge a branch into the current branch",
            prop("path", "string", "Repository path"),
            prop("branch", "string", "Branch to merge"),
            prop("message", "string", "Merge commit message", required = false),
            prop("no_ff", "boolean", "Create merge commit even for fast-forward (--no-ff)", required = false)
        ),
        toolDef(
            "git_push", "Push commits to a remote repository",
            prop("path", "string", "Repository path"),
            prop("remote", "string", "Remote name (default 'origin')", required = false),
            prop("branch", "string", "Branch to push", required = false),
            prop("set_upstream", "boolean", "Set upstream (-u)", required = false),
            prop("tags", "boolean", "Push tags (--tags)", required = false)
        ),
        toolDef(
            "git_pull", "Fetch and integrate changes from a remote repository",
            prop("path", "string", "Repository path"),
            prop("remote", "string", "Remote name", required = false),
            prop("branch", "string", "Branch to pull", required = false),
            prop("rebase", "boolean", "Rebase instead of merge (--rebase)", required = false)
        ),
        toolDef(
            "git_stash", "Stash changes in the working directory",
            prop("path", "string", "Repository path"),
            prop("action", "string", "Action: push, pop, list, apply, drop (default 'push')"),
            prop("message", "string", "Stash message (for push)", required = false),
            prop("index", "integer", "Stash index (for apply/drop)", required = false)
        ),
        toolDef(
            "git_tag", "Create, list, or delete tags",
            prop("path", "string", "Repository path"),
            prop("action", "string", "Action: list, create, delete (default 'list')"),
            prop("name", "string", "Tag name (for create/delete)", required = false),
            prop("message", "string", "Tag message for annotated tag", required = false),
            prop("ref", "string", "Commit to tag (default HEAD)", required = false)
        ),
        toolDef(
            "git_reset", "Reset current HEAD to a specified state",
            prop("path", "string", "Repository path"),
            prop("mode", "string", "Reset mode: soft, mixed, hard (default 'mixed')"),
            prop("ref", "string", "Target commit (default HEAD)", required = false),
            prop("files", "string", "Unstage specific files (space-separated)", required = false)
        ),
        toolDef(
            "git_remote", "Manage remote repositories",
            prop("path", "string", "Repository path"),
            prop("action", "string", "Action: list, add, remove, show (default 'list')"),
            prop("name", "string", "Remote name (for add/remove/show)", required = false),
            prop("url", "string", "Remote URL (for add)", required = false)
        ),
        toolDef(
            "git_show", "Show details of a commit or object",
            prop("path", "string", "Repository path"),
            prop("ref", "string", "Commit/tag/object to show (default HEAD)", required = false),
            prop("stat", "boolean", "Show diffstat only (--stat)", required = false)
        ),
        toolDef(
            "git_rev_parse", "Get repository information (current branch, root, HEAD hash, etc.)",
            prop("path", "string", "Repository path"),
            prop("query", "string", "What to resolve: branch, root, head, is_repo (default 'branch')")
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
        val msg = try {
            JSONObject(line)
        } catch (_: Exception) {
            return errorResponse(null, -32700, "Parse error")
        }
        val id = msg.opt("id")
        val method = msg.optString("method", "")
        val params = msg.optJSONObject("params") ?: JSONObject()

        // Notifications have no id — no response
        if (id == null || id == JSONObject.NULL) {
            return null
        }

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
                .put("name", "Git MCP Server")
                .put("version", "1.0.0"))
        return successResponse(id, result)
    }

    private fun handleListTools(id: Any): String {
        val toolsArray = JSONArray()
        for (tool in tools) {
            toolsArray.put(tool)
        }
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
                "git_init" -> execGitInit(args)
                "git_clone" -> execGitClone(args)
                "git_status" -> execGitStatus(args)
                "git_add" -> execGitAdd(args)
                "git_commit" -> execGitCommit(args)
                "git_log" -> execGitLog(args)
                "git_diff" -> execGitDiff(args)
                "git_branch_list" -> execGitBranchList(args)
                "git_branch_create" -> execGitBranchCreate(args)
                "git_branch_delete" -> execGitBranchDelete(args)
                "git_checkout" -> execGitCheckout(args)
                "git_merge" -> execGitMerge(args)
                "git_push" -> execGitPush(args)
                "git_pull" -> execGitPull(args)
                "git_stash" -> execGitStash(args)
                "git_tag" -> execGitTag(args)
                "git_reset" -> execGitReset(args)
                "git_remote" -> execGitRemote(args)
                "git_show" -> execGitShow(args)
                "git_rev_parse" -> execGitRevParse(args)
                else -> toolError("Unknown tool: $name")
            }
        } catch (e: Exception) {
            toolError("Error: ${e.message}")
        }
    }

    // ───── Tool implementations ─────

    private fun execGitInit(args: JSONObject): JSONObject {
        val path = args.getString("path")
        val dir = File(path)
        if (!dir.exists()) dir.mkdirs()
        return runGit(dir, "init")
    }

    private fun execGitClone(args: JSONObject): JSONObject {
        val url = args.getString("url")
        val path = args.optString("path", "")
        val cmd = buildList {
            add("clone"); add(url)
            if (path.isNotBlank()) add(path)
        }
        val workDir = if (path.isNotBlank()) File(path).parentFile ?: File(".") else File(".")
        if (!workDir.exists()) workDir.mkdirs()
        return runGit(workDir, *cmd.toTypedArray())
    }

    private fun execGitStatus(args: JSONObject): JSONObject {
        val dir = File(args.getString("path"))
        return runGit(dir, "status", "--short", "--branch")
    }

    private fun execGitAdd(args: JSONObject): JSONObject {
        val dir = File(args.getString("path"))
        val files = args.getString("files").split("\\s+".toRegex())
        return runGit(dir, "add", *files.toTypedArray())
    }

    private fun execGitCommit(args: JSONObject): JSONObject {
        val dir = File(args.getString("path"))
        val message = args.getString("message")
        val all = args.optBoolean("all", false)
        val cmd = buildList {
            add("commit")
            if (all) add("-a")
            add("-m"); add(message)
        }
        return runGit(dir, *cmd.toTypedArray())
    }

    private fun execGitLog(args: JSONObject): JSONObject {
        val dir = File(args.getString("path"))
        val count = args.optInt("count", 10)
        val oneline = args.optBoolean("oneline", true)
        val branch = args.optString("branch", "")
        val cmd = buildList {
            add("log")
            add("-n"); add(count.toString())
            if (oneline) add("--oneline")
            if (branch.isNotBlank()) add(branch)
        }
        return runGit(dir, *cmd.toTypedArray())
    }

    private fun execGitDiff(args: JSONObject): JSONObject {
        val dir = File(args.getString("path"))
        val staged = args.optBoolean("staged", false)
        val file = args.optString("file", "")
        val ref = args.optString("ref", "")
        val cmd = buildList {
            add("diff")
            if (staged) add("--cached")
            if (ref.isNotBlank()) add(ref)
            if (file.isNotBlank()) { add("--"); add(file) }
        }
        return runGit(dir, *cmd.toTypedArray())
    }

    private fun execGitBranchList(args: JSONObject): JSONObject {
        val dir = File(args.getString("path"))
        val all = args.optBoolean("all", false)
        val cmd = buildList {
            add("branch")
            if (all) add("-a")
            add("-v")
        }
        return runGit(dir, *cmd.toTypedArray())
    }

    private fun execGitBranchCreate(args: JSONObject): JSONObject {
        val dir = File(args.getString("path"))
        val name = args.getString("name")
        val startPoint = args.optString("start_point", "")
        val cmd = buildList {
            add("branch"); add(name)
            if (startPoint.isNotBlank()) add(startPoint)
        }
        return runGit(dir, *cmd.toTypedArray())
    }

    private fun execGitBranchDelete(args: JSONObject): JSONObject {
        val dir = File(args.getString("path"))
        val name = args.getString("name")
        val force = args.optBoolean("force", false)
        return runGit(dir, "branch", if (force) "-D" else "-d", name)
    }

    private fun execGitCheckout(args: JSONObject): JSONObject {
        val dir = File(args.getString("path"))
        val ref = args.getString("ref")
        val create = args.optBoolean("create", false)
        val cmd = buildList {
            add("checkout")
            if (create) add("-b")
            add(ref)
        }
        return runGit(dir, *cmd.toTypedArray())
    }

    private fun execGitMerge(args: JSONObject): JSONObject {
        val dir = File(args.getString("path"))
        val branch = args.getString("branch")
        val message = args.optString("message", "")
        val noFf = args.optBoolean("no_ff", false)
        val cmd = buildList {
            add("merge")
            if (noFf) add("--no-ff")
            if (message.isNotBlank()) { add("-m"); add(message) }
            add(branch)
        }
        return runGit(dir, *cmd.toTypedArray())
    }

    private fun execGitPush(args: JSONObject): JSONObject {
        val dir = File(args.getString("path"))
        val remote = args.optString("remote", "")
        val branch = args.optString("branch", "")
        val setUpstream = args.optBoolean("set_upstream", false)
        val tags = args.optBoolean("tags", false)
        val cmd = buildList {
            add("push")
            if (setUpstream) add("-u")
            if (tags) add("--tags")
            if (remote.isNotBlank()) add(remote)
            if (branch.isNotBlank()) add(branch)
        }
        return runGit(dir, *cmd.toTypedArray())
    }

    private fun execGitPull(args: JSONObject): JSONObject {
        val dir = File(args.getString("path"))
        val remote = args.optString("remote", "")
        val branch = args.optString("branch", "")
        val rebase = args.optBoolean("rebase", false)
        val cmd = buildList {
            add("pull")
            if (rebase) add("--rebase")
            if (remote.isNotBlank()) add(remote)
            if (branch.isNotBlank()) add(branch)
        }
        return runGit(dir, *cmd.toTypedArray())
    }

    private fun execGitStash(args: JSONObject): JSONObject {
        val dir = File(args.getString("path"))
        val action = args.optString("action", "push")
        val message = args.optString("message", "")
        val index = args.optInt("index", -1)
        val cmd = buildList {
            add("stash")
            add(action)
            when (action) {
                "push" -> if (message.isNotBlank()) { add("-m"); add(message) }
                "apply", "drop" -> if (index >= 0) add("stash@{$index}")
            }
        }
        return runGit(dir, *cmd.toTypedArray())
    }

    private fun execGitTag(args: JSONObject): JSONObject {
        val dir = File(args.getString("path"))
        val action = args.optString("action", "list")
        return when (action) {
            "list" -> runGit(dir, "tag", "-l")
            "create" -> {
                val name = args.getString("name")
                val message = args.optString("message", "")
                val ref = args.optString("ref", "")
                val cmd = buildList {
                    add("tag")
                    if (message.isNotBlank()) { add("-a"); add(name); add("-m"); add(message) }
                    else add(name)
                    if (ref.isNotBlank()) add(ref)
                }
                runGit(dir, *cmd.toTypedArray())
            }
            "delete" -> runGit(dir, "tag", "-d", args.getString("name"))
            else -> toolError("Unknown tag action: $action")
        }
    }

    private fun execGitReset(args: JSONObject): JSONObject {
        val dir = File(args.getString("path"))
        val mode = args.optString("mode", "mixed")
        val ref = args.optString("ref", "")
        val files = args.optString("files", "")
        if (files.isNotBlank()) {
            // Unstage specific files
            val fileList = files.split("\\s+".toRegex())
            return runGit(dir, "reset", "HEAD", "--", *fileList.toTypedArray())
        }
        val cmd = buildList {
            add("reset")
            add("--$mode")
            if (ref.isNotBlank()) add(ref)
        }
        return runGit(dir, *cmd.toTypedArray())
    }

    private fun execGitRemote(args: JSONObject): JSONObject {
        val dir = File(args.getString("path"))
        val action = args.optString("action", "list")
        return when (action) {
            "list" -> runGit(dir, "remote", "-v")
            "add" -> {
                val name = args.getString("name")
                val url = args.getString("url")
                runGit(dir, "remote", "add", name, url)
            }
            "remove" -> runGit(dir, "remote", "remove", args.getString("name"))
            "show" -> runGit(dir, "remote", "show", args.optString("name", "origin"))
            else -> toolError("Unknown remote action: $action")
        }
    }

    private fun execGitShow(args: JSONObject): JSONObject {
        val dir = File(args.getString("path"))
        val ref = args.optString("ref", "HEAD")
        val stat = args.optBoolean("stat", false)
        val cmd = buildList {
            add("show")
            if (stat) add("--stat")
            add(ref)
        }
        return runGit(dir, *cmd.toTypedArray())
    }

    private fun execGitRevParse(args: JSONObject): JSONObject {
        val dir = File(args.getString("path"))
        val query = args.optString("query", "branch")
        return when (query) {
            "branch" -> runGit(dir, "rev-parse", "--abbrev-ref", "HEAD")
            "root" -> runGit(dir, "rev-parse", "--show-toplevel")
            "head" -> runGit(dir, "rev-parse", "HEAD")
            "is_repo" -> runGit(dir, "rev-parse", "--is-inside-work-tree")
            else -> toolError("Unknown query: $query. Use: branch, root, head, is_repo")
        }
    }

    // ───── Helpers ─────

    private fun runGit(workDir: File, vararg args: String): JSONObject {
        val cmd = buildList {
            add("git")
            addAll(args)
        }
        val pb = ProcessBuilder(cmd)
        pb.directory(workDir)
        pb.redirectErrorStream(true)
        val process = pb.start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val exitCode = process.waitFor(30, TimeUnit.SECONDS)
        val code = if (exitCode) process.exitValue() else -1

        val isError = code != 0
        val text = if (output.isBlank() && !isError) "OK" else output.trimEnd()
        return toolResult(text, isError)
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

package mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class McpClient : McpClientInterface {
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var nextId = 1

    override val isConnected: Boolean get() = process?.isAlive == true

    override suspend fun connect(
        command: String,
        args: List<String>,
        env: Map<String, String>
    ): McpInitResult = withContext(Dispatchers.IO) {
        disconnect()

        val cmdList = buildList {
            add(command)
            addAll(args)
        }
        val pb = ProcessBuilder(cmdList)
        pb.redirectErrorStream(false)
        env.forEach { (k, v) -> pb.environment()[k] = v }

        val proc = pb.start()
        process = proc
        reader = BufferedReader(InputStreamReader(proc.inputStream))
        writer = BufferedWriter(OutputStreamWriter(proc.outputStream))

        // Send initialize request
        val initResult = sendRequest("initialize", buildJsonObject {
            put("protocolVersion", "2024-11-05")
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject {
                put("name", "AI Advent MCP Client")
                put("version", "1.0.0")
            })
        })

        // Send initialized notification
        sendNotification("notifications/initialized")

        // Parse server info from initialize result
        val serverInfo = initResult?.get("serverInfo")?.jsonObject
        McpInitResult(
            serverName = serverInfo?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
            serverVersion = serverInfo?.get("version")?.jsonPrimitive?.contentOrNull ?: "",
            protocolVersion = initResult?.get("protocolVersion")?.jsonPrimitive?.contentOrNull ?: ""
        )
    }

    override suspend fun listTools(): List<McpTool> {
        val result = sendRequest("tools/list", buildJsonObject {})
        val tools = result?.get("tools")?.jsonArray ?: return emptyList()
        return tools.map { toolJson ->
            val obj = toolJson.jsonObject
            McpTool(
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
                inputSchema = obj["inputSchema"]?.jsonObject
            )
        }
    }

    override fun disconnect() {
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { process?.destroyForcibly() } catch (_: Exception) {}
        process = null
        reader = null
        writer = null
        nextId = 1
    }

    private suspend fun sendRequest(method: String, params: JsonObject): JsonObject? =
        withContext(Dispatchers.IO) {
            val id = nextId++
            val msg = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }
            val w = writer ?: throw IllegalStateException("Not connected")
            val r = reader ?: throw IllegalStateException("Not connected")

            w.write(msg.toString())
            w.newLine()
            w.flush()

            // Read lines until we get the response matching our id
            while (true) {
                val line = r.readLine()
                    ?: throw RuntimeException("MCP server closed connection")
                if (line.isBlank()) continue

                val response = Json.parseToJsonElement(line).jsonObject

                // Check for error
                val error = response["error"]?.jsonObject
                if (error != null && response["id"]?.jsonPrimitive?.intOrNull == id) {
                    val errorMsg = error["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown MCP error"
                    throw RuntimeException("MCP error: $errorMsg")
                }

                // Match response by id
                if (response.containsKey("id") && response["id"]?.jsonPrimitive?.intOrNull == id) {
                    return@withContext response["result"]?.jsonObject
                }
                // Skip notifications and other responses
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

    private suspend fun sendNotification(method: String, params: JsonObject? = null) {
        withContext(Dispatchers.IO) {
            val w = writer ?: throw IllegalStateException("Not connected")
            val msg = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                if (params != null) put("params", params)
            }
            w.write(msg.toString())
            w.newLine()
            w.flush()
        }
    }

    companion object {
        /** Build a JSON-RPC request string (useful for testing). */
        fun buildJsonRpcRequest(id: Int, method: String, params: JsonObject): String =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }.toString()

        /** Parse a tools/list result JSON into McpTool list (useful for testing). */
        fun parseToolsList(resultJson: String): List<McpTool> {
            val obj = Json.parseToJsonElement(resultJson).jsonObject
            val result = obj["result"]?.jsonObject ?: obj
            val tools = result["tools"]?.jsonArray ?: return emptyList()
            return tools.map { toolJson ->
                val t = toolJson.jsonObject
                McpTool(
                    name = t["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    description = t["description"]?.jsonPrimitive?.contentOrNull ?: "",
                    inputSchema = t["inputSchema"]?.jsonObject
                )
            }
        }
    }
}

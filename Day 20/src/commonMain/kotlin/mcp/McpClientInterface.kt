package mcp

interface McpClientInterface {
    val isConnected: Boolean
    suspend fun connect(command: String, args: List<String> = emptyList(), env: Map<String, String> = emptyMap()): McpInitResult
    suspend fun listTools(): List<McpTool>
    suspend fun callTool(name: String, arguments: String): McpToolResult
    fun disconnect()
}

data class McpToolResult(
    val content: String,
    val isError: Boolean = false
)

data class McpInitResult(
    val serverName: String = "",
    val serverVersion: String = "",
    val protocolVersion: String = ""
)

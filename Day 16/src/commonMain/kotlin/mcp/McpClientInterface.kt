package mcp

interface McpClientInterface {
    val isConnected: Boolean
    suspend fun connect(command: String, args: List<String> = emptyList(), env: Map<String, String> = emptyMap()): McpInitResult
    suspend fun listTools(): List<McpTool>
    fun disconnect()
}

data class McpInitResult(
    val serverName: String = "",
    val serverVersion: String = "",
    val protocolVersion: String = ""
)

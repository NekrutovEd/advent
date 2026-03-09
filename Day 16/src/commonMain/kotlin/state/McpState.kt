package state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import mcp.McpClientInterface
import mcp.McpTool

class McpState(private val client: McpClientInterface) {
    var serverCommand by mutableStateOf("")
    var serverArgs by mutableStateOf("")
    var isConnected by mutableStateOf(false)
    var isConnecting by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var serverName by mutableStateOf("")
    val tools = mutableStateListOf<McpTool>()

    suspend fun connect() {
        if (serverCommand.isBlank()) {
            error = "Server command is required"
            return
        }
        isConnecting = true
        error = null
        tools.clear()
        serverName = ""

        try {
            val args = serverArgs.split(" ").filter { it.isNotBlank() }
            val initResult = client.connect(serverCommand, args)
            isConnected = true
            serverName = buildString {
                if (initResult.serverName.isNotBlank()) {
                    append(initResult.serverName)
                    if (initResult.serverVersion.isNotBlank()) append(" v${initResult.serverVersion}")
                }
            }

            val toolList = client.listTools()
            tools.addAll(toolList)
        } catch (e: Exception) {
            error = e.message ?: "Connection failed"
            isConnected = false
        } finally {
            isConnecting = false
        }
    }

    fun disconnect() {
        client.disconnect()
        isConnected = false
        tools.clear()
        error = null
        serverName = ""
    }
}

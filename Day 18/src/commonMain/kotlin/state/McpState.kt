package state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mcp.McpClientInterface
import mcp.McpTool

@Serializable
data class SchedulerNotification(
    val taskId: String = "",
    val taskType: String = "",
    val description: String = "",
    val output: String = "",
    val chatId: String = "",
    val sessionId: String = "",
    val isError: Boolean = false,
    val timestamp: Long = 0
)

class McpState(private val client: McpClientInterface) {
    var serverCommand by mutableStateOf("")
    var serverArgs by mutableStateOf("")
    var isConnected by mutableStateOf(false)
    var isConnecting by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var serverName by mutableStateOf("")
    val tools = mutableStateListOf<McpTool>()

    /** Parsed notifications from scheduler poll_notifications. */
    val notifications = mutableStateListOf<SchedulerNotification>()

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

    /** Poll the scheduler for new notifications. Returns true if there were any. */
    suspend fun pollNotifications(): Boolean {
        if (!isConnected) return false
        val hasPollTool = tools.any { it.name == "poll_notifications" }
        if (!hasPollTool) return false

        return try {
            val result = client.callTool("poll_notifications", "{}")
            if (result.content.isNotBlank() && !result.isError) {
                val parsed = notificationJson.decodeFromString<List<SchedulerNotification>>(result.content)
                notifications.addAll(parsed)
                parsed.isNotEmpty()
            } else false
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private val notificationJson = Json { ignoreUnknownKeys = true }
    }
}

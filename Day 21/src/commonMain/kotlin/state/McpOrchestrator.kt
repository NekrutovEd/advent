package state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json
import mcp.McpClientInterface
import mcp.McpTool
import mcp.McpToolResult

/**
 * One registered MCP server with its own connection and tools.
 */
class McpServerEntry(
    val id: String,
    private val client: McpClientInterface
) {
    var label by mutableStateOf("")
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

    /** Call a tool on this server. */
    suspend fun callTool(name: String, arguments: String): McpToolResult {
        return client.callTool(name, arguments)
    }

    /** Poll the scheduler for new notifications. */
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

/**
 * Orchestrator: manages multiple MCP servers, aggregates tools, routes calls.
 */
class McpOrchestrator(private val clientFactory: () -> McpClientInterface) {
    val servers = mutableStateListOf<McpServerEntry>()

    /** All tools from all connected servers. */
    val allTools: List<McpTool>
        get() = servers.filter { it.isConnected }.flatMap { it.tools }

    /** Total connected server count. */
    val connectedCount: Int
        get() = servers.count { it.isConnected }

    /** Aggregated notifications from all servers. */
    val allNotifications: List<SchedulerNotification>
        get() = servers.flatMap { it.notifications }

    /** Remove a consumed notification from whichever server owns it. */
    fun removeNotification(notification: SchedulerNotification) {
        servers.forEach { it.notifications.remove(notification) }
    }

    /** Add a new server entry and return it. */
    fun addServer(label: String = "", command: String = "", args: String = ""): McpServerEntry {
        val entry = McpServerEntry(
            id = generateId(),
            client = clientFactory()
        )
        entry.label = label
        entry.serverCommand = command
        entry.serverArgs = args
        servers.add(entry)
        return entry
    }

    /** Remove a server by id, disconnecting it first. */
    fun removeServer(id: String) {
        val entry = servers.firstOrNull { it.id == id } ?: return
        if (entry.isConnected) entry.disconnect()
        servers.remove(entry)
    }

    /** Connect all servers that have a command configured. */
    suspend fun connectAll() {
        servers.filter { !it.isConnected && it.serverCommand.isNotBlank() }.forEach {
            it.connect()
        }
    }

    /** Disconnect all servers. */
    fun disconnectAll() {
        servers.filter { it.isConnected }.forEach { it.disconnect() }
    }

    /** Route a tool call to the server that owns the tool. */
    suspend fun callTool(name: String, arguments: String): McpToolResult {
        val server = servers.firstOrNull { s -> s.isConnected && s.tools.any { it.name == name } }
            ?: return McpToolResult("Tool not found across any connected server: $name", isError = true)
        return server.callTool(name, arguments)
    }

    /** Find which server owns a given tool. */
    fun serverForTool(toolName: String): McpServerEntry? =
        servers.firstOrNull { s -> s.isConnected && s.tools.any { it.name == toolName } }

    /** Poll notifications from all connected servers. */
    suspend fun pollAllNotifications() {
        servers.filter { it.isConnected }.forEach { it.pollNotifications() }
    }

    /** Drain all notifications across all servers. */
    fun drainNotifications(): List<SchedulerNotification> {
        val all = mutableListOf<SchedulerNotification>()
        servers.forEach { server ->
            all.addAll(server.notifications)
            server.notifications.clear()
        }
        return all
    }

    private fun generateId(): String = kotlin.random.Random.Default.nextBytes(4).joinToString("") {
        val v = it.toInt() and 0xFF
        val h = v.toString(16)
        if (h.length == 1) "0$h" else h
    }
}

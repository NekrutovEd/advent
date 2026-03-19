package mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class McpTool(
    val name: String,
    val description: String = "",
    val inputSchema: JsonObject? = null
)

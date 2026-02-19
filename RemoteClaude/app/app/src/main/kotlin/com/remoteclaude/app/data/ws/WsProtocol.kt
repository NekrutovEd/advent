package com.remoteclaude.app.data.ws

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Shared WebSocket protocol — mirror of plugin/server/WsProtocol.kt
// Keep both files in sync when adding new message types.

val wsJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
sealed class WsMessage

// ─── Server → Client ──────────────────────────────────────────────────────────

@Serializable
@SerialName("init")
data class InitMessage(val tabs: List<TabInfo>) : WsMessage()

@Serializable
@SerialName("output")
data class OutputMessage(val tabId: Int, val data: String) : WsMessage()

@Serializable
@SerialName("tab_state")
data class TabStateMessage(
    val tabId: Int,
    val state: TabState,
    val message: String? = null,
) : WsMessage()

@Serializable
@SerialName("tab_added")
data class TabAddedMessage(val tab: TabInfo) : WsMessage()

@Serializable
@SerialName("tab_removed")
data class TabRemovedMessage(val tabId: Int) : WsMessage()

@Serializable
@SerialName("buffer")
data class BufferMessage(val tabId: Int, val data: String) : WsMessage()

@Serializable
@SerialName("projects_list")
data class ProjectsListMessage(val projects: List<ProjectInfo>) : WsMessage()

@Serializable
@SerialName("agent_launched")
data class AgentLaunchedMessage(
    val tabId: Int,
    val projectPath: String,
    val mode: AgentMode,
) : WsMessage()

@Serializable
@SerialName("agent_output")
data class AgentOutputMessage(
    val tabId: Int,
    val data: String,
    val isJson: Boolean = false,
) : WsMessage()

@Serializable
@SerialName("agent_completed")
data class AgentCompletedMessage(
    val tabId: Int,
    val exitCode: Int,
) : WsMessage()

@Serializable
@SerialName("error")
data class ErrorMessage(val message: String) : WsMessage()

// ─── Client → Server ──────────────────────────────────────────────────────────

@Serializable
@SerialName("input")
data class InputMessage(val tabId: Int, val data: String) : WsMessage()

@Serializable
@SerialName("request_buffer")
data class RequestBufferMessage(val tabId: Int) : WsMessage()

@Serializable
@SerialName("list_projects")
class ListProjectsMessage : WsMessage()

@Serializable
@SerialName("launch_agent")
data class LaunchAgentMessage(
    val projectPath: String,
    val mode: AgentMode,
    val prompt: String,
    val allowedTools: List<String> = emptyList(),
) : WsMessage()

@Serializable
@SerialName("terminate_agent")
data class TerminateAgentMessage(val tabId: Int) : WsMessage()

@Serializable
@SerialName("add_project")
data class AddProjectMessage(val path: String) : WsMessage()

// ─── Shared data classes ──────────────────────────────────────────────────────

@Serializable
data class TabInfo(
    val id: Int,
    val title: String,
    val state: TabState,
    val projectPath: String? = null,
)

@Serializable
data class ProjectInfo(
    val path: String,
    val name: String,
    val hasGit: Boolean = false,
)

@Serializable
enum class TabState {
    STARTING,
    RUNNING,
    WAITING_INPUT,
    WAITING_TOOL,
    COMPLETED,
    FAILED,
}

@Serializable
enum class AgentMode {
    INTERACTIVE,
    BATCH,
}

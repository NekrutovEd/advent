package com.remoteclaude.app.data.ws

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// WebSocket protocol — mirror of server's App-facing messages.
// Tab IDs are global Strings (format: "pluginId:localTabId").

val wsJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
sealed class WsMessage

// ─── Server → App ────────────────────────────────────────────────────────────

@Serializable
@SerialName("init")
data class InitMessage(
    val tabs: List<TabInfo>,
    val plugins: List<PluginInfo> = emptyList(),
) : WsMessage()

@Serializable
@SerialName("output")
data class OutputMessage(val tabId: String, val data: String) : WsMessage()

@Serializable
@SerialName("tab_state")
data class TabStateMessage(
    val tabId: String,
    val state: TabState,
    val message: String? = null,
) : WsMessage()

@Serializable
@SerialName("tab_added")
data class TabAddedMessage(val tab: TabInfo) : WsMessage()

@Serializable
@SerialName("tab_removed")
data class TabRemovedMessage(val tabId: String) : WsMessage()

@Serializable
@SerialName("buffer")
data class BufferMessage(val tabId: String, val data: String) : WsMessage()

@Serializable
@SerialName("projects_list")
data class ProjectsListMessage(val projects: List<ProjectInfo>) : WsMessage()

@Serializable
@SerialName("agent_launched")
data class AgentLaunchedMessage(
    val tabId: String,
    val projectPath: String,
    val mode: AgentMode,
) : WsMessage()

@Serializable
@SerialName("agent_output")
data class AgentOutputMessage(
    val tabId: String,
    val data: String,
    val isJson: Boolean = false,
) : WsMessage()

@Serializable
@SerialName("agent_completed")
data class AgentCompletedMessage(
    val tabId: String,
    val exitCode: Int,
) : WsMessage()

@Serializable
@SerialName("error")
data class ErrorMessage(val message: String) : WsMessage()

// ─── App → Server ────────────────────────────────────────────────────────────

/** Register this app with the server */
@Serializable
@SerialName("register_app")
data class RegisterAppMessage(
    val deviceName: String,
    val platform: String,
) : WsMessage()

@Serializable
@SerialName("input")
data class InputMessage(val tabId: String, val data: String) : WsMessage()

@Serializable
@SerialName("request_buffer")
data class RequestBufferMessage(val tabId: String) : WsMessage()

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
data class TerminateAgentMessage(val tabId: String) : WsMessage()

@Serializable
@SerialName("add_project")
data class AddProjectMessage(val path: String) : WsMessage()

@Serializable
@SerialName("close_tab")
data class CloseTabMessage(val tabId: String) : WsMessage()

@Serializable
@SerialName("create_terminal")
data class CreateTerminalMessage(val projectPath: String) : WsMessage()

@Serializable
@SerialName("create_server_terminal")
data class CreateServerTerminalMessage(val workingDir: String? = null) : WsMessage()

@Serializable
@SerialName("launch_ide")
data class LaunchIdeMessage(val projectPath: String) : WsMessage()

// ─── Shared data classes ─────────────────────────────────────────────────────

@Serializable
data class TabInfo(
    val id: String,
    val title: String,
    val state: TabState,
    val pluginId: String = "",
    val pluginName: String = "",
    val projectPath: String? = null,
)

@Serializable
data class PluginInfo(
    val pluginId: String,
    val ideName: String,
    val projectName: String,
    val hostname: String,
    val tabCount: Int = 0,
    val connected: Boolean = true,
    val projectPath: String = "",
    val ideHomePath: String = "",
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

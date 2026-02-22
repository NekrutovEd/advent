package com.remoteclaude.server.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val wsJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
sealed class WsMessage

// ─── Plugin → Server ─────────────────────────────────────────────────────────

/** Plugin registers itself on connect */
@Serializable
@SerialName("register_plugin")
data class RegisterPluginMessage(
    val pluginId: String,
    val ideName: String,
    val projectName: String,
    val hostname: String,
    val projectPath: String = "",
    val ideHomePath: String = "",
) : WsMessage()

/** Plugin sends its full tab list */
@Serializable
@SerialName("plugin_tabs")
data class PluginTabsMessage(
    val pluginId: String,
    val tabs: List<LocalTabInfo>,
) : WsMessage()

/** Plugin sends terminal output */
@Serializable
@SerialName("plugin_output")
data class PluginOutputMessage(
    val pluginId: String,
    val localTabId: Int,
    val data: String,
) : WsMessage()

/** Plugin reports tab state change */
@Serializable
@SerialName("plugin_tab_state")
data class PluginTabStateMessage(
    val pluginId: String,
    val localTabId: Int,
    val state: TabState,
    val message: String? = null,
) : WsMessage()

/** Plugin reports new tab opened */
@Serializable
@SerialName("plugin_tab_added")
data class PluginTabAddedMessage(
    val pluginId: String,
    val tab: LocalTabInfo,
) : WsMessage()

/** Plugin reports tab closed */
@Serializable
@SerialName("plugin_tab_removed")
data class PluginTabRemovedMessage(
    val pluginId: String,
    val localTabId: Int,
) : WsMessage()

/** Plugin sends scrollback buffer response */
@Serializable
@SerialName("plugin_buffer")
data class PluginBufferMessage(
    val pluginId: String,
    val localTabId: Int,
    val data: String,
) : WsMessage()

/** Plugin sends projects list */
@Serializable
@SerialName("plugin_projects_list")
data class PluginProjectsListMessage(
    val pluginId: String,
    val projects: List<ProjectInfo>,
) : WsMessage()

/** Plugin confirms agent launched */
@Serializable
@SerialName("plugin_agent_launched")
data class PluginAgentLaunchedMessage(
    val pluginId: String,
    val localTabId: Int,
    val projectPath: String,
    val mode: AgentMode,
) : WsMessage()

/** Plugin sends agent output */
@Serializable
@SerialName("plugin_agent_output")
data class PluginAgentOutputMessage(
    val pluginId: String,
    val localTabId: Int,
    val data: String,
    val isJson: Boolean = false,
) : WsMessage()

/** Plugin reports agent completed */
@Serializable
@SerialName("plugin_agent_completed")
data class PluginAgentCompletedMessage(
    val pluginId: String,
    val localTabId: Int,
    val exitCode: Int,
) : WsMessage()

// ─── Server → Plugin ─────────────────────────────────────────────────────────

/** Server confirms plugin registration */
@Serializable
@SerialName("plugin_registered")
data class PluginRegisteredMessage(
    val pluginId: String,
) : WsMessage()

/** Server forwards input from app to plugin */
@Serializable
@SerialName("forward_input")
data class ForwardInputMessage(
    val localTabId: Int,
    val data: String,
) : WsMessage()

/** Server asks plugin for scrollback buffer */
@Serializable
@SerialName("forward_request_buffer")
data class ForwardRequestBufferMessage(
    val localTabId: Int,
) : WsMessage()

/** Server asks plugin to launch agent */
@Serializable
@SerialName("forward_launch_agent")
data class ForwardLaunchAgentMessage(
    val projectPath: String,
    val mode: AgentMode,
    val prompt: String,
    val allowedTools: List<String> = emptyList(),
) : WsMessage()

/** Server asks plugin to close a tab */
@Serializable
@SerialName("forward_close_tab")
data class ForwardCloseTabMessage(
    val localTabId: Int,
) : WsMessage()

/** Server asks plugin to create a terminal */
@Serializable
@SerialName("forward_create_terminal")
data class ForwardCreateTerminalMessage(
    val projectPath: String,
) : WsMessage()

/** Server asks plugin to terminate an agent */
@Serializable
@SerialName("forward_terminate_agent")
data class ForwardTerminateAgentMessage(
    val localTabId: Int,
) : WsMessage()

/** Server asks plugin to list projects */
@Serializable
@SerialName("forward_list_projects")
class ForwardListProjectsMessage : WsMessage()

/** Server asks plugin to add a project */
@Serializable
@SerialName("forward_add_project")
data class ForwardAddProjectMessage(
    val path: String,
) : WsMessage()

// ─── App → Server ────────────────────────────────────────────────────────────

/** App registers itself on connect */
@Serializable
@SerialName("register_app")
data class RegisterAppMessage(
    val deviceName: String,
    val platform: String,
) : WsMessage()

/** App sends input to a global tab */
@Serializable
@SerialName("input")
data class InputMessage(val tabId: String, val data: String) : WsMessage()

/** App requests scrollback buffer */
@Serializable
@SerialName("request_buffer")
data class RequestBufferMessage(val tabId: String) : WsMessage()

/** App requests project list */
@Serializable
@SerialName("list_projects")
class ListProjectsMessage : WsMessage()

/** App requests agent launch */
@Serializable
@SerialName("launch_agent")
data class LaunchAgentMessage(
    val projectPath: String,
    val mode: AgentMode,
    val prompt: String,
    val allowedTools: List<String> = emptyList(),
) : WsMessage()

/** App requests agent termination */
@Serializable
@SerialName("terminate_agent")
data class TerminateAgentMessage(val tabId: String) : WsMessage()

/** App adds a project */
@Serializable
@SerialName("add_project")
data class AddProjectMessage(val path: String) : WsMessage()

/** App closes a tab */
@Serializable
@SerialName("close_tab")
data class CloseTabMessage(val tabId: String) : WsMessage()

/** App creates a terminal */
@Serializable
@SerialName("create_terminal")
data class CreateTerminalMessage(val projectPath: String) : WsMessage()

/** App creates a server-side terminal (no plugin needed) */
@Serializable
@SerialName("create_server_terminal")
data class CreateServerTerminalMessage(val workingDir: String? = null) : WsMessage()

/** App requests IDE launch for an offline plugin's project */
@Serializable
@SerialName("launch_ide")
data class LaunchIdeMessage(val projectPath: String) : WsMessage()

// ─── Server → App ────────────────────────────────────────────────────────────

/** Sent on connect: full list of current tabs and plugins */
@Serializable
@SerialName("init")
data class InitMessage(
    val tabs: List<GlobalTabInfo>,
    val plugins: List<PluginInfo> = emptyList(),
) : WsMessage()

/** Terminal output (global tab ID) */
@Serializable
@SerialName("output")
data class OutputMessage(val tabId: String, val data: String) : WsMessage()

/** Tab state changed */
@Serializable
@SerialName("tab_state")
data class TabStateMessage(
    val tabId: String,
    val state: TabState,
    val message: String? = null,
) : WsMessage()

/** New tab opened */
@Serializable
@SerialName("tab_added")
data class TabAddedMessage(val tab: GlobalTabInfo) : WsMessage()

/** Tab closed */
@Serializable
@SerialName("tab_removed")
data class TabRemovedMessage(val tabId: String) : WsMessage()

/** Scrollback buffer */
@Serializable
@SerialName("buffer")
data class BufferMessage(val tabId: String, val data: String) : WsMessage()

/** Projects list */
@Serializable
@SerialName("projects_list")
data class ProjectsListMessage(val projects: List<ProjectInfo>) : WsMessage()

/** Agent launched */
@Serializable
@SerialName("agent_launched")
data class AgentLaunchedMessage(
    val tabId: String,
    val projectPath: String,
    val mode: AgentMode,
) : WsMessage()

/** Agent output */
@Serializable
@SerialName("agent_output")
data class AgentOutputMessage(
    val tabId: String,
    val data: String,
    val isJson: Boolean = false,
) : WsMessage()

/** Agent completed */
@Serializable
@SerialName("agent_completed")
data class AgentCompletedMessage(
    val tabId: String,
    val exitCode: Int,
) : WsMessage()

/** Error */
@Serializable
@SerialName("error")
data class ErrorMessage(val message: String) : WsMessage()

// ─── Shared data classes ─────────────────────────────────────────────────────

/** Tab info as reported by a plugin (local IDs) */
@Serializable
data class LocalTabInfo(
    val id: Int,
    val title: String,
    val state: TabState,
    val projectPath: String? = null,
)

/** Tab info with global ID (pluginId:localTabId) */
@Serializable
data class GlobalTabInfo(
    val id: String,
    val title: String,
    val state: TabState,
    val pluginId: String,
    val pluginName: String,
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

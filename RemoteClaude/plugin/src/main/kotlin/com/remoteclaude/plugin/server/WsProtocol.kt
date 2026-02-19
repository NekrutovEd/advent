package com.remoteclaude.plugin.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// WebSocket protocol for plugin <-> central server communication.
// Plugin sends Plugin* messages to server, receives Forward* messages back.

val wsJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
sealed class WsMessage

// ─── Plugin → Server ─────────────────────────────────────────────────────────

/** Register this plugin with the server */
@Serializable
@SerialName("register_plugin")
data class RegisterPluginMessage(
    val pluginId: String,
    val ideName: String,
    val projectName: String,
    val hostname: String,
) : WsMessage()

/** Send full tab list to server */
@Serializable
@SerialName("plugin_tabs")
data class PluginTabsMessage(
    val pluginId: String,
    val tabs: List<TabInfo>,
) : WsMessage()

/** Send terminal output to server */
@Serializable
@SerialName("plugin_output")
data class PluginOutputMessage(
    val pluginId: String,
    val localTabId: Int,
    val data: String,
) : WsMessage()

/** Report tab state change to server */
@Serializable
@SerialName("plugin_tab_state")
data class PluginTabStateMessage(
    val pluginId: String,
    val localTabId: Int,
    val state: TabState,
    val message: String? = null,
) : WsMessage()

/** Report new tab opened */
@Serializable
@SerialName("plugin_tab_added")
data class PluginTabAddedMessage(
    val pluginId: String,
    val tab: TabInfo,
) : WsMessage()

/** Report tab closed */
@Serializable
@SerialName("plugin_tab_removed")
data class PluginTabRemovedMessage(
    val pluginId: String,
    val localTabId: Int,
) : WsMessage()

/** Send scrollback buffer response */
@Serializable
@SerialName("plugin_buffer")
data class PluginBufferMessage(
    val pluginId: String,
    val localTabId: Int,
    val data: String,
) : WsMessage()

/** Send projects list */
@Serializable
@SerialName("plugin_projects_list")
data class PluginProjectsListMessage(
    val pluginId: String,
    val projects: List<ProjectInfo>,
) : WsMessage()

/** Confirm agent launched */
@Serializable
@SerialName("plugin_agent_launched")
data class PluginAgentLaunchedMessage(
    val pluginId: String,
    val localTabId: Int,
    val projectPath: String,
    val mode: AgentMode,
) : WsMessage()

/** Send agent output */
@Serializable
@SerialName("plugin_agent_output")
data class PluginAgentOutputMessage(
    val pluginId: String,
    val localTabId: Int,
    val data: String,
    val isJson: Boolean = false,
) : WsMessage()

/** Report agent completed */
@Serializable
@SerialName("plugin_agent_completed")
data class PluginAgentCompletedMessage(
    val pluginId: String,
    val localTabId: Int,
    val exitCode: Int,
) : WsMessage()

// ─── Server → Plugin (Forwarded commands) ────────────────────────────────────

/** Server confirms registration */
@Serializable
@SerialName("plugin_registered")
data class PluginRegisteredMessage(
    val pluginId: String,
) : WsMessage()

/** Server forwards input from app */
@Serializable
@SerialName("forward_input")
data class ForwardInputMessage(
    val localTabId: Int,
    val data: String,
) : WsMessage()

/** Server requests scrollback buffer */
@Serializable
@SerialName("forward_request_buffer")
data class ForwardRequestBufferMessage(
    val localTabId: Int,
) : WsMessage()

/** Server requests agent launch */
@Serializable
@SerialName("forward_launch_agent")
data class ForwardLaunchAgentMessage(
    val projectPath: String,
    val mode: AgentMode,
    val prompt: String,
    val allowedTools: List<String> = emptyList(),
) : WsMessage()

/** Server requests tab close */
@Serializable
@SerialName("forward_close_tab")
data class ForwardCloseTabMessage(
    val localTabId: Int,
) : WsMessage()

/** Server requests terminal creation */
@Serializable
@SerialName("forward_create_terminal")
data class ForwardCreateTerminalMessage(
    val projectPath: String,
) : WsMessage()

/** Server requests agent termination */
@Serializable
@SerialName("forward_terminate_agent")
data class ForwardTerminateAgentMessage(
    val localTabId: Int,
) : WsMessage()

/** Server requests projects list */
@Serializable
@SerialName("forward_list_projects")
class ForwardListProjectsMessage : WsMessage()

/** Server requests adding a project */
@Serializable
@SerialName("forward_add_project")
data class ForwardAddProjectMessage(
    val path: String,
) : WsMessage()

// ─── Shared data classes ─────────────────────────────────────────────────────

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

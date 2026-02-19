package com.remoteclaude.plugin.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Shared WebSocket protocol between plugin (server) and Android app (client).
// Both sides use identical message types serialized as JSON with a "type" discriminator.

val wsJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
sealed class WsMessage

// ─── Server → Client ──────────────────────────────────────────────────────────

/** Sent on connect: full list of current terminal tabs */
@Serializable
@SerialName("init")
data class InitMessage(val tabs: List<TabInfo>) : WsMessage()

/** Realtime terminal output chunk (ANSI-encoded) */
@Serializable
@SerialName("output")
data class OutputMessage(val tabId: Int, val data: String) : WsMessage()

/** Tab state changed (e.g. started waiting for input) */
@Serializable
@SerialName("tab_state")
data class TabStateMessage(
    val tabId: Int,
    val state: TabState,
    val message: String? = null,
) : WsMessage()

/** A new terminal tab was opened */
@Serializable
@SerialName("tab_added")
data class TabAddedMessage(val tab: TabInfo) : WsMessage()

/** A terminal tab was closed */
@Serializable
@SerialName("tab_removed")
data class TabRemovedMessage(val tabId: Int) : WsMessage()

/** Scrollback buffer response for a tab */
@Serializable
@SerialName("buffer")
data class BufferMessage(val tabId: Int, val data: String) : WsMessage()

/** Response to list_projects request */
@Serializable
@SerialName("projects_list")
data class ProjectsListMessage(val projects: List<ProjectInfo>) : WsMessage()

/** Confirms an agent was launched */
@Serializable
@SerialName("agent_launched")
data class AgentLaunchedMessage(
    val tabId: Int,
    val projectPath: String,
    val mode: AgentMode,
) : WsMessage()

/** Output chunk from a batch agent */
@Serializable
@SerialName("agent_output")
data class AgentOutputMessage(
    val tabId: Int,
    val data: String,
    val isJson: Boolean = false,
) : WsMessage()

/** Batch agent finished */
@Serializable
@SerialName("agent_completed")
data class AgentCompletedMessage(
    val tabId: Int,
    val exitCode: Int,
) : WsMessage()

/** Error notification */
@Serializable
@SerialName("error")
data class ErrorMessage(val message: String) : WsMessage()

// ─── Client → Server ──────────────────────────────────────────────────────────

/** Send text input to a terminal tab */
@Serializable
@SerialName("input")
data class InputMessage(val tabId: Int, val data: String) : WsMessage()

/** Request scrollback buffer for a tab */
@Serializable
@SerialName("request_buffer")
data class RequestBufferMessage(val tabId: Int) : WsMessage()

/** Request list of configured projects */
@Serializable
@SerialName("list_projects")
class ListProjectsMessage : WsMessage()

/** Launch a new Claude Code agent */
@Serializable
@SerialName("launch_agent")
data class LaunchAgentMessage(
    val projectPath: String,
    val mode: AgentMode,
    val prompt: String,
    val allowedTools: List<String> = emptyList(),
) : WsMessage()

/** Terminate a running agent */
@Serializable
@SerialName("terminate_agent")
data class TerminateAgentMessage(val tabId: Int) : WsMessage()

/** Add a project path to the registry */
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

package com.remoteclaude.server.net

import com.remoteclaude.server.config.KnownPlugin
import com.remoteclaude.server.protocol.*
import com.remoteclaude.server.state.*
import com.remoteclaude.server.terminal.StandaloneTerminalManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File

class MessageRouter(
    val pluginRegistry: PluginRegistry,
    val appRegistry: AppRegistry,
    val tabRegistry: GlobalTabRegistry,
    val knownPluginRegistry: KnownPluginRegistry,
) {
    var standaloneManager: StandaloneTerminalManager? = null
    var hub: WebSocketHub? = null
    private val log = LoggerFactory.getLogger(MessageRouter::class.java)
    private val launchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Active sessions
    private val pluginSessions = mutableMapOf<String, PluginSession>()   // pluginId -> session
    private val appSessions = mutableMapOf<String, AppSession>()         // sessionId -> session

    // Events for UI
    data class LogEntry(val timestamp: Long, val message: String)
    private val _logs = mutableListOf<LogEntry>()
    val logs: List<LogEntry> get() = synchronized(_logs) { _logs.toList() }

    private fun addLog(message: String) {
        synchronized(_logs) {
            _logs.add(LogEntry(System.currentTimeMillis(), message))
            if (_logs.size > 500) _logs.removeFirst()
        }
    }

    // ── Plugin session management ─────────────────────────────────────────

    fun addPluginSession(pluginId: String, session: PluginSession) {
        pluginSessions[pluginId] = session
    }

    fun removePluginSession(pluginId: String) {
        pluginSessions.remove(pluginId)
    }

    fun getPluginSession(pluginId: String): PluginSession? = pluginSessions[pluginId]

    // ── App session management ────────────────────────────────────────────

    fun addAppSession(sessionId: String, session: AppSession) {
        appSessions[sessionId] = session
    }

    fun removeAppSession(sessionId: String) {
        appSessions.remove(sessionId)
    }

    // ── Handle messages from plugins ──────────────────────────────────────

    suspend fun handlePluginMessage(pluginSession: PluginSession, message: WsMessage) {
        val pluginId = pluginSession.pluginId ?: return

        when (message) {
            is RegisterPluginMessage -> {
                // Already handled in WebSocketHub before routing
            }
            is PluginTabsMessage -> {
                val entry = pluginRegistry.get(pluginId) ?: return
                val pluginName = "${entry.ideName} - ${entry.projectName}"
                for (localTab in message.tabs) {
                    tabRegistry.registerTab(pluginId, pluginName, localTab)
                }
                addLog("Plugin $pluginId registered ${message.tabs.size} tabs")
                broadcastToApps(InitMessage(
                    tabs = tabRegistry.getAllTabs(),
                    plugins = hub?.buildMergedPluginList() ?: pluginRegistry.toPluginInfoList(tabRegistry),
                ))
            }
            is PluginOutputMessage -> {
                val globalId = TabNamespace.toGlobal(pluginId, message.localTabId)
                tabRegistry.getBuffer(globalId)?.append(message.data)
                tabRegistry.emitOutput(globalId, message.data)
                broadcastToApps(OutputMessage(globalId, message.data))
            }
            is PluginTabStateMessage -> {
                val globalId = TabNamespace.toGlobal(pluginId, message.localTabId)
                tabRegistry.updateTabState(globalId, message.state, message.message)
                broadcastToApps(TabStateMessage(globalId, message.state, message.message))
            }
            is PluginTabAddedMessage -> {
                val entry = pluginRegistry.get(pluginId) ?: return
                val pluginName = "${entry.ideName} - ${entry.projectName}"
                val globalTab = tabRegistry.registerTab(pluginId, pluginName, message.tab)
                addLog("Tab added: ${globalTab.id} (${globalTab.title})")
                broadcastToApps(TabAddedMessage(globalTab))
            }
            is PluginTabRemovedMessage -> {
                val globalId = TabNamespace.toGlobal(pluginId, message.localTabId)
                tabRegistry.removeTab(globalId)
                addLog("Tab removed: $globalId")
                broadcastToApps(TabRemovedMessage(globalId))
            }
            is PluginBufferMessage -> {
                val globalId = TabNamespace.toGlobal(pluginId, message.localTabId)
                broadcastToApps(BufferMessage(globalId, message.data))
            }
            is PluginProjectsListMessage -> {
                broadcastToApps(ProjectsListMessage(message.projects))
            }
            is PluginAgentLaunchedMessage -> {
                val globalId = TabNamespace.toGlobal(pluginId, message.localTabId)
                broadcastToApps(AgentLaunchedMessage(globalId, message.projectPath, message.mode))
            }
            is PluginAgentOutputMessage -> {
                val globalId = TabNamespace.toGlobal(pluginId, message.localTabId)
                tabRegistry.getBuffer(globalId)?.append(message.data)
                tabRegistry.emitOutput(globalId, message.data)
                broadcastToApps(AgentOutputMessage(globalId, message.data, message.isJson))
            }
            is PluginAgentCompletedMessage -> {
                val globalId = TabNamespace.toGlobal(pluginId, message.localTabId)
                broadcastToApps(AgentCompletedMessage(globalId, message.exitCode))
            }
            else -> {
                log.debug("Unhandled plugin message type: ${message::class.simpleName}")
            }
        }
    }

    // ── Handle messages from apps ─────────────────────────────────────────

    suspend fun handleAppMessage(appSession: AppSession, message: WsMessage) {
        when (message) {
            is RegisterAppMessage -> {
                // Already handled in WebSocketHub before routing
            }
            is InputMessage -> {
                val (pluginId, localTabId) = TabNamespace.fromGlobal(message.tabId)
                if (standaloneManager?.isServerTerminal(pluginId) == true) {
                    standaloneManager?.sendInput(localTabId, message.data)
                } else {
                    val session = pluginSessions[pluginId]
                    if (session != null) {
                        session.send(ForwardInputMessage(localTabId, message.data))
                    } else {
                        appSession.send(ErrorMessage("Plugin $pluginId not connected"))
                    }
                }
            }
            is RequestBufferMessage -> {
                // First check local buffer
                val buffer = tabRegistry.getBuffer(message.tabId)?.getSnapshot()
                if (buffer != null && buffer.isNotEmpty()) {
                    appSession.send(BufferMessage(message.tabId, buffer))
                } else {
                    val (pluginId, localTabId) = TabNamespace.fromGlobal(message.tabId)
                    if (standaloneManager?.isServerTerminal(pluginId) != true) {
                        pluginSessions[pluginId]?.send(ForwardRequestBufferMessage(localTabId))
                    }
                }
            }
            is ListProjectsMessage -> {
                // Forward to all plugins, aggregate responses
                for ((_, session) in pluginSessions) {
                    session.send(ForwardListProjectsMessage())
                }
                // Also provide projects from known offline plugins
                val connectedPaths = pluginRegistry.getAll()
                    .mapNotNull { it.projectPath.ifEmpty { null } }.toSet()
                val offlineProjects = knownPluginRegistry.getAll()
                    .filter { it.projectPath !in connectedPaths && it.projectPath.isNotEmpty() }
                    .map { ProjectInfo(path = it.projectPath, name = it.projectName) }
                if (offlineProjects.isNotEmpty()) {
                    appSession.send(ProjectsListMessage(offlineProjects))
                }
            }
            is LaunchAgentMessage -> {
                // Route to first plugin (or could route based on projectPath)
                val targetPlugin = findPluginForProject(message.projectPath)
                if (targetPlugin != null) {
                    targetPlugin.send(ForwardLaunchAgentMessage(
                        message.projectPath, message.mode, message.prompt, message.allowedTools
                    ))
                } else {
                    appSession.send(ErrorMessage("No plugin available for project: ${message.projectPath}"))
                }
            }
            is TerminateAgentMessage -> {
                val (pluginId, localTabId) = TabNamespace.fromGlobal(message.tabId)
                pluginSessions[pluginId]?.send(ForwardTerminateAgentMessage(localTabId))
            }
            is AddProjectMessage -> {
                for ((_, session) in pluginSessions) {
                    session.send(ForwardAddProjectMessage(message.path))
                }
            }
            is CloseTabMessage -> {
                val (pluginId, localTabId) = TabNamespace.fromGlobal(message.tabId)
                if (standaloneManager?.isServerTerminal(pluginId) == true) {
                    standaloneManager?.close(localTabId)
                } else {
                    pluginSessions[pluginId]?.send(ForwardCloseTabMessage(localTabId))
                }
            }
            is CreateServerTerminalMessage -> {
                val mgr = standaloneManager
                if (mgr != null) {
                    mgr.create(message.workingDir)
                } else {
                    appSession.send(ErrorMessage("Server terminals not available"))
                }
            }
            is CreateTerminalMessage -> {
                val targetPlugin = findPluginForProject(message.projectPath)
                if (targetPlugin != null) {
                    targetPlugin.send(ForwardCreateTerminalMessage(message.projectPath))
                } else {
                    // Send to first plugin if no match
                    pluginSessions.values.firstOrNull()?.send(ForwardCreateTerminalMessage(message.projectPath))
                        ?: appSession.send(ErrorMessage("No plugins connected"))
                }
            }
            is LaunchIdeMessage -> {
                val known = knownPluginRegistry.get(message.projectPath)
                if (known != null && known.ideHomePath.isNotEmpty()) {
                    launchScope.launch { launchIde(known) }
                    log.info("Launching IDE for project: ${message.projectPath}")
                } else {
                    appSession.send(ErrorMessage("IDE path not available for this project"))
                }
            }
            else -> {
                log.debug("Unhandled app message type: ${message::class.simpleName}")
            }
        }
    }

    // ── Broadcasting ──────────────────────────────────────────────────────

    suspend fun broadcastToApps(message: WsMessage) {
        for ((_, session) in appSessions) {
            session.send(message)
        }
    }

    // ── Server-side input (for local terminal windows) ──────────────────

    suspend fun sendInputToTab(globalTabId: String, data: String): Boolean {
        val (pluginId, localTabId) = TabNamespace.fromGlobal(globalTabId)
        if (standaloneManager?.isServerTerminal(pluginId) == true) {
            standaloneManager?.sendInput(localTabId, data)
            return true
        }
        val session = pluginSessions[pluginId] ?: return false
        session.send(ForwardInputMessage(localTabId, data))
        return true
    }

    suspend fun createTerminalForPlugin(pluginId: String): Boolean {
        val session = pluginSessions[pluginId] ?: return false
        val entry = pluginRegistry.get(pluginId) ?: return false
        session.send(ForwardCreateTerminalMessage(entry.projectName))
        return true
    }

    suspend fun closeTab(globalTabId: String): Boolean {
        val (pluginId, localTabId) = TabNamespace.fromGlobal(globalTabId)
        if (standaloneManager?.isServerTerminal(pluginId) == true) {
            standaloneManager?.close(localTabId)
            return true
        }
        val session = pluginSessions[pluginId] ?: return false
        session.send(ForwardCloseTabMessage(localTabId))
        return true
    }

    suspend fun createServerTerminal(workingDir: String? = null): String? {
        return standaloneManager?.create(workingDir)
    }

    // ── Utility ───────────────────────────────────────────────────────────

    private fun findPluginForProject(projectPath: String): PluginSession? {
        // Try to find a plugin whose project matches
        for ((pluginId, entry) in pluginRegistry.getAll().associateBy { it.pluginId }) {
            if (entry.projectName.isNotEmpty()) {
                // Simple heuristic: check if projectPath contains the project name
                if (projectPath.contains(entry.projectName) || entry.projectName.contains(projectPath.substringAfterLast("/"))) {
                    return pluginSessions[pluginId]
                }
            }
        }
        // Fallback: first available plugin
        return pluginSessions.values.firstOrNull()
    }

    private fun launchIde(plugin: KnownPlugin) {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val binNames = if (isWindows)
            listOf("bin/studio64.exe", "bin/idea64.exe", "bin/pycharm64.exe")
        else
            listOf("bin/studio.sh", "bin/idea.sh", "bin/pycharm.sh")
        val exe = binNames.map { File(plugin.ideHomePath, it) }.firstOrNull { it.exists() }
        if (exe != null) {
            log.info("Launching IDE: ${exe.absolutePath} ${plugin.projectPath}")
            ProcessBuilder(exe.absolutePath, plugin.projectPath).start()
        } else {
            log.warn("No IDE executable found in ${plugin.ideHomePath}")
        }
    }
}

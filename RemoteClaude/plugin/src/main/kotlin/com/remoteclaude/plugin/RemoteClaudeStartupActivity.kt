package com.remoteclaude.plugin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Key
import com.remoteclaude.plugin.server.*
import com.remoteclaude.plugin.terminal.TerminalTabsWatcher

class RemoteClaudeStartupActivity : ProjectActivity {

    private val LOG = Logger.getInstance(RemoteClaudeStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        LOG.info("RemoteClaude: starting plugin for project ${project.name}")

        // Start local WS server (for Claude Code /api/notify hook only)
        val server = WsServer.getInstance(project)
        server.start()

        // Start WS client to connect to central server
        val pluginClient = WsPluginClient.getInstance(project)

        // Set up message handling for forwarded commands from server
        pluginClient.onMessage = { message ->
            handleServerMessage(project, message, pluginClient, server)
        }

        pluginClient.start()

        // Start terminal watcher (sends output via pluginClient)
        val watcher = TerminalTabsWatcher.getInstance(project)
        watcher.start(pluginClient)

        // Store references for cleanup
        project.putUserData(WATCHER_KEY, watcher)
        project.putUserData(CLIENT_KEY, pluginClient)
    }

    private suspend fun handleServerMessage(
        project: Project,
        message: WsMessage,
        pluginClient: WsPluginClient,
        server: WsServer,
    ) {
        when (message) {
            is PluginRegisteredMessage -> {
                LOG.info("RemoteClaude: registered with server as ${message.pluginId}")
                // Send current tabs to server
                val tabs = server.registry.getAllTabs()
                pluginClient.send(PluginTabsMessage(pluginClient.pluginId, tabs))
            }
            is ForwardInputMessage -> {
                LOG.info("RemoteClaude: forwarded input for tab ${message.localTabId}")
                TerminalTabsWatcher.getInstance(project).sendInput(message.localTabId, message.data)
            }
            is ForwardRequestBufferMessage -> {
                val buf = server.registry.getBuffer(message.localTabId)?.getSnapshot() ?: ""
                pluginClient.send(PluginBufferMessage(pluginClient.pluginId, message.localTabId, buf))
            }
            is ForwardLaunchAgentMessage -> {
                com.remoteclaude.plugin.orchestration.AgentLauncher.getInstance(project)
                    .launch(message, pluginClient, server.registry)
            }
            is ForwardTerminateAgentMessage -> {
                com.remoteclaude.plugin.orchestration.AgentLauncher.getInstance(project)
                    .terminate(message.localTabId)
            }
            is ForwardListProjectsMessage -> {
                val projects = com.remoteclaude.plugin.orchestration.ProjectRegistry.getInstance().allProjects()
                pluginClient.send(PluginProjectsListMessage(pluginClient.pluginId, projects))
            }
            is ForwardAddProjectMessage -> {
                com.remoteclaude.plugin.orchestration.ProjectRegistry.getInstance().addProject(message.path)
            }
            is ForwardCloseTabMessage -> {
                TerminalTabsWatcher.getInstance(project).closeTab(message.localTabId)
            }
            is ForwardCreateTerminalMessage -> {
                TerminalTabsWatcher.getInstance(project).createTerminal(message.projectPath, server)
            }
            else -> {
                LOG.info("RemoteClaude: unhandled server message: ${message::class.simpleName}")
            }
        }
    }

    companion object {
        val WATCHER_KEY: Key<TerminalTabsWatcher> =
            Key.create("remoteclaude.watcher")
        val CLIENT_KEY: Key<WsPluginClient> =
            Key.create("remoteclaude.client")
    }
}

package com.remoteclaude.plugin.orchestration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.remoteclaude.plugin.server.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class AgentLauncher(private val project: Project) {

    private val batchProcesses = ConcurrentHashMap<Int, Process>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun launch(
        message: ForwardLaunchAgentMessage,
        pluginClient: WsPluginClient,
        registry: TabRegistry,
    ) {
        when (message.mode) {
            AgentMode.INTERACTIVE -> launchInteractive(message, pluginClient)
            AgentMode.BATCH -> launchBatch(message, pluginClient, registry)
        }
    }

    private fun launchInteractive(
        message: ForwardLaunchAgentMessage,
        pluginClient: WsPluginClient,
    ) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val tvClass = runCatching {
                    Class.forName("com.intellij.terminal.TerminalView")
                }.getOrElse {
                    Class.forName("org.jetbrains.plugins.terminal.TerminalView")
                }
                val getInstance = tvClass.getMethod("getInstance", Project::class.java)
                val terminalView = getInstance.invoke(null, project)

                val projectName = File(message.projectPath).name
                val createWidget = tvClass.getMethod(
                    "createLocalShellWidget", String::class.java, String::class.java
                )
                val widget = createWidget.invoke(terminalView, message.projectPath, "claude: $projectName")

                if (message.prompt.isNotBlank()) {
                    val cmd = buildClaudeCommand(message)
                    val executeCmd = widget.javaClass.getMethod("executeCommand", String::class.java)
                    executeCmd.invoke(widget, cmd)
                }
            } catch (e: Exception) {
                pluginClient.sendFireAndForget(
                    PluginAgentCompletedMessage(pluginClient.pluginId, 0, 1)
                )
            }
        }
    }

    private fun launchBatch(
        message: ForwardLaunchAgentMessage,
        pluginClient: WsPluginClient,
        registry: TabRegistry,
    ) {
        scope.launch {
            val tabInfo = registry.registerTab(
                "batch: ${File(message.projectPath).name}",
                message.projectPath
            )
            pluginClient.send(
                PluginAgentLaunchedMessage(pluginClient.pluginId, tabInfo.id, message.projectPath, AgentMode.BATCH)
            )

            val cmd = buildList {
                add("claude")
                add("--print")
                add(message.prompt)
                add("--output-format")
                add("stream-json")
                add("--no-color")
                if (message.allowedTools.isNotEmpty()) {
                    add("--allowedTools")
                    add(message.allowedTools.joinToString(","))
                }
            }

            try {
                val process = ProcessBuilder(cmd)
                    .directory(File(message.projectPath))
                    .redirectErrorStream(true)
                    .start()
                batchProcesses[tabInfo.id] = process

                process.inputStream.bufferedReader().forEachLine { line ->
                    registry.getBuffer(tabInfo.id)?.append(line + "\n")
                    runBlocking {
                        pluginClient.send(
                            PluginAgentOutputMessage(pluginClient.pluginId, tabInfo.id, line, isJson = true)
                        )
                    }
                }

                val exitCode = process.waitFor()
                batchProcesses.remove(tabInfo.id)
                val finalState = if (exitCode == 0) TabState.COMPLETED else TabState.FAILED
                registry.updateTabState(tabInfo.id, finalState)
                pluginClient.send(
                    PluginAgentCompletedMessage(pluginClient.pluginId, tabInfo.id, exitCode)
                )
            } catch (e: Exception) {
                batchProcesses.remove(tabInfo.id)
                registry.updateTabState(tabInfo.id, TabState.FAILED)
                pluginClient.sendFireAndForget(
                    PluginAgentCompletedMessage(pluginClient.pluginId, tabInfo.id, 1)
                )
            }
        }
    }

    fun terminate(tabId: Int) {
        batchProcesses[tabId]?.destroyForcibly()
        batchProcesses.remove(tabId)
    }

    companion object {
        fun getInstance(project: Project): AgentLauncher =
            project.getService(AgentLauncher::class.java)
    }

    private fun buildClaudeCommand(message: ForwardLaunchAgentMessage): String {
        val parts = mutableListOf("claude")
        if (message.allowedTools.isNotEmpty()) {
            parts.add("--allowedTools ${message.allowedTools.joinToString(",")}")
        }
        if (message.prompt.isNotBlank()) {
            parts.add("--message \"${message.prompt.replace("\"", "\\\"")}\"")
        }
        return parts.joinToString(" ")
    }
}

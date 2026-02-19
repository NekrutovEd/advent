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

    fun launch(
        message: LaunchAgentMessage,
        scope: CoroutineScope,
        registry: TabRegistry,
        sessionManager: WsSessionManager,
    ) {
        when (message.mode) {
            AgentMode.INTERACTIVE -> launchInteractive(message, scope, registry, sessionManager)
            AgentMode.BATCH -> launchBatch(message, scope, registry, sessionManager)
        }
    }

    private fun launchInteractive(
        message: LaunchAgentMessage,
        scope: CoroutineScope,
        registry: TabRegistry,
        sessionManager: WsSessionManager,
    ) {
        ApplicationManager.getApplication().invokeLater {
            try {
                // Access TerminalView via reflection to avoid hard compile-time dependency.
                // The class may be at com.intellij.terminal.TerminalView or
                // org.jetbrains.plugins.terminal.TerminalView depending on IDE version.
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
                scope.launch {
                    sessionManager.broadcast(ErrorMessage("Failed to open terminal: ${e.message}"))
                }
            }
        }
    }

    private fun launchBatch(
        message: LaunchAgentMessage,
        scope: CoroutineScope,
        registry: TabRegistry,
        sessionManager: WsSessionManager,
    ) {
        scope.launch(Dispatchers.IO) {
            val tabInfo = registry.registerTab(
                "batch: ${File(message.projectPath).name}",
                message.projectPath
            )
            sessionManager.broadcast(
                AgentLaunchedMessage(tabInfo.id, message.projectPath, AgentMode.BATCH)
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
                        sessionManager.broadcast(AgentOutputMessage(tabInfo.id, line, isJson = true))
                    }
                }

                val exitCode = process.waitFor()
                batchProcesses.remove(tabInfo.id)
                val finalState = if (exitCode == 0) TabState.COMPLETED else TabState.FAILED
                registry.updateTabState(tabInfo.id, finalState)
                sessionManager.broadcast(AgentCompletedMessage(tabInfo.id, exitCode))
            } catch (e: Exception) {
                batchProcesses.remove(tabInfo.id)
                registry.updateTabState(tabInfo.id, TabState.FAILED)
                sessionManager.broadcast(ErrorMessage("Batch agent failed: ${e.message}"))
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

    private fun buildClaudeCommand(message: LaunchAgentMessage): String {
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

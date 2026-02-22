package com.remoteclaude.server.terminal

import com.remoteclaude.server.protocol.*
import com.remoteclaude.server.state.GlobalTabRegistry
import com.pty4j.PtyProcessBuilder
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class StandaloneTerminalManager(
    private val tabRegistry: GlobalTabRegistry,
    private val broadcastToApps: suspend (WsMessage) -> Unit,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(StandaloneTerminalManager::class.java)

    companion object {
        const val PLUGIN_ID = "server"
        const val PLUGIN_NAME = "Server Terminal"
        private const val IDLE_TIMEOUT_MS = 1500L
    }

    private data class TerminalHandle(
        val process: Process,
        val outputStream: OutputStream,
        val readerJob: Job,
        val localTabId: Int,
    )

    private val terminals = ConcurrentHashMap<Int, TerminalHandle>()
    private val terminalStates = ConcurrentHashMap<Int, TabState>()
    private val idleJobs = ConcurrentHashMap<Int, Job>()
    private val nextId = AtomicInteger(1)

    fun isServerTerminal(pluginId: String): Boolean = pluginId == PLUGIN_ID

    suspend fun create(workingDir: String? = null): String {
        val localTabId = nextId.getAndIncrement()
        val globalId = TabNamespace.toGlobal(PLUGIN_ID, localTabId)

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val cmd = if (isWindows) arrayOf("powershell.exe", "-NoLogo") else arrayOf("/bin/bash", "-l")

        val env = System.getenv().toMutableMap()
        env["TERM"] = "xterm-256color"

        val dir = workingDir?.let { java.io.File(it) }?.takeIf { it.isDirectory }
            ?: java.io.File(System.getProperty("user.home"))

        log.info("Creating server terminal $globalId in ${dir.absolutePath}")

        val process = PtyProcessBuilder()
            .setCommand(cmd)
            .setEnvironment(env)
            .setDirectory(dir.absolutePath)
            .setInitialColumns(120)
            .setInitialRows(40)
            .start()

        // Register tab
        val localTab = LocalTabInfo(
            id = localTabId,
            title = "Server: ${dir.name}",
            state = TabState.RUNNING,
            projectPath = dir.absolutePath,
        )
        val globalTab = tabRegistry.registerTab(PLUGIN_ID, PLUGIN_NAME, localTab)
        terminalStates[localTabId] = TabState.RUNNING

        // Start reader coroutine
        val readerJob = scope.launch(Dispatchers.IO) {
            try {
                val inputStream = process.inputStream
                val buf = ByteArray(4096)
                while (isActive) {
                    val n = inputStream.read(buf)
                    if (n < 0) break
                    val data = String(buf, 0, n)
                    tabRegistry.getBuffer(globalId)?.append(data)
                    tabRegistry.emitOutput(globalId, data)
                    broadcastToApps(OutputMessage(globalId, data))

                    // Immediate state detection (WAITING_TOOL, WAITING_INPUT patterns)
                    val currentState = terminalStates[localTabId] ?: TabState.RUNNING
                    val immediateState = OutputAnalyzer.analyzeChunk(data)
                    if (immediateState != null && immediateState != currentState) {
                        updateState(localTabId, globalId, immediateState)
                    } else if (currentState != TabState.RUNNING && immediateState == null) {
                        // New output arrived while waiting → back to RUNNING
                        val clean = data.replace(Regex("""\x1B\[[0-9;]*[a-zA-Z]"""), "")
                        if (clean.isNotBlank()) {
                            updateState(localTabId, globalId, TabState.RUNNING)
                        }
                    }

                    // Schedule idle detection
                    scheduleIdleCheck(localTabId, globalId)
                }
            } catch (e: Exception) {
                if (isActive) {
                    log.warn("Reader error for $globalId: ${e.message}")
                }
            } finally {
                // Process ended — clean up
                idleJobs.remove(localTabId)?.cancel()
                terminalStates.remove(localTabId)
                if (terminals.containsKey(localTabId)) {
                    terminals.remove(localTabId)
                    tabRegistry.removeTab(globalId)
                    broadcastToApps(TabRemovedMessage(globalId))
                    log.info("Server terminal $globalId ended")
                }
            }
        }

        val handle = TerminalHandle(process, process.outputStream, readerJob, localTabId)
        terminals[localTabId] = handle

        // Broadcast tab added
        broadcastToApps(TabAddedMessage(globalTab))
        log.info("Server terminal $globalId created (pid=${process.pid()})")

        return globalId
    }

    private fun scheduleIdleCheck(localTabId: Int, globalId: String) {
        idleJobs[localTabId]?.cancel()
        idleJobs[localTabId] = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            val currentState = terminalStates[localTabId] ?: return@launch
            if (currentState == TabState.RUNNING || currentState == TabState.STARTING) {
                val bufferTail = tabRegistry.getBuffer(globalId)?.getSnapshot()?.takeLast(500) ?: ""
                val idleState = OutputAnalyzer.analyzeIdle(bufferTail)
                if (idleState != null && idleState != currentState) {
                    updateState(localTabId, globalId, idleState)
                }
            }
        }
    }

    private suspend fun updateState(localTabId: Int, globalId: String, state: TabState) {
        terminalStates[localTabId] = state
        tabRegistry.updateTabState(globalId, state)
        broadcastToApps(TabStateMessage(globalId, state))
    }

    fun sendInput(localTabId: Int, data: String) {
        val handle = terminals[localTabId] ?: run {
            log.warn("sendInput: no terminal for localTabId=$localTabId")
            return
        }
        try {
            handle.outputStream.write(data.toByteArray())
            handle.outputStream.flush()
        } catch (e: Exception) {
            log.warn("sendInput error for localTabId=$localTabId: ${e.message}")
        }
    }

    suspend fun close(localTabId: Int) {
        val handle = terminals.remove(localTabId) ?: return
        val globalId = TabNamespace.toGlobal(PLUGIN_ID, localTabId)
        log.info("Closing server terminal $globalId")

        idleJobs.remove(localTabId)?.cancel()
        terminalStates.remove(localTabId)
        handle.readerJob.cancel()
        try { handle.process.destroyForcibly() } catch (_: Exception) {}
        tabRegistry.removeTab(globalId)
        broadcastToApps(TabRemovedMessage(globalId))
    }

    suspend fun stopAll() {
        log.info("Stopping all server terminals (${terminals.size})")
        val ids = terminals.keys.toList()
        for (id in ids) {
            close(id)
        }
    }

    fun getBuffer(localTabId: Int): String? {
        val globalId = TabNamespace.toGlobal(PLUGIN_ID, localTabId)
        return tabRegistry.getBuffer(globalId)?.getSnapshot()
    }
}

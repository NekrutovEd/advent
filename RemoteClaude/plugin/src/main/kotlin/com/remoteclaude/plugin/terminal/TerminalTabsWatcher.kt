package com.remoteclaude.plugin.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.remoteclaude.plugin.server.*
import kotlinx.coroutines.*
import java.util.concurrent.CompletableFuture

class TerminalTabsWatcher(private val project: Project) : Disposable {

    private val LOG = Logger.getInstance(TerminalTabsWatcher::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Map tabId -> function that sends input text to the terminal (UI-based fallback)
    private val inputSenders = mutableMapOf<Int, (String) -> Unit>()

    // Map tabId -> Content reference for lazy TtyConnector search at send time
    private val tabContents = mutableMapOf<Int, com.intellij.ui.content.Content>()

    // Map tabId -> cached TtyConnector (found via lazy search)
    private val cachedConnectors = mutableMapOf<Int, com.jediterm.terminal.TtyConnector>()

    // Map content display name -> tabId for removal lookups
    private val contentToTabId = mutableMapOf<String, Int>()

    // Reference to the local WsServer's registry (for local tab tracking + buffers)
    private val registry: TabRegistry
        get() = WsServer.getInstance(project).registry

    fun start(client: WsPluginClient) {
        scope.launch {
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("Terminal") ?: return@launch

            val contentManager = toolWindow.contentManager

            contentManager.addContentManagerListener(object : ContentManagerListener {
                override fun contentAdded(event: ContentManagerEvent) {
                    scope.launch { onTabAdded(event.content, client) }
                }

                override fun contentRemoved(event: ContentManagerEvent) {
                    scope.launch { onTabRemoved(event.content, client) }
                }
            })

            // Register already-open tabs
            withContext(Dispatchers.Main) {
                contentManager.contents.forEach { content ->
                    scope.launch { onTabAdded(content, client) }
                }
            }
        }
    }

    private suspend fun onTabAdded(content: com.intellij.ui.content.Content, client: WsPluginClient) {
        val title = content.displayName ?: "Terminal"
        val tabInfo = registry.registerTab(title, null)

        contentToTabId[title] = tabInfo.id
        tabContents[tabInfo.id] = content

        scope.launch(Dispatchers.IO) {
            attachOutputInterceptor(content, tabInfo.id, client)
        }

        client.send(PluginTabAddedMessage(client.pluginId, tabInfo))
    }

    private suspend fun onTabRemoved(content: com.intellij.ui.content.Content, client: WsPluginClient) {
        val title = content.displayName ?: return
        val tabId = contentToTabId.remove(title)
            ?: registry.removeTabByTitle(title)
            ?: return
        inputSenders.remove(tabId)
        tabContents.remove(tabId)
        cachedConnectors.remove(tabId)
        registry.removeTab(tabId)
        client.send(PluginTabRemovedMessage(client.pluginId, tabId))
    }

    // ── Main attachment logic ──────────────────────────────────────────────

    private suspend fun attachOutputInterceptor(
        content: com.intellij.ui.content.Content,
        tabId: Int,
        client: WsPluginClient,
    ) {
        var asyncCallbackRegistered = false
        val asyncConnector = CompletableDeferred<com.jediterm.terminal.TtyConnector>()

        // Register async TtyConnector callback early (for input upgrade later)
        run {
            val accessor = findObjectByClassName(content, "TtyConnectorAccessor", maxDepth = 6)
            if (accessor != null) {
                val future = extractCompletableFuture(accessor, "ttyConnectorFuture")
                if (future != null) {
                    LOG.info("RemoteClaude: [tab $tabId] TtyConnectorAccessor found, future: isDone=${future.isDone}")
                    asyncCallbackRegistered = true
                    if (future.isDone && !future.isCancelled) {
                        try {
                            val result = future.getNow(null)
                            if (result is com.jediterm.terminal.TtyConnector) {
                                asyncConnector.complete(result)
                            }
                        } catch (_: Exception) {}
                    }
                    if (!asyncConnector.isCompleted) {
                        future.whenComplete { result, ex ->
                            if (ex == null && result is com.jediterm.terminal.TtyConnector) {
                                LOG.info("RemoteClaude: [tab $tabId] ttyConnectorFuture completed async!")
                                asyncConnector.complete(result)
                            }
                        }
                    }
                }
            }
        }

        // Phase 1: Try Editor-based I/O first (non-destructive output — preferred)
        LOG.info("RemoteClaude: [tab $tabId] Phase 1: trying Editor-based I/O (non-destructive)...")
        for (attempt in 1..5) {
            delay(500)
            val editorSetup = setupEditorBasedIO(content, tabId, client)
            if (editorSetup) {
                LOG.info("RemoteClaude: [tab $tabId] Editor-based I/O active (attempt $attempt)")
                if (asyncCallbackRegistered) {
                    scope.launch {
                        val c = withTimeoutOrNull(300_000L) { asyncConnector.await() }
                        if (c != null) {
                            LOG.info("RemoteClaude: [tab $tabId] TtyConnector arrived — upgrading input sender")
                            cachedConnectors[tabId] = c
                            inputSenders[tabId] = { text ->
                                try { c.write(text) }
                                catch (e: Exception) { LOG.warn("RemoteClaude: [tab $tabId] async write failed: ${e.message}") }
                            }
                        }
                    }
                }
                return
            }
        }

        // Phase 2: No Editor — try TtyConnector for full I/O
        LOG.info("RemoteClaude: [tab $tabId] Phase 2: Editor not found, trying TtyConnector for full I/O...")
        dumpWidgetDiagnostics(content, tabId)

        var connector: com.jediterm.terminal.TtyConnector? = null

        for (attempt in 1..5) {
            val component = content.component

            if (component != null) {
                connector = findTtyConnectorByMethod(component)
                if (connector != null) {
                    LOG.info("RemoteClaude: [tab $tabId] found TtyConnector via method search (attempt $attempt)")
                    break
                }
            }

            if (asyncConnector.isCompleted) {
                connector = asyncConnector.await()
                LOG.info("RemoteClaude: [tab $tabId] got TtyConnector from async callback")
                break
            }

            if (component != null) {
                connector = findTtyConnectorDeep(component)
                if (connector != null) {
                    LOG.info("RemoteClaude: [tab $tabId] found TtyConnector via deep field search (attempt $attempt)")
                    break
                }
            }

            delay(500)
        }

        if (connector != null) {
            startConnectorIO(tabId, connector, client)
            return
        }

        // Phase 3: fallback — wait for async TtyConnector (up to 60s)
        if (asyncCallbackRegistered) {
            LOG.info("RemoteClaude: [tab $tabId] waiting for async TtyConnector (60s)...")
            connector = withTimeoutOrNull(60_000L) { asyncConnector.await() }
            if (connector != null) {
                startConnectorIO(tabId, connector, client)
                return
            }
        }

        LOG.warn("RemoteClaude: [tab $tabId] ALL I/O strategies failed")
    }

    private suspend fun startConnectorIO(
        tabId: Int,
        connector: com.jediterm.terminal.TtyConnector,
        client: WsPluginClient,
    ) {
        for (attempt in 1..40) {
            if (connector.isConnected) break
            delay(250)
        }
        if (!connector.isConnected) {
            LOG.warn("RemoteClaude: [tab $tabId] TtyConnector never became connected")
            return
        }

        cachedConnectors[tabId] = connector
        inputSenders[tabId] = { text ->
            try {
                connector.write(text)
            } catch (e: Exception) {
                LOG.warn("RemoteClaude: [tab $tabId] write failed: ${e.message}")
            }
        }

        LOG.info("RemoteClaude: [tab $tabId] starting output reader, connector=${connector.javaClass.name}")
        registry.updateTabState(tabId, TabState.RUNNING)
        client.send(PluginTabStateMessage(client.pluginId, tabId, TabState.RUNNING))

        // Idle detection coroutine: checks every 500ms if output has been idle for 1.5s
        val idleJob = scope.launch {
            while (isActive) {
                delay(500)
                val lastOutput = registry.getLastOutputTime(tabId) ?: continue
                val idleMs = System.currentTimeMillis() - lastOutput
                if (idleMs >= 1500) {
                    val currentState = registry.getTabInfo(tabId)?.state ?: TabState.RUNNING
                    if (currentState == TabState.RUNNING || currentState == TabState.STARTING) {
                        val bufferTail = registry.getBuffer(tabId)?.getSnapshot()?.takeLast(500) ?: ""
                        val idleState = AgentLifecycleMonitor.analyzeIdle(bufferTail)
                        if (idleState != null && idleState != currentState) {
                            LOG.info("RemoteClaude: [tab $tabId] idle detected state=$idleState (idleMs=$idleMs)")
                            registry.updateTabState(tabId, idleState)
                            client.send(PluginTabStateMessage(client.pluginId, tabId, idleState))
                        } else if (idleMs >= 6000) {
                            // Fallback: 6+ seconds of no output with no pattern match — assume idle
                            LOG.info("RemoteClaude: [tab $tabId] idle fallback: no output for ${idleMs}ms, forcing WAITING_INPUT")
                            registry.updateTabState(tabId, TabState.WAITING_INPUT)
                            client.send(PluginTabStateMessage(client.pluginId, tabId, TabState.WAITING_INPUT))
                        }
                    }
                }
            }
        }

        val buf = CharArray(4096)
        val ctx = currentCoroutineContext()
        try {
            while (ctx.isActive) {
                try {
                    if (!connector.isConnected) {
                        delay(500)
                        continue
                    }
                    val n = connector.read(buf, 0, buf.size)
                    if (n > 0) {
                        val data = String(buf, 0, n)
                        registry.getBuffer(tabId)?.append(data)
                        registry.touchOutput(tabId)

                        val currentState = registry.getTabInfo(tabId)?.state ?: TabState.RUNNING
                        val newState = AgentLifecycleMonitor.analyze(data, currentState)

                        if (newState != currentState) {
                            registry.updateTabState(tabId, newState)
                            client.send(PluginTabStateMessage(client.pluginId, tabId, newState))
                        }
                        client.send(PluginOutputMessage(client.pluginId, tabId, data))
                    } else if (n < 0) {
                        LOG.info("RemoteClaude: [tab $tabId] EOF")
                        break
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    LOG.info("RemoteClaude: [tab $tabId] reader stopped: ${e.message}")
                    break
                }
            }
        } finally {
            idleJob.cancel()
        }
    }

    // ── Phase 2: Editor-based I/O for IntelliJ 2025.2 block terminal ─────

    private suspend fun setupEditorBasedIO(
        content: com.intellij.ui.content.Content,
        tabId: Int,
        client: WsPluginClient,
    ): Boolean {
        val editorComponent = withContext(Dispatchers.Main) {
            val root = content.component ?: return@withContext null
            findAwtComponentByClassName(root, "EditorComponentImpl")
        }
        if (editorComponent == null) {
            LOG.info("RemoteClaude: [tab $tabId] EditorComponentImpl not found in AWT tree")
            return false
        }
        LOG.info("RemoteClaude: [tab $tabId] found EditorComponentImpl: ${editorComponent.javaClass.name}")

        val editor = withContext(Dispatchers.Main) {
            try {
                val method = editorComponent.javaClass.getMethod("getEditor")
                method.invoke(editorComponent) as? com.intellij.openapi.editor.Editor
            } catch (e: Exception) {
                LOG.warn("RemoteClaude: [tab $tabId] getEditor() failed: ${e.message}")
                null
            }
        }
        if (editor == null) {
            LOG.warn("RemoteClaude: [tab $tabId] could not get Editor from EditorComponentImpl")
            return false
        }

        val document = editor.document
        LOG.info("RemoteClaude: [tab $tabId] got Document, textLength=${document.textLength}")

        // Output: poll document every 50ms
        scope.launch {
            var lastStamp = withContext(Dispatchers.Main) { document.modificationStamp }
            var lastText = withContext(Dispatchers.Main) { document.text }

            val defaultFg = withContext(Dispatchers.Main) { editor.colorsScheme.defaultForeground }

            // Send initial content
            if (lastText.isNotEmpty()) {
                val initialHighlighters = withContext(Dispatchers.Main) {
                    AnsiColorEncoder.getHighlightersForRange(editor, 0, lastText.length)
                }
                val colorized = AnsiColorEncoder.encode(lastText, initialHighlighters, 0, lastText.length, defaultFg)
                registry.getBuffer(tabId)?.append(colorized)
                registry.touchOutput(tabId)
                client.send(PluginOutputMessage(client.pluginId, tabId, colorized))
                LOG.info("RemoteClaude: [tab $tabId] sent initial document text (${lastText.length} chars)")

                // Analyze initial state from existing terminal content (use idle analysis for shell prompts)
                val initialState = AgentLifecycleMonitor.analyzeIdle(lastText.takeLast(500))
                    ?: AgentLifecycleMonitor.analyze(lastText.takeLast(1000), TabState.RUNNING)
                if (initialState != TabState.RUNNING) {
                    registry.updateTabState(tabId, initialState)
                    client.send(PluginTabStateMessage(client.pluginId, tabId, initialState))
                }
            }

            var idleTicks = 0           // counts consecutive polls with no document change
            val idleThreshold = 30      // 30 * 50ms = 1.5s

            while (isActive) {
                delay(50)
                val (currentStamp, currentText) = withContext(Dispatchers.Main) {
                    document.modificationStamp to document.text
                }
                if (currentStamp != lastStamp) {
                    idleTicks = 0
                    var rawTextForAnalysis: String
                    if (currentText.startsWith(lastText)) {
                        val deltaStart = lastText.length
                        val deltaEnd = currentText.length
                        rawTextForAnalysis = currentText.substring(deltaStart, deltaEnd)
                        if (deltaStart < deltaEnd) {
                            val highlighters = withContext(Dispatchers.Main) {
                                AnsiColorEncoder.getHighlightersForRange(editor, deltaStart, deltaEnd)
                            }
                            val delta = AnsiColorEncoder.encode(currentText, highlighters, deltaStart, deltaEnd, defaultFg)
                            registry.getBuffer(tabId)?.append(delta)
                            registry.touchOutput(tabId)
                            client.send(PluginOutputMessage(client.pluginId, tabId, delta))
                        }
                    } else {
                        rawTextForAnalysis = currentText.takeLast(1000)
                        val highlighters = withContext(Dispatchers.Main) {
                            AnsiColorEncoder.getHighlightersForRange(editor, 0, currentText.length)
                        }
                        val colorized = AnsiColorEncoder.encode(currentText, highlighters, 0, currentText.length, defaultFg)
                        registry.getBuffer(tabId)?.clear()
                        registry.getBuffer(tabId)?.append(colorized)
                        registry.touchOutput(tabId)
                        client.send(PluginOutputMessage(client.pluginId, tabId, "\u001b[2J\u001b[H$colorized"))
                    }

                    // Analyze state from raw document text (no ANSI codes)
                    val currentState = registry.getTabInfo(tabId)?.state ?: TabState.RUNNING
                    val newState = AgentLifecycleMonitor.analyze(rawTextForAnalysis, currentState)
                    if (newState != currentState) {
                        registry.updateTabState(tabId, newState)
                        client.send(PluginTabStateMessage(client.pluginId, tabId, newState))
                    }

                    lastStamp = currentStamp
                    lastText = currentText
                } else {
                    // No document change — increment idle counter
                    idleTicks++
                    // Check periodically (every 1.5s of continuous idleness)
                    if (idleTicks >= idleThreshold && idleTicks % idleThreshold == 0) {
                        val currentState = registry.getTabInfo(tabId)?.state ?: TabState.RUNNING
                        if (currentState == TabState.RUNNING || currentState == TabState.STARTING) {
                            val tail = currentText.takeLast(500)
                            val idleState = AgentLifecycleMonitor.analyzeIdle(tail)
                            if (idleState != null && idleState != currentState) {
                                LOG.info("RemoteClaude: [tab $tabId] idle detected state=$idleState (ticks=$idleTicks)")
                                registry.updateTabState(tabId, idleState)
                                client.send(PluginTabStateMessage(client.pluginId, tabId, idleState))
                            } else if (idleTicks >= idleThreshold * 4) {
                                // Fallback: 6+ seconds of no output with no pattern match — assume idle
                                LOG.info("RemoteClaude: [tab $tabId] idle fallback: no output for ${idleTicks * 50}ms, forcing WAITING_INPUT")
                                registry.updateTabState(tabId, TabState.WAITING_INPUT)
                                client.send(PluginTabStateMessage(client.pluginId, tabId, TabState.WAITING_INPUT))
                            }
                        }
                    }
                }
            }
        }

        // Store content ref for lazy TtyConnector search at send time
        tabContents[tabId] = content

        // Input setup
        inputSenders[tabId] = setupInputSender(content, tabId, editor)

        // Mark running
        registry.updateTabState(tabId, TabState.RUNNING)
        scope.launch {
            client.send(PluginTabStateMessage(client.pluginId, tabId, TabState.RUNNING))
            client.send(PluginOutputMessage(client.pluginId, tabId,
                "\r\n--- [RemoteClaude] Editor I/O active for tab $tabId ---\r\n"
            ))
        }

        LOG.info("RemoteClaude: [tab $tabId] Editor-based I/O ready")
        return true
    }

    /** Recursively find an AWT component whose class name contains the given substring. */
    private fun findAwtComponentByClassName(
        root: java.awt.Component,
        classNamePart: String,
    ): java.awt.Component? {
        if (root.javaClass.name.contains(classNamePart)) return root
        if (root is java.awt.Container) {
            for (child in root.components) {
                val found = findAwtComponentByClassName(child, classNamePart)
                if (found != null) return found
            }
        }
        return null
    }

    private fun setupInputSender(
        content: com.intellij.ui.content.Content,
        tabId: Int,
        editor: com.intellij.openapi.editor.Editor,
    ): (String) -> Unit {
        val processSender = findProcessSender(content, tabId)
        if (processSender != null) return processSender

        val terminalInput = findObjectByClassName(content, "TerminalInput", maxDepth = 8)
        if (terminalInput != null) {
            LOG.info("RemoteClaude: [tab $tabId] TerminalInput found: ${terminalInput.javaClass.name}")

            val candidateMethods = mutableListOf<java.lang.reflect.Method>()
            var c: Class<*>? = terminalInput.javaClass
            while (c != null && c != Any::class.java) {
                for (m in c.declaredMethods) {
                    if (m.parameterCount == 1 &&
                        (m.parameterTypes[0] == String::class.java ||
                         m.parameterTypes[0] == CharSequence::class.java)
                    ) {
                        candidateMethods.add(m)
                    }
                }
                c = c.superclass
            }

            val prioritized = candidateMethods.sortedBy { m ->
                when (m.name) {
                    "sendString" -> 0
                    "sendBytes" -> 1
                    "sendBracketedString" -> 10
                    else -> 5
                }
            }

            for (m in prioritized) {
                LOG.info("RemoteClaude: [tab $tabId] trying TerminalInput.${m.name}(String) for input")
                m.isAccessible = true
                if (m.name == "sendString") {
                    LOG.info("RemoteClaude: [tab $tabId] input via TerminalInput.sendString() (native \\n)")
                    return { text -> m.invoke(terminalInput, text) }
                }
                try {
                    m.invoke(terminalInput, " ")
                    LOG.info("RemoteClaude: [tab $tabId] input via TerminalInput.${m.name}() + EditorEnter")
                    return wrapWithEditorEnter(editor, tabId) { text -> m.invoke(terminalInput, text) }
                } catch (e: Exception) {
                    LOG.info("RemoteClaude: [tab $tabId] TerminalInput.${m.name}() test failed: ${e.message}")
                }
            }

            val channelSender = setupBufferChannelSender(terminalInput, tabId)
            if (channelSender != null) return wrapWithEditorEnter(editor, tabId, channelSender)
        } else {
            LOG.info("RemoteClaude: [tab $tabId] TerminalInput NOT found in field graph")
        }

        LOG.info("RemoteClaude: [tab $tabId] input via KeyEvent dispatch + EditorEnter")
        val contentComponent = try {
            editor.contentComponent
        } catch (_: Exception) { null }
        val target = contentComponent ?: (editor as? java.awt.Component)

        return { text ->
            if (target != null) {
                javax.swing.SwingUtilities.invokeLater {
                    try {
                        for (char in text) {
                            if (char == '\n' || char == '\r') {
                                dispatchEditorEnter(editor, tabId)
                            } else {
                                target.dispatchEvent(java.awt.event.KeyEvent(
                                    target, java.awt.event.KeyEvent.KEY_TYPED,
                                    System.currentTimeMillis(), 0,
                                    java.awt.event.KeyEvent.VK_UNDEFINED, char
                                ))
                            }
                        }
                    } catch (e: Exception) {
                        LOG.warn("RemoteClaude: [tab $tabId] key dispatch error: ${e.message}")
                    }
                }
            } else {
                LOG.warn("RemoteClaude: [tab $tabId] no target component for KeyEvent dispatch")
            }
        }
    }

    private fun wrapWithEditorEnter(
        editor: com.intellij.openapi.editor.Editor,
        tabId: Int,
        typeSender: (String) -> Unit,
    ): (String) -> Unit {
        return { text ->
            val parts = text.split('\n', '\r')
            val endsWithNewline = text.endsWith("\n") || text.endsWith("\r")
            for ((index, part) in parts.withIndex()) {
                if (part.isNotEmpty()) {
                    try {
                        typeSender(part)
                    } catch (e: Exception) {
                        LOG.warn("RemoteClaude: [tab $tabId] typeSender failed: ${e.cause?.message ?: e.message}")
                    }
                }
                if (index < parts.size - 1 || endsWithNewline) {
                    dispatchEditorEnter(editor, tabId)
                }
            }
        }
    }

    private fun dispatchEditorEnter(editor: com.intellij.openapi.editor.Editor, tabId: Int) {
        javax.swing.SwingUtilities.invokeLater {
            try {
                com.intellij.openapi.application.WriteIntentReadAction.compute<Unit, Exception> {
                    val handler = com.intellij.openapi.editor.actionSystem.EditorActionManager.getInstance()
                        .getActionHandler(com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_ENTER)
                    val dataContext = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                        .add(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, editor)
                        .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                        .build()
                    handler.execute(editor, null, dataContext)
                }
                LOG.info("RemoteClaude: [tab $tabId] EditorEnter dispatched OK")
            } catch (e: Exception) {
                LOG.warn("RemoteClaude: [tab $tabId] dispatchEditorEnter failed: ${e.message}")
            }
        }
    }

    private fun findProcessSender(content: com.intellij.ui.content.Content, tabId: Int): ((String) -> Unit)? {
        for (className in listOf("PtyProcess", "WinPtyProcess", "UnixPtyProcess")) {
            val processObj = findObjectByClassName(content, className, maxDepth = 10) ?: continue
            LOG.info("RemoteClaude: [tab $tabId] found $className: ${processObj.javaClass.name}")

            if (processObj is Process && processObj.isAlive) {
                LOG.info("RemoteClaude: [tab $tabId] input via ${processObj.javaClass.simpleName}.outputStream")
                val os = processObj.outputStream
                return { text ->
                    try {
                        os.write(text.toByteArray(Charsets.UTF_8))
                        os.flush()
                    } catch (e: Exception) {
                        LOG.warn("RemoteClaude: [tab $tabId] Process.write failed: ${e.message}")
                    }
                }
            }

            try {
                val getOS = processObj.javaClass.getMethod("getOutputStream")
                val os = getOS.invoke(processObj) as? java.io.OutputStream
                if (os != null) {
                    LOG.info("RemoteClaude: [tab $tabId] input via ${processObj.javaClass.simpleName}.getOutputStream() (reflection)")
                    return { text ->
                        try {
                            os.write(text.toByteArray(Charsets.UTF_8))
                            os.flush()
                        } catch (e: Exception) {
                            LOG.warn("RemoteClaude: [tab $tabId] OutputStream.write failed: ${e.message}")
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        LOG.info("RemoteClaude: [tab $tabId] Process not found for input")
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun setupBufferChannelSender(terminalInput: Any, tabId: Int): ((String) -> Unit)? {
        val bufferChannel = try {
            val field = terminalInput.javaClass.declaredFields.find { it.name == "bufferChannel" }
                ?: terminalInput.javaClass.superclass?.declaredFields?.find { it.name == "bufferChannel" }
            field?.isAccessible = true
            field?.get(terminalInput)
        } catch (e: Exception) {
            LOG.warn("RemoteClaude: [tab $tabId] failed to get bufferChannel: ${e.message}")
            null
        } ?: return null

        LOG.info("RemoteClaude: [tab $tabId] bufferChannel type: ${bufferChannel.javaClass.name}")

        val trySendMethod = bufferChannel.javaClass.methods.find {
            it.name == "trySend" && it.parameterCount == 1
        }
        if (trySendMethod == null) {
            LOG.warn("RemoteClaude: [tab $tabId] trySend not found on bufferChannel")
            return null
        }

        LOG.info("RemoteClaude: [tab $tabId] input via bufferChannel.trySend(${trySendMethod.parameterTypes[0].simpleName})")
        return { text ->
            try {
                trySendMethod.invoke(bufferChannel, text)
            } catch (e: Exception) {
                LOG.warn("RemoteClaude: [tab $tabId] bufferChannel.trySend failed: ${e.cause?.message ?: e.message}")
                try {
                    trySendMethod.invoke(bufferChannel, text.toByteArray(Charsets.UTF_8))
                } catch (e2: Exception) {
                    LOG.warn("RemoteClaude: [tab $tabId] bufferChannel.trySend(ByteArray) also failed: ${e2.cause?.message}")
                }
            }
        }
    }

    private fun dumpWidgetDiagnostics(content: com.intellij.ui.content.Content, tabId: Int) {
        val widget = findObjectByClassName(content, "ReworkedTerminalWidget", maxDepth = 5)
        if (widget != null) {
            LOG.info("RemoteClaude: [tab $tabId] === Widget dump ===")
            logAllMembersOf(widget, "      widget")

            try {
                val getSession = widget.javaClass.methods.find { it.name == "getSession" && it.parameterCount == 0 }
                val session = getSession?.invoke(widget)
                if (session != null) {
                    LOG.info("RemoteClaude: [tab $tabId] === TerminalSession dump ===")
                    logAllMembersOf(session, "      session")
                    logAllMethodsWithParams(session, "      session")
                }
            } catch (e: Exception) {
                LOG.info("RemoteClaude: [tab $tabId] getSession() failed: ${e.message}")
            }
        }

        val terminalInput = findObjectByClassName(content, "TerminalInput", maxDepth = 8)
        if (terminalInput != null) {
            LOG.info("RemoteClaude: [tab $tabId] === TerminalInput found! ===")
            logAllMembersOf(terminalInput, "      terminalInput")
            logAllMethodsWithParams(terminalInput, "      terminalInput")
        } else {
            LOG.info("RemoteClaude: [tab $tabId] TerminalInput NOT found in field graph")
        }
    }

    // ── Strategy 1: method-based search (classic JBTerminalWidget) ──────────

    private fun findTtyConnectorByMethod(component: java.awt.Component): com.jediterm.terminal.TtyConnector? {
        val connector = extractTtyConnectorByMethod(component)
        if (connector != null) return connector

        if (component is java.awt.Container) {
            for (child in component.components) {
                val found = findTtyConnectorByMethod(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun extractTtyConnectorByMethod(obj: Any): com.jediterm.terminal.TtyConnector? {
        val methodNames = listOf("getTtyConnector", "ttyConnector", "getProcessTtyConnector")
        for (methodName in methodNames) {
            val method = findMethod(obj.javaClass, methodName) ?: continue
            try {
                method.isAccessible = true
                val result = method.invoke(obj)
                if (result is com.jediterm.terminal.TtyConnector) {
                    return result
                }
            } catch (_: Exception) {}
        }
        return null
    }

    // ── Deep field-based TtyConnector search on AWT tree ──────────────────

    private fun findTtyConnectorDeep(
        root: Any,
        visited: MutableSet<Int> = mutableSetOf(),
        depth: Int = 0,
    ): com.jediterm.terminal.TtyConnector? {
        if (depth > 7) return null
        val id = System.identityHashCode(root)
        if (!visited.add(id)) return null

        if (root is com.jediterm.terminal.TtyConnector) return root

        var clazz: Class<*>? = root.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                try {
                    if (field.type.isPrimitive) continue
                    if (field.type == String::class.java) continue
                    if (field.type == Class::class.java) continue
                    if (field.type.isArray && field.type.componentType.isPrimitive) continue

                    field.isAccessible = true
                    val value = field.get(root) ?: continue

                    if (value is com.jediterm.terminal.TtyConnector) return value

                    val className = value.javaClass.name.lowercase()
                    if (isTerminalRelatedClass(className)) {
                        val found = findTtyConnectorDeep(value, visited, depth + 1)
                        if (found != null) return found
                    }
                } catch (_: Exception) {}
            }
            clazz = clazz.superclass
        }

        if (root is java.awt.Container) {
            for (child in root.components) {
                val found = findTtyConnectorDeep(child, visited, depth + 1)
                if (found != null) return found
            }
        }

        return null
    }

    // ── Utility: find object by class name in field graph ──────────────────

    private fun findObjectByClassName(
        root: Any,
        classNamePart: String,
        visited: MutableSet<Int> = mutableSetOf(),
        depth: Int = 0,
        maxDepth: Int = 6,
    ): Any? {
        if (depth > maxDepth) return null
        val id = System.identityHashCode(root)
        if (!visited.add(id)) return null

        if (root.javaClass.name.contains(classNamePart)) return root

        var clazz: Class<*>? = root.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                try {
                    if (field.type.isPrimitive) continue
                    if (field.type == String::class.java) continue
                    field.isAccessible = true
                    val value = field.get(root) ?: continue

                    if (value.javaClass.name.contains(classNamePart)) return value

                    val cn = value.javaClass.name.lowercase()
                    if (shouldFollowForSearch(cn)) {
                        val found = findObjectByClassName(value, classNamePart, visited, depth + 1, maxDepth)
                        if (found != null) return found
                    }
                } catch (_: Exception) {}
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun shouldFollowForSearch(className: String): Boolean {
        return isTerminalRelatedClass(className) ||
                className.contains("lambda") ||
                className.contains("widget") ||
                className.contains("content") ||
                className.contains("focus") ||
                className.contains("model") ||
                className.contains("controller") ||
                className.contains("runner") ||
                className.contains("view") ||
                className.contains("container")
    }

    // ── Utility: search ALL fields for TtyConnector (unrestricted) ─────────

    private fun findTtyConnectorInAllFields(
        root: Any,
        visited: MutableSet<Int> = mutableSetOf(),
        depth: Int = 0,
        maxDepth: Int = 5,
    ): com.jediterm.terminal.TtyConnector? {
        if (depth > maxDepth) return null
        val id = System.identityHashCode(root)
        if (!visited.add(id)) return null

        if (root is com.jediterm.terminal.TtyConnector) return root

        var clazz: Class<*>? = root.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                try {
                    if (field.type.isPrimitive) continue
                    if (field.type == String::class.java) continue
                    if (field.type == Class::class.java) continue
                    if (field.type.isArray) continue

                    val typeName = field.type.name
                    if (typeName.startsWith("java.lang.") && typeName != "java.lang.Object") continue
                    if (typeName.startsWith("java.util.") && !typeName.contains("concurrent")) continue
                    if (typeName.startsWith("kotlin.")) continue

                    field.isAccessible = true
                    val value = field.get(root) ?: continue

                    if (value is com.jediterm.terminal.TtyConnector) {
                        LOG.info("RemoteClaude: found TtyConnector in ${root.javaClass.simpleName}.${field.name} (type: ${value.javaClass.name})")
                        return value
                    }

                    if (typeName == "java.lang.Object") {
                        val valueCn = value.javaClass.name.lowercase()
                        if (!isTerminalRelatedClass(valueCn) && !valueCn.contains("concurrent")) continue
                    }

                    val found = findTtyConnectorInAllFields(value, visited, depth + 1, maxDepth)
                    if (found != null) return found
                } catch (_: Exception) {}
            }
            clazz = clazz.superclass
        }

        return null
    }

    // ── Helper: extract CompletableFuture from an object ──────────────────

    private fun extractCompletableFuture(obj: Any, preferredFieldName: String): CompletableFuture<*>? {
        var clazz: Class<*>? = obj.javaClass
        var anyFuture: CompletableFuture<*>? = null

        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                try {
                    field.isAccessible = true
                    val value = field.get(obj)
                    if (value is CompletableFuture<*>) {
                        if (field.name == preferredFieldName) {
                            return value
                        }
                        if (anyFuture == null) anyFuture = value
                    }
                } catch (_: Exception) {}
            }
            clazz = clazz.superclass
        }
        return anyFuture
    }

    // ── Helper predicates ──────────────────────────────────────────────────

    private fun isTerminalRelatedClass(className: String): Boolean {
        return className.contains("terminal") ||
                className.contains("session") ||
                className.contains("tty") ||
                className.contains("pty") ||
                className.contains("connector") ||
                className.contains("shell") ||
                className.contains("process") ||
                className.contains("jediterm") ||
                (className.contains("block") && className.contains("term"))
    }

    // ── Diagnostics ────────────────────────────────────────────────────────

    private fun logAllMembersOf(obj: Any, prefix: String) {
        val clazz = obj.javaClass
        LOG.info("$prefix class: ${clazz.name}")

        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (field in c.declaredFields) {
                try {
                    field.isAccessible = true
                    val value = field.get(obj)
                    LOG.info("$prefix field ${field.name}: ${field.type.simpleName} = ${value?.javaClass?.name ?: "null"}")
                } catch (e: Exception) {
                    LOG.info("$prefix field ${field.name}: ${field.type.simpleName} = <error: ${e.message}>")
                }
            }
            c = c.superclass
        }

        c = clazz
        while (c != null && c != Any::class.java) {
            for (method in c.declaredMethods) {
                if (method.parameterCount == 0 && method.returnType != Void.TYPE) {
                    LOG.info("$prefix method ${method.name}(): ${method.returnType.simpleName}")
                }
            }
            c = c.superclass
        }
    }

    private fun logAllMethodsWithParams(obj: Any, prefix: String) {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (method in c.declaredMethods) {
                val params = method.parameterTypes.joinToString(", ") { it.simpleName }
                LOG.info("$prefix allMethod ${method.name}($params): ${method.returnType.simpleName}")
            }
            c = c.superclass
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────────

    private fun findMethod(clazz: Class<*>, name: String): java.lang.reflect.Method? {
        return generateSequence(clazz) { it.superclass }
            .flatMap { cls -> cls.declaredMethods.asSequence() }
            .find { it.name == name && it.parameterCount == 0 }
    }

    fun createTerminal(projectPath: String, server: WsServer) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            try {
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
                val countBefore = toolWindow?.contentManager?.contentCount ?: 0

                // Show the Terminal tool window (may auto-create the first terminal)
                toolWindow?.show()

                val countAfter = toolWindow?.contentManager?.contentCount ?: 0
                if (countBefore == 0 && countAfter > 0) {
                    LOG.info("RemoteClaude: createTerminal — tool window auto-created a terminal on show(), skipping")
                    return@invokeLater
                }

                // Approach 1: TerminalToolWindowManager.createNewSession() — creates reworked terminal
                if (tryCreateViaToolWindowManager()) return@invokeLater

                // Approach 2: IDE action — creates same terminal as the "+" button
                if (tryCreateViaAction()) return@invokeLater

                // Approach 3: Fallback to old API (creates old-style JBTerminalWidget)
                LOG.warn("RemoteClaude: createTerminal fallback to old createLocalShellWidget API")
                val tvClass = runCatching {
                    Class.forName("com.intellij.terminal.TerminalView")
                }.getOrElse {
                    Class.forName("org.jetbrains.plugins.terminal.TerminalView")
                }
                val getInstance = tvClass.getMethod("getInstance", Project::class.java)
                val terminalView = getInstance.invoke(null, project)
                val projectName = java.io.File(projectPath).name
                val createWidget = tvClass.getMethod(
                    "createLocalShellWidget", String::class.java, String::class.java
                )
                createWidget.invoke(terminalView, projectPath, "bash: $projectName")
            } catch (e: Exception) {
                LOG.warn("RemoteClaude: createTerminal failed: ${e.message}")
            }
        }
    }

    private fun tryCreateViaToolWindowManager(): Boolean {
        return try {
            val tmwClass = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager")
            val getInstance = tmwClass.methods.find {
                it.name == "getInstance" && it.parameterCount == 1
                    && it.parameterTypes[0] == Project::class.java
            } ?: return false
            val manager = getInstance.invoke(null, project)

            // Try createNewSession() (no-arg or nullable arg)
            val createMethod = tmwClass.methods.find { m ->
                m.name == "createNewSession" && (m.parameterCount == 0 ||
                    (m.parameterCount == 1 && !m.parameterTypes[0].isPrimitive))
            }
            if (createMethod != null) {
                if (createMethod.parameterCount == 0) {
                    createMethod.invoke(manager)
                } else {
                    createMethod.invoke(manager, null)
                }
                LOG.info("RemoteClaude: createTerminal via TerminalToolWindowManager.${createMethod.name}()")
                return true
            }
            false
        } catch (e: Exception) {
            LOG.info("RemoteClaude: TerminalToolWindowManager approach failed: ${e.message}")
            false
        }
    }

    private fun tryCreateViaAction(): Boolean {
        val actionManager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        val actionIds = listOf("Terminal.NewTab", "terminal.new.session")
        for (id in actionIds) {
            val action = actionManager.getAction(id) ?: continue
            try {
                val dataContext = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                    .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                    .build()
                val event = com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(
                    action, null, "RemoteClaude", dataContext
                )
                action.actionPerformed(event)
                LOG.info("RemoteClaude: createTerminal via action '$id'")
                return true
            } catch (e: Exception) {
                LOG.info("RemoteClaude: action '$id' failed: ${e.message}")
            }
        }
        return false
    }

    fun closeTab(tabId: Int) {
        LOG.info("RemoteClaude: closeTab tabId=$tabId")
        val content = tabContents[tabId] ?: run {
            LOG.warn("RemoteClaude: closeTab - no content found for tabId=$tabId")
            return
        }
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return@invokeLater
            toolWindow.contentManager.removeContent(content, true)
        }
    }

    fun sendInput(tabId: Int, data: String) {
        LOG.info("RemoteClaude: sendInput tabId=$tabId, dataLen=${data.length}, data=${data.take(80)}")

        // 1. Try cached TtyConnector
        val cached = cachedConnectors[tabId]
        if (cached != null) {
            try {
                if (cached.isConnected) {
                    cached.write(data)
                    LOG.info("RemoteClaude: [tab $tabId] SENT via cached TtyConnector")
                    return
                }
            } catch (e: Exception) {
                LOG.warn("RemoteClaude: [tab $tabId] cached connector write failed: ${e.message}")
            }
            cachedConnectors.remove(tabId)
        }

        // 2. Comprehensive lazy TtyConnector search from ALL roots
        val content = tabContents[tabId]
        if (content != null) {
            val connector = lazyFindConnector(content, tabId)
            if (connector != null) {
                cachedConnectors[tabId] = connector
                try {
                    connector.write(data)
                    LOG.info("RemoteClaude: [tab $tabId] SENT via lazy TtyConnector (${connector.javaClass.name})")
                    return
                } catch (e: Exception) {
                    LOG.warn("RemoteClaude: [tab $tabId] lazy connector write failed: ${e.message}")
                    cachedConnectors.remove(tabId)
                }
            }
        }

        // 3. Fallback to pre-configured UI-based sender
        val sender = inputSenders[tabId]
        if (sender != null) {
            LOG.warn("RemoteClaude: [tab $tabId] FALLBACK to UI-based sender (TtyConnector not found)")
            sender.invoke(data)
        } else {
            LOG.warn("RemoteClaude: sendInput FAILED - no sender for tabId=$tabId")
        }
    }

    private fun lazyFindConnector(
        content: com.intellij.ui.content.Content,
        tabId: Int,
    ): com.jediterm.terminal.TtyConnector? {
        val component = content.component
        if (component != null) {
            val c = findTtyConnectorByMethod(component)
                ?: findTtyConnectorDeep(component)
            if (c != null && c.isConnected) {
                LOG.info("RemoteClaude: [tab $tabId] lazy: found via AWT tree")
                return c
            }
        }

        val widget = findObjectByClassName(content, "TerminalWidget", maxDepth = 5)
            ?: findObjectByClassName(content, "ReworkedTerminalWidget", maxDepth = 5)
        if (widget != null) {
            val c = findTtyConnectorInAllFields(widget, maxDepth = 8)
            if (c != null && c.isConnected) {
                LOG.info("RemoteClaude: [tab $tabId] lazy: found via widget fields")
                return c
            }

            try {
                val getSession = widget.javaClass.methods.find { it.name == "getSession" && it.parameterCount == 0 }
                val session = getSession?.let { m -> m.isAccessible = true; m.invoke(widget) }
                if (session != null) {
                    val sc = findTtyConnectorInAllFields(session, maxDepth = 8)
                    if (sc != null && sc.isConnected) {
                        LOG.info("RemoteClaude: [tab $tabId] lazy: found via session fields")
                        return sc
                    }
                }
            } catch (e: Exception) {
                LOG.info("RemoteClaude: [tab $tabId] lazy: session search failed: ${e.message}")
            }
        }

        val accessor = findObjectByClassName(content, "TtyConnectorAccessor", maxDepth = 8)
        if (accessor != null) {
            val future = extractCompletableFuture(accessor, "ttyConnectorFuture")
            if (future != null && future.isDone && !future.isCancelled) {
                try {
                    val result = future.getNow(null)
                    if (result is com.jediterm.terminal.TtyConnector && result.isConnected) {
                        LOG.info("RemoteClaude: [tab $tabId] lazy: found via accessor future!")
                        return result
                    }
                } catch (_: Exception) {}
            }
            val ac = findTtyConnectorInAllFields(accessor, maxDepth = 6)
            if (ac != null && ac.isConnected) {
                LOG.info("RemoteClaude: [tab $tabId] lazy: found via accessor fields")
                return ac
            }
        }

        val cc = findTtyConnectorInAllFields(content, maxDepth = 8)
        if (cc != null && cc.isConnected) {
            LOG.info("RemoteClaude: [tab $tabId] lazy: found via content fields")
            return cc
        }

        LOG.warn("RemoteClaude: [tab $tabId] lazy: TtyConnector NOT FOUND from any root")
        return null
    }

    fun stop() {
        scope.cancel()
        inputSenders.clear()
        tabContents.clear()
        cachedConnectors.clear()
        contentToTabId.clear()
    }

    override fun dispose() {
        stop()
    }

    companion object {
        fun getInstance(project: Project): TerminalTabsWatcher =
            project.getService(TerminalTabsWatcher::class.java)
    }
}

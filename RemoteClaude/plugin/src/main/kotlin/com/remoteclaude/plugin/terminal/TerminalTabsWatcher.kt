package com.remoteclaude.plugin.terminal

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.remoteclaude.plugin.server.*
import kotlinx.coroutines.*
import java.util.concurrent.CompletableFuture

class TerminalTabsWatcher(private val project: Project) {

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

    fun start(server: WsServer) {
        scope.launch {
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("Terminal") ?: return@launch

            val contentManager = toolWindow.contentManager

            contentManager.addContentManagerListener(object : ContentManagerListener {
                override fun contentAdded(event: ContentManagerEvent) {
                    scope.launch { onTabAdded(event.content, server) }
                }

                override fun contentRemoved(event: ContentManagerEvent) {
                    scope.launch { onTabRemoved(event.content, server) }
                }
            })

            // Register already-open tabs
            withContext(Dispatchers.Main) {
                contentManager.contents.forEach { content ->
                    scope.launch { onTabAdded(content, server) }
                }
            }
        }
    }

    private suspend fun onTabAdded(content: com.intellij.ui.content.Content, server: WsServer) {
        val title = content.displayName ?: "Terminal"
        val tabInfo = server.registry.registerTab(title, null)

        contentToTabId[title] = tabInfo.id

        scope.launch(Dispatchers.IO) {
            attachOutputInterceptor(content, tabInfo.id, server)
        }

        server.sessionManager.broadcast(TabAddedMessage(tabInfo))
    }

    private suspend fun onTabRemoved(content: com.intellij.ui.content.Content, server: WsServer) {
        val title = content.displayName ?: return
        val tabId = contentToTabId.remove(title)
            ?: server.registry.removeTabByTitle(title)
            ?: return
        inputSenders.remove(tabId)
        tabContents.remove(tabId)
        cachedConnectors.remove(tabId)
        server.registry.removeTab(tabId)
        server.sessionManager.broadcast(TabRemovedMessage(tabId))
    }

    // ── Main attachment logic ──────────────────────────────────────────────

    private suspend fun attachOutputInterceptor(
        content: com.intellij.ui.content.Content,
        tabId: Int,
        server: WsServer,
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
        // TtyConnector.read() is DESTRUCTIVE: it competes with the terminal emulator for data
        // from the same PTY stream, causing garbled display in the IDE. Editor Document polling
        // reads the already-rendered text without interfering with terminal rendering.
        LOG.info("RemoteClaude: [tab $tabId] Phase 1: trying Editor-based I/O (non-destructive)...")
        for (attempt in 1..5) {
            delay(500)
            val editorSetup = setupEditorBasedIO(content, tabId, server)
            if (editorSetup) {
                LOG.info("RemoteClaude: [tab $tabId] Editor-based I/O active (attempt $attempt)")
                // Upgrade input to TtyConnector.write() when available (write is safe, only read is destructive)
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

        // Phase 2: No Editor — try TtyConnector for full I/O (classic JediTerm terminal)
        // WARNING: TtyConnector.read() is destructive and may cause display corruption.
        // This path is only used when Editor-based I/O is unavailable (older IntelliJ versions).
        LOG.info("RemoteClaude: [tab $tabId] Phase 2: Editor not found, trying TtyConnector for full I/O...")
        dumpWidgetDiagnostics(content, tabId)

        var connector: com.jediterm.terminal.TtyConnector? = null

        for (attempt in 1..5) {
            val component = content.component

            // Strategy 1: method-based search (classic JBTerminalWidget)
            if (component != null) {
                connector = findTtyConnectorByMethod(component)
                if (connector != null) {
                    LOG.info("RemoteClaude: [tab $tabId] found TtyConnector via method search (attempt $attempt)")
                    break
                }
            }

            // Strategy 2: check if async TtyConnector has resolved
            if (asyncConnector.isCompleted) {
                connector = asyncConnector.await()
                LOG.info("RemoteClaude: [tab $tabId] got TtyConnector from async callback")
                break
            }

            // Strategy 3: deep field-based search on AWT component tree
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
            startConnectorIO(tabId, connector, server)
            return
        }

        // Phase 3: fallback — wait for async TtyConnector (up to 60s)
        if (asyncCallbackRegistered) {
            LOG.info("RemoteClaude: [tab $tabId] waiting for async TtyConnector (60s)...")
            connector = withTimeoutOrNull(60_000L) { asyncConnector.await() }
            if (connector != null) {
                startConnectorIO(tabId, connector, server)
                return
            }
        }

        LOG.warn("RemoteClaude: [tab $tabId] ALL I/O strategies failed")
    }

    /**
     * Sets up input sender and output reading loop for a connected TtyConnector.
     */
    private suspend fun startConnectorIO(
        tabId: Int,
        connector: com.jediterm.terminal.TtyConnector,
        server: WsServer,
    ) {
        // Wait for the connector to become connected
        for (attempt in 1..40) {
            if (connector.isConnected) break
            delay(250)
        }
        if (!connector.isConnected) {
            LOG.warn("RemoteClaude: [tab $tabId] TtyConnector never became connected")
            return
        }

        // Cache connector for direct I/O + store as input sender
        cachedConnectors[tabId] = connector
        inputSenders[tabId] = { text ->
            try {
                connector.write(text)
            } catch (e: Exception) {
                LOG.warn("RemoteClaude: [tab $tabId] write failed: ${e.message}")
            }
        }

        // Start output reader
        LOG.info("RemoteClaude: [tab $tabId] starting output reader, connector=${connector.javaClass.name}")
        server.registry.updateTabState(tabId, TabState.RUNNING)
        server.sessionManager.broadcast(TabStateMessage(tabId, TabState.RUNNING))

        val buf = CharArray(4096)
        val ctx = currentCoroutineContext()
        while (ctx.isActive) {
            try {
                if (!connector.isConnected) {
                    delay(500)
                    continue
                }
                val n = connector.read(buf, 0, buf.size)
                if (n > 0) {
                    val data = String(buf, 0, n)
                    server.registry.getBuffer(tabId)?.append(data)

                    val currentState = server.registry.getTabInfo(tabId)?.state
                        ?: TabState.RUNNING
                    val newState = AgentLifecycleMonitor.analyze(data, currentState)

                    if (newState != currentState) {
                        server.registry.updateTabState(tabId, newState)
                        server.sessionManager.broadcast(TabStateMessage(tabId, newState))
                    }
                    server.sessionManager.broadcast(OutputMessage(tabId, data))
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
    }

    // ── Phase 2: Editor-based I/O for IntelliJ 2025.2 block terminal ─────

    /**
     * Sets up output capture via Document polling (not DocumentListener to avoid EDT interference),
     * and input via process outputStream or TerminalInput reflection.
     */
    private suspend fun setupEditorBasedIO(
        content: com.intellij.ui.content.Content,
        tabId: Int,
        server: WsServer,
    ): Boolean {
        // Find EditorComponentImpl in AWT tree (must be on EDT)
        val editorComponent = withContext(Dispatchers.Main) {
            val root = content.component ?: return@withContext null
            findAwtComponentByClassName(root, "EditorComponentImpl")
        }
        if (editorComponent == null) {
            LOG.info("RemoteClaude: [tab $tabId] EditorComponentImpl not found in AWT tree")
            return false
        }
        LOG.info("RemoteClaude: [tab $tabId] found EditorComponentImpl: ${editorComponent.javaClass.name}")

        // Get Editor via getEditor() method
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

        // Output: poll document every 50ms instead of DocumentListener (avoids EDT interference)
        // Reads text + markup highlighters in a single EDT call for consistency,
        // then encodes colors as ANSI escape codes for xterm.js rendering.
        scope.launch {
            var lastStamp = withContext(Dispatchers.Main) { document.modificationStamp }
            var lastText = withContext(Dispatchers.Main) { document.text }

            // Default foreground — text with this color won't get ANSI codes (uses xterm.js default)
            val defaultFg = withContext(Dispatchers.Main) { editor.colorsScheme.defaultForeground }

            // Send initial content (with colors)
            if (lastText.isNotEmpty()) {
                val initialHighlighters = withContext(Dispatchers.Main) {
                    AnsiColorEncoder.getHighlightersForRange(editor, 0, lastText.length)
                }
                val colorized = AnsiColorEncoder.encode(lastText, initialHighlighters, 0, lastText.length, defaultFg)
                server.registry.getBuffer(tabId)?.append(colorized)
                server.sessionManager.broadcast(OutputMessage(tabId, colorized))
                LOG.info("RemoteClaude: [tab $tabId] sent initial document text (${lastText.length} chars, ${initialHighlighters.size} highlighters)")
            }

            while (isActive) {
                delay(50)
                // Read text, stamp, and highlighters in a single EDT call for consistency
                val (currentStamp, currentText) = withContext(Dispatchers.Main) {
                    document.modificationStamp to document.text
                }
                if (currentStamp != lastStamp) {
                    if (currentText.startsWith(lastText)) {
                        val deltaStart = lastText.length
                        val deltaEnd = currentText.length
                        if (deltaStart < deltaEnd) {
                            val highlighters = withContext(Dispatchers.Main) {
                                AnsiColorEncoder.getHighlightersForRange(editor, deltaStart, deltaEnd)
                            }
                            val delta = AnsiColorEncoder.encode(currentText, highlighters, deltaStart, deltaEnd, defaultFg)
                            server.registry.getBuffer(tabId)?.append(delta)
                            server.sessionManager.broadcast(OutputMessage(tabId, delta))
                        }
                    } else {
                        // Document was modified/cleared — send full refresh with colors
                        val highlighters = withContext(Dispatchers.Main) {
                            AnsiColorEncoder.getHighlightersForRange(editor, 0, currentText.length)
                        }
                        val colorized = AnsiColorEncoder.encode(currentText, highlighters, 0, currentText.length, defaultFg)
                        server.registry.getBuffer(tabId)?.clear()
                        server.registry.getBuffer(tabId)?.append(colorized)
                        server.sessionManager.broadcast(
                            OutputMessage(tabId, "\u001b[2J\u001b[H$colorized")
                        )
                    }
                    lastStamp = currentStamp
                    lastText = currentText
                }
            }
        }

        // Store content ref for lazy TtyConnector search at send time
        tabContents[tabId] = content

        // Input: try multiple strategies (UI-based fallback, used only if lazy TtyConnector search fails)
        inputSenders[tabId] = setupInputSender(content, tabId, editor)

        // Mark running
        server.registry.updateTabState(tabId, TabState.RUNNING)
        scope.launch {
            server.sessionManager.broadcast(TabStateMessage(tabId, TabState.RUNNING))
            server.sessionManager.broadcast(OutputMessage(tabId,
                "\r\n--- [RemoteClaude] Editor I/O active for tab $tabId ---\r\n"
            ))
        }

        LOG.info("RemoteClaude: [tab $tabId] Editor-based I/O ready (output: polling, input: see above)")
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

    /**
     * Multi-strategy input setup. Tries (in order):
     * 1. Find Process/PtyProcess -> write to outputStream (handles \n natively)
     * 2. TerminalInput methods that accept String (+ EditorEnter action for \n)
     * 3. TerminalInput.bufferChannel.trySend (+ EditorEnter action for \n)
     * 4. KeyEvent dispatch (+ EditorEnter action for \n)
     */
    private fun setupInputSender(
        content: com.intellij.ui.content.Content,
        tabId: Int,
        editor: com.intellij.openapi.editor.Editor,
    ): (String) -> Unit {
        // Strategy 1: Find Process and write to its outputStream
        // This handles \n natively (goes directly to process stdin)
        val processSender = findProcessSender(content, tabId)
        if (processSender != null) return processSender

        // For strategies 2-4, \n must be dispatched via EditorEnter action
        // because block terminal handles Enter through IntelliJ's action system

        // Strategy 2: TerminalInput — try public/internal methods
        val terminalInput = findObjectByClassName(content, "TerminalInput", maxDepth = 8)
        if (terminalInput != null) {
            LOG.info("RemoteClaude: [tab $tabId] TerminalInput found: ${terminalInput.javaClass.name}")

            // Collect candidate methods, prioritizing sendString (handles \n natively)
            val candidateMethods = mutableListOf<java.lang.reflect.Method>()
            var c: Class<*>? = terminalInput.javaClass
            while (c != null && c != Any::class.java) {
                for (m in c.declaredMethods) {
                    val params = m.parameterTypes.joinToString(", ") { it.simpleName }
                    LOG.info("RemoteClaude: [tab $tabId] TerminalInput method: ${m.name}($params): ${m.returnType.simpleName}")
                    if (m.parameterCount == 1 &&
                        (m.parameterTypes[0] == String::class.java ||
                         m.parameterTypes[0] == CharSequence::class.java)
                    ) {
                        candidateMethods.add(m)
                    }
                }
                c = c.superclass
            }

            // Prioritize: sendString (raw PTY input, handles \n natively) > sendBytes > others
            // sendBracketedString wraps text in escape sequences and can't handle \n as Enter
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
                // sendString/sendBytes handle \n natively (goes directly to PTY)
                if (m.name == "sendString") {
                    LOG.info("RemoteClaude: [tab $tabId] input via TerminalInput.sendString() (native \\n)")
                    return { text -> m.invoke(terminalInput, text) }
                }
                // For other methods, test and wrap with EditorEnter for \n handling
                try {
                    m.invoke(terminalInput, " ")
                    LOG.info("RemoteClaude: [tab $tabId] input via TerminalInput.${m.name}() + EditorEnter")
                    return wrapWithEditorEnter(editor, tabId) { text -> m.invoke(terminalInput, text) }
                } catch (e: Exception) {
                    LOG.info("RemoteClaude: [tab $tabId] TerminalInput.${m.name}() test failed: ${e.message}")
                }
            }

            // Strategy 3: bufferChannel.trySend
            val channelSender = setupBufferChannelSender(terminalInput, tabId)
            if (channelSender != null) return wrapWithEditorEnter(editor, tabId, channelSender)
        } else {
            LOG.info("RemoteClaude: [tab $tabId] TerminalInput NOT found in field graph")
        }

        // Strategy 4: KeyEvent dispatch to editor content component + EditorEnter for \n
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

    /**
     * Wraps a text-typing sender: strips newlines from text, dispatches Enter
     * via EditorActionHandler for each \n (block terminal requires this).
     */
    private fun wrapWithEditorEnter(
        editor: com.intellij.openapi.editor.Editor,
        tabId: Int,
        typeSender: (String) -> Unit,
    ): (String) -> Unit {
        return { text ->
            // Split text on newlines: type each part, dispatch Enter for each \n
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
                // Dispatch Enter for each newline boundary (not after last part unless text ends with \n)
                if (index < parts.size - 1 || endsWithNewline) {
                    dispatchEditorEnter(editor, tabId)
                }
            }
        }
    }

    /**
     * Dispatch Enter via IntelliJ's EditorActionHandler (handles block terminal's
     * custom Enter behavior — command submission instead of newline insertion).
     */
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

    /** Search for a Process (PtyProcess, WinPtyProcess, etc.) and return a sender that writes to its outputStream. */
    private fun findProcessSender(content: com.intellij.ui.content.Content, tabId: Int): ((String) -> Unit)? {
        for (className in listOf("PtyProcess", "WinPtyProcess", "UnixPtyProcess")) {
            val processObj = findObjectByClassName(content, className, maxDepth = 10) ?: continue
            LOG.info("RemoteClaude: [tab $tabId] found $className: ${processObj.javaClass.name}")

            // Try direct Process cast
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

            // Try reflection getOutputStream
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

    /** Try to use TerminalInput.bufferChannel.trySend for input. */
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
                val result = trySendMethod.invoke(bufferChannel, text)
                LOG.info("RemoteClaude: [tab $tabId] bufferChannel.trySend result=$result")
            } catch (e: Exception) {
                LOG.warn("RemoteClaude: [tab $tabId] bufferChannel.trySend failed: ${e.cause?.message ?: e.message}")
                // Try ByteArray as fallback
                try {
                    trySendMethod.invoke(bufferChannel, text.toByteArray(Charsets.UTF_8))
                } catch (e2: Exception) {
                    LOG.warn("RemoteClaude: [tab $tabId] bufferChannel.trySend(ByteArray) also failed: ${e2.cause?.message}")
                }
            }
        }
    }

    /** Dump diagnostics for the widget, outputModel, TerminalInput, TerminalSession. */
    private fun dumpWidgetDiagnostics(content: com.intellij.ui.content.Content, tabId: Int) {
        val widget = findObjectByClassName(content, "ReworkedTerminalWidget", maxDepth = 5)
        if (widget != null) {
            LOG.info("RemoteClaude: [tab $tabId] === Widget dump ===")
            logAllMembersOf(widget, "      widget")

            // Get TerminalSession from widget
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

        val outputModel = findObjectByClassName(content, "OutputModel", maxDepth = 8)
        if (outputModel != null) {
            LOG.info("RemoteClaude: [tab $tabId] === OutputModel dump ===")
            logAllMembersOf(outputModel, "      outputModel")
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

                    // Skip most basic JDK types, but allow Object (generics erase to Object)
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

                    // For Object-typed fields, only recurse if the runtime type is interesting
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

    private fun dumpFieldTree(obj: Any, prefix: String, maxDepth: Int, visited: MutableSet<Int> = mutableSetOf()) {
        if (maxDepth <= 0) return
        val id = System.identityHashCode(obj)
        if (!visited.add(id)) return

        var clazz: Class<*>? = obj.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                try {
                    if (field.type.isPrimitive) continue
                    if (field.type == String::class.java) continue
                    field.isAccessible = true
                    val value = field.get(obj) ?: continue
                    val cn = value.javaClass.name.lowercase()
                    if (shouldFollowForSearch(cn)) {
                        LOG.info("$prefix.${field.name}: ${value.javaClass.name}")
                        dumpFieldTree(value, "$prefix.${field.name}", maxDepth - 1, visited)
                    }
                } catch (_: Exception) {}
            }
            clazz = clazz.superclass
        }
    }

    private fun logAllMembersOf(obj: Any, prefix: String) {
        val clazz = obj.javaClass
        LOG.info("$prefix class: ${clazz.name}")

        // Fields (including values)
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

        // Methods (no-arg, non-void)
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

    /** Dump ALL methods (including those with parameters) for comprehensive API discovery. */
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

    /**
     * Aggressive TtyConnector search from multiple roots:
     * 1. content.component (AWT tree)
     * 2. Widget object → session → deep search
     * 3. TtyConnectorAccessor → future (might be completed by now)
     * 4. Content object itself (field graph)
     */
    private fun lazyFindConnector(
        content: com.intellij.ui.content.Content,
        tabId: Int,
    ): com.jediterm.terminal.TtyConnector? {
        // Root 1: AWT component tree
        val component = content.component
        if (component != null) {
            val c = findTtyConnectorByMethod(component)
                ?: findTtyConnectorDeep(component)
            if (c != null && c.isConnected) {
                LOG.info("RemoteClaude: [tab $tabId] lazy: found via AWT tree")
                return c
            }
        }

        // Root 2: Widget → Session → deep search
        val widget = findObjectByClassName(content, "TerminalWidget", maxDepth = 5)
            ?: findObjectByClassName(content, "ReworkedTerminalWidget", maxDepth = 5)
        if (widget != null) {
            LOG.info("RemoteClaude: [tab $tabId] lazy: searching from widget (${widget.javaClass.name})")
            val c = findTtyConnectorInAllFields(widget, maxDepth = 8)
            if (c != null && c.isConnected) {
                LOG.info("RemoteClaude: [tab $tabId] lazy: found via widget fields")
                return c
            }

            // Try widget.getSession() → search session
            try {
                val getSession = widget.javaClass.methods.find { it.name == "getSession" && it.parameterCount == 0 }
                val session = getSession?.let { m -> m.isAccessible = true; m.invoke(widget) }
                if (session != null) {
                    LOG.info("RemoteClaude: [tab $tabId] lazy: searching from session (${session.javaClass.name})")
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

        // Root 3: TtyConnectorAccessor — future might be completed by now
        val accessor = findObjectByClassName(content, "TtyConnectorAccessor", maxDepth = 8)
        if (accessor != null) {
            val future = extractCompletableFuture(accessor, "ttyConnectorFuture")
            LOG.info("RemoteClaude: [tab $tabId] lazy: accessor future isDone=${future?.isDone}, isCancelled=${future?.isCancelled}")
            if (future != null && future.isDone && !future.isCancelled) {
                try {
                    val result = future.getNow(null)
                    if (result is com.jediterm.terminal.TtyConnector && result.isConnected) {
                        LOG.info("RemoteClaude: [tab $tabId] lazy: found via accessor future!")
                        return result
                    }
                } catch (_: Exception) {}
            }
            // Also search accessor's field graph
            val ac = findTtyConnectorInAllFields(accessor, maxDepth = 6)
            if (ac != null && ac.isConnected) {
                LOG.info("RemoteClaude: [tab $tabId] lazy: found via accessor fields")
                return ac
            }
        }

        // Root 4: Content object itself
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

    companion object {
        fun getInstance(project: Project): TerminalTabsWatcher =
            project.getService(TerminalTabsWatcher::class.java)
    }
}

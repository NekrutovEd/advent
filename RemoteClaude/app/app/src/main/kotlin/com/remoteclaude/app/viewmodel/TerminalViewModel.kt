package com.remoteclaude.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remoteclaude.app.data.ws.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "RC_DEBUG"

data class TerminalUiState(
    val tabs: List<TabInfo> = emptyList(),
    val activeTabId: String? = null,
    val projects: List<ProjectInfo> = emptyList(),
    val plugins: List<PluginInfo> = emptyList(),
    val selectedPluginId: String? = null,
)

class TerminalViewModel(private val wsClient: WsClient) : ViewModel() {

    private val _uiState = MutableStateFlow(TerminalUiState())
    val uiState: StateFlow<TerminalUiState> = _uiState

    val connectionState: StateFlow<WsClient.ConnectionState> = wsClient.connectionState

    // Buffer per tab: tabId (String) -> StringBuilder
    private val buffers = ConcurrentHashMap<String, StringBuilder>()

    // Output flow per tab (for TerminalView to collect)
    private val outputFlows = ConcurrentHashMap<String, MutableSharedFlow<String>>()

    fun outputFlow(tabId: String): SharedFlow<String> =
        outputFlows.getOrPut(tabId) { MutableSharedFlow(extraBufferCapacity = 256) }

    init {
        Log.d(TAG, "TerminalViewModel: init, starting message collector")
        viewModelScope.launch {
            Log.d(TAG, "TerminalViewModel: collector coroutine started, subscribing to wsClient.messages")
            wsClient.messages.collect { message ->
                Log.d(TAG, "TerminalViewModel: collected message type=${message::class.simpleName}")
                handleMessage(message)
            }
        }
        // Register app with server
        viewModelScope.launch {
            wsClient.send(RegisterAppMessage(
                deviceName = android.os.Build.MODEL,
                platform = "android",
            ))
        }
        requestProjects()
    }

    private var pendingBareTerminal = false

    fun launchBareTerminal() {
        val state = _uiState.value
        val selectedPlugin = state.plugins.find { it.pluginId == state.selectedPluginId }
        // Resolve project path: try matching plugin's projectName with known projects,
        // then try extracting from an existing tab of this plugin
        val projectPath = selectedPlugin?.let { plugin ->
            state.projects.find { it.name == plugin.projectName }?.path
                ?: state.tabs.firstOrNull {
                    it.pluginId == plugin.pluginId || it.id.substringBefore(":") == plugin.pluginId
                }?.projectPath
        } ?: state.projects.firstOrNull()?.path

        if (projectPath != null) {
            Log.d(TAG, "TerminalVM: launchBareTerminal for $projectPath (plugin=${selectedPlugin?.pluginId})")
            viewModelScope.launch { wsClient.send(CreateTerminalMessage(projectPath)) }
        } else {
            Log.w(TAG, "TerminalVM: launchBareTerminal - no projects yet, requesting and queuing launch")
            pendingBareTerminal = true
            requestProjects()
        }
    }

    private suspend fun handleMessage(message: WsMessage) {
        when (message) {
            is InitMessage -> {
                Log.d(TAG, "TerminalVM: InitMessage tabs=${message.tabs.map { "id=${it.id},title=${it.title},state=${it.state}" }}, plugins=${message.plugins.size}")
                val firstPlugin = message.plugins.firstOrNull()?.pluginId
                val firstTab = if (firstPlugin != null) {
                    message.tabs.firstOrNull { it.pluginId == firstPlugin || it.id.substringBefore(":") == firstPlugin }
                } else {
                    message.tabs.firstOrNull()
                }
                _uiState.update { it.copy(
                    tabs = message.tabs,
                    activeTabId = firstTab?.id ?: message.tabs.firstOrNull()?.id,
                    plugins = message.plugins,
                    selectedPluginId = firstPlugin,
                ) }
                Log.d(TAG, "TerminalVM: after init, activeTabId=${_uiState.value.activeTabId}, selectedPluginId=$firstPlugin")
            }
            is TabAddedMessage -> {
                Log.d(TAG, "TerminalVM: TabAddedMessage tab.id=${message.tab.id}, title=${message.tab.title}")
                _uiState.update { state ->
                    val newTabs = state.tabs + message.tab
                    val tabPid = message.tab.pluginId.ifEmpty { message.tab.id.substringBefore(":") }
                    val isForSelectedPlugin = state.selectedPluginId == null || tabPid == state.selectedPluginId
                    // Auto-activate if: no active tab, or active tab is from another plugin (empty state visible)
                    val activeTabInSelectedPlugin = state.activeTabId != null && state.selectedPluginId?.let { pid ->
                        state.tabs.any { it.id == state.activeTabId && (it.pluginId == pid || it.id.substringBefore(":") == pid) }
                    } ?: (state.activeTabId != null)
                    val shouldActivate = isForSelectedPlugin && !activeTabInSelectedPlugin
                    state.copy(
                        tabs = newTabs,
                        activeTabId = if (shouldActivate) message.tab.id else state.activeTabId,
                    )
                }
            }
            is TabRemovedMessage -> {
                Log.d(TAG, "TerminalVM: TabRemovedMessage tabId=${message.tabId}")
                _uiState.update { state ->
                    val newTabs = state.tabs.filter { it.id != message.tabId }
                    val selectedPid = state.selectedPluginId
                    // Pick next tab within the same plugin, or null (empty state)
                    val newActiveTabId = if (state.activeTabId == message.tabId) {
                        newTabs.firstOrNull {
                            selectedPid != null && (it.pluginId == selectedPid || it.id.substringBefore(":") == selectedPid)
                        }?.id
                    } else state.activeTabId
                    state.copy(
                        tabs = newTabs,
                        activeTabId = newActiveTabId,
                    )
                }
                buffers.remove(message.tabId)
                outputFlows.remove(message.tabId)
            }
            is TabStateMessage -> {
                Log.d(TAG, "TerminalVM: TabStateMessage tabId=${message.tabId}, state=${message.state}")
                _uiState.update { state ->
                    state.copy(tabs = state.tabs.map { tab ->
                        if (tab.id == message.tabId) tab.copy(state = message.state) else tab
                    })
                }
            }
            is OutputMessage -> {
                Log.d(TAG, "TerminalVM: OutputMessage tabId=${message.tabId}, dataLen=${message.data.length}, preview=${message.data.take(80)}")
                buffers.getOrPut(message.tabId) { StringBuilder() }.append(message.data)
                val flow = outputFlows.getOrPut(message.tabId) { MutableSharedFlow(extraBufferCapacity = 256) }
                val emitted = flow.tryEmit(message.data)
                if (!emitted) {
                    Log.w(TAG, "TerminalVM: tryEmit failed for tabId=${message.tabId}, using suspend emit")
                    flow.emit(message.data)
                }
                Log.d(TAG, "TerminalVM: OutputMessage emitted to flow, subscribers=${flow.subscriptionCount.value}")
            }
            is BufferMessage -> {
                Log.d(TAG, "TerminalVM: BufferMessage tabId=${message.tabId}, dataLen=${message.data.length}, preview=${message.data.take(80)}")
                buffers.getOrPut(message.tabId) { StringBuilder() }.clear()
                buffers[message.tabId]?.append(message.data)
                val flow = outputFlows.getOrPut(message.tabId) { MutableSharedFlow(extraBufferCapacity = 256) }
                val emitted = flow.tryEmit(message.data)
                if (!emitted) {
                    Log.w(TAG, "TerminalVM: BufferMessage tryEmit failed for tabId=${message.tabId}, using suspend emit")
                    flow.emit(message.data)
                }
                Log.d(TAG, "TerminalVM: BufferMessage emitted to flow, subscribers=${flow.subscriptionCount.value}")
            }
            is ProjectsListMessage -> {
                Log.d(TAG, "TerminalVM: ProjectsListMessage count=${message.projects.size}")
                _uiState.update { it.copy(projects = message.projects) }
                if (pendingBareTerminal) {
                    pendingBareTerminal = false
                    val project = message.projects.firstOrNull()
                    if (project != null) {
                        Log.d(TAG, "TerminalVM: executing pending bare terminal launch for ${project.path}")
                        viewModelScope.launch {
                            wsClient.send(CreateTerminalMessage(project.path))
                        }
                    } else {
                        Log.w(TAG, "TerminalVM: pending bare terminal but projects list is still empty")
                    }
                }
            }
            is AgentLaunchedMessage -> {
                Log.d(TAG, "TerminalVM: AgentLaunchedMessage tabId=${message.tabId}, project=${message.projectPath}")
                val extractedPluginId = message.tabId.substringBefore(":")
                val plugin = _uiState.value.plugins.find { it.pluginId == extractedPluginId }
                val newTab = TabInfo(
                    id = message.tabId,
                    title = "claude: ${message.projectPath.split("/").last()}",
                    state = TabState.STARTING,
                    pluginId = extractedPluginId,
                    pluginName = plugin?.projectName ?: extractedPluginId,
                    projectPath = message.projectPath,
                )
                _uiState.update { it.copy(tabs = it.tabs + newTab, activeTabId = message.tabId) }
            }
            is AgentCompletedMessage -> {
                Log.d(TAG, "TerminalVM: AgentCompletedMessage tabId=${message.tabId}, exitCode=${message.exitCode}")
                _uiState.update { state ->
                    state.copy(tabs = state.tabs.map { tab ->
                        if (tab.id == message.tabId) tab.copy(state = TabState.COMPLETED) else tab
                    })
                }
            }
            is ErrorMessage -> {
                Log.e(TAG, "TerminalVM: ErrorMessage: ${message.message}")
            }
            is AgentOutputMessage -> {
                Log.d(TAG, "TerminalVM: AgentOutputMessage tabId=${message.tabId}, dataLen=${message.data.length}, isJson=${message.isJson}")
            }
            else -> {
                Log.d(TAG, "TerminalVM: unhandled message type=${message::class.simpleName}")
            }
        }
    }

    fun selectPlugin(pluginId: String) {
        Log.d(TAG, "TerminalVM: selectPlugin($pluginId)")
        _uiState.update { state ->
            val firstTab = state.tabs.firstOrNull {
                it.pluginId == pluginId || it.id.substringBefore(":") == pluginId
            }
            state.copy(
                selectedPluginId = pluginId,
                activeTabId = firstTab?.id,
            )
        }
    }

    fun switchTab(tabId: String) {
        Log.d(TAG, "TerminalVM: switchTab($tabId), hasBuffer=${buffers[tabId] != null}")
        _uiState.update { it.copy(activeTabId = tabId) }
        if (buffers[tabId] == null) {
            Log.d(TAG, "TerminalVM: requesting buffer for tabId=$tabId")
            viewModelScope.launch { wsClient.send(RequestBufferMessage(tabId)) }
        }
    }

    fun sendInput(tabId: String, text: String) {
        Log.d(TAG, "TerminalVM: sendInput tabId=$tabId, text=\"$text\"")
        viewModelScope.launch { wsClient.send(InputMessage(tabId, "$text\r")) }
    }

    fun sendRawInput(tabId: String, data: String) {
        Log.d(TAG, "TerminalVM: sendRawInput tabId=$tabId, dataLen=${data.length}, data=${data.take(80)}")
        viewModelScope.launch { wsClient.send(InputMessage(tabId, data)) }
    }

    fun requestProjects() {
        Log.d(TAG, "TerminalVM: requestProjects()")
        viewModelScope.launch { wsClient.send(ListProjectsMessage()) }
    }

    fun launchAgent(projectPath: String, mode: AgentMode, prompt: String, allowedTools: List<String> = emptyList()) {
        Log.d(TAG, "TerminalVM: launchAgent project=$projectPath, mode=$mode, prompt=${prompt.take(50)}")
        viewModelScope.launch {
            wsClient.send(LaunchAgentMessage(projectPath, mode, prompt, allowedTools))
        }
    }

    fun createServerTerminal(workingDir: String? = null) {
        Log.d(TAG, "TerminalVM: createServerTerminal workingDir=$workingDir")
        viewModelScope.launch { wsClient.send(CreateServerTerminalMessage(workingDir)) }
    }

    fun launchIde(projectPath: String) {
        Log.d(TAG, "TerminalVM: launchIde projectPath=$projectPath")
        viewModelScope.launch { wsClient.send(LaunchIdeMessage(projectPath)) }
    }

    fun closeTab(tabId: String) {
        Log.d(TAG, "TerminalVM: closeTab tabId=$tabId")
        viewModelScope.launch { wsClient.send(CloseTabMessage(tabId)) }
    }

    fun terminateAgent(tabId: String) {
        Log.d(TAG, "TerminalVM: terminateAgent tabId=$tabId")
        viewModelScope.launch { wsClient.send(TerminateAgentMessage(tabId)) }
    }

    fun getBuffer(tabId: String): String {
        val buf = buffers[tabId]?.toString() ?: ""
        Log.d(TAG, "TerminalVM: getBuffer($tabId) len=${buf.length}")
        return buf
    }
}

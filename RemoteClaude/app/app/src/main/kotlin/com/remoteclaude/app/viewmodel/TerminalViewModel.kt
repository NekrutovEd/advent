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
    val activeTabId: Int? = null,
    val projects: List<ProjectInfo> = emptyList(),
    val isConnected: Boolean = true,
)

class TerminalViewModel(private val wsClient: WsClient) : ViewModel() {

    private val _uiState = MutableStateFlow(TerminalUiState())
    val uiState: StateFlow<TerminalUiState> = _uiState

    // Buffer per tab: tabId -> StringBuilder
    private val buffers = ConcurrentHashMap<Int, StringBuilder>()

    // Output flow per tab (for TerminalView to collect)
    private val outputFlows = ConcurrentHashMap<Int, MutableSharedFlow<String>>()

    fun outputFlow(tabId: Int): SharedFlow<String> =
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
        requestProjects()
    }

    private var pendingBareTerminal = false

    fun launchBareTerminal() {
        val project = _uiState.value.projects.firstOrNull()
        if (project != null) {
            launchAgent(project.path, AgentMode.INTERACTIVE, "")
        } else {
            Log.w(TAG, "TerminalVM: launchBareTerminal - no projects yet, requesting and queuing launch")
            pendingBareTerminal = true
            requestProjects()
        }
    }

    private suspend fun handleMessage(message: WsMessage) {
        when (message) {
            is InitMessage -> {
                Log.d(TAG, "TerminalVM: InitMessage tabs=${message.tabs.map { "id=${it.id},title=${it.title},state=${it.state}" }}")
                _uiState.update { it.copy(tabs = message.tabs, activeTabId = message.tabs.firstOrNull()?.id) }
                Log.d(TAG, "TerminalVM: after init, activeTabId=${_uiState.value.activeTabId}")
            }
            is TabAddedMessage -> {
                Log.d(TAG, "TerminalVM: TabAddedMessage tab.id=${message.tab.id}, title=${message.tab.title}")
                _uiState.update { it.copy(tabs = it.tabs + message.tab) }
                if (_uiState.value.activeTabId == null) {
                    _uiState.update { it.copy(activeTabId = message.tab.id) }
                }
            }
            is TabRemovedMessage -> {
                Log.d(TAG, "TerminalVM: TabRemovedMessage tabId=${message.tabId}")
                _uiState.update { state ->
                    val newTabs = state.tabs.filter { it.id != message.tabId }
                    state.copy(
                        tabs = newTabs,
                        activeTabId = if (state.activeTabId == message.tabId) newTabs.firstOrNull()?.id else state.activeTabId
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
                            wsClient.send(LaunchAgentMessage(project.path, AgentMode.INTERACTIVE, "", emptyList()))
                        }
                    } else {
                        Log.w(TAG, "TerminalVM: pending bare terminal but projects list is still empty")
                    }
                }
            }
            is AgentLaunchedMessage -> {
                Log.d(TAG, "TerminalVM: AgentLaunchedMessage tabId=${message.tabId}, project=${message.projectPath}")
                val newTab = TabInfo(
                    id = message.tabId,
                    title = "claude: ${message.projectPath.split("/").last()}",
                    state = TabState.STARTING,
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

    fun switchTab(tabId: Int) {
        Log.d(TAG, "TerminalVM: switchTab($tabId), hasBuffer=${buffers[tabId] != null}")
        _uiState.update { it.copy(activeTabId = tabId) }
        if (buffers[tabId] == null) {
            Log.d(TAG, "TerminalVM: requesting buffer for tabId=$tabId")
            viewModelScope.launch { wsClient.send(RequestBufferMessage(tabId)) }
        }
    }

    fun sendInput(tabId: Int, text: String) {
        Log.d(TAG, "TerminalVM: sendInput tabId=$tabId, text=\"$text\"")
        viewModelScope.launch { wsClient.send(InputMessage(tabId, "$text\r")) }
    }

    fun sendRawInput(tabId: Int, data: String) {
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

    fun terminateAgent(tabId: Int) {
        Log.d(TAG, "TerminalVM: terminateAgent tabId=$tabId")
        viewModelScope.launch { wsClient.send(TerminateAgentMessage(tabId)) }
    }

    fun getBuffer(tabId: Int): String {
        val buf = buffers[tabId]?.toString() ?: ""
        Log.d(TAG, "TerminalVM: getBuffer($tabId) len=${buf.length}")
        return buf
    }
}

package com.remoteclaude.server.state

import com.remoteclaude.server.protocol.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

class GlobalTabRegistry {

    // globalTabId -> GlobalTabInfo
    private val tabs = ConcurrentHashMap<String, GlobalTabInfo>()
    private val buffers = ConcurrentHashMap<String, TabBuffer>()

    private val _tabsFlow = MutableStateFlow<List<GlobalTabInfo>>(emptyList())
    val tabsFlow: StateFlow<List<GlobalTabInfo>> = _tabsFlow

    /** Live output events: Pair(globalTabId, data) */
    private val _outputFlow = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 256)
    val outputFlow: SharedFlow<Pair<String, String>> = _outputFlow

    fun emitOutput(globalTabId: String, data: String) {
        _outputFlow.tryEmit(globalTabId to data)
    }

    fun registerTab(pluginId: String, pluginName: String, localTab: LocalTabInfo): GlobalTabInfo {
        val globalId = TabNamespace.toGlobal(pluginId, localTab.id)
        val info = GlobalTabInfo(
            id = globalId,
            title = localTab.title,
            state = localTab.state,
            pluginId = pluginId,
            pluginName = pluginName,
            projectPath = localTab.projectPath,
        )
        tabs[globalId] = info
        buffers[globalId] = TabBuffer()
        _tabsFlow.value = tabs.values.toList()
        return info
    }

    fun updateTabState(globalTabId: String, state: TabState, message: String? = null): GlobalTabInfo? {
        val existing = tabs[globalTabId] ?: return null
        val updated = existing.copy(state = state)
        tabs[globalTabId] = updated
        _tabsFlow.value = tabs.values.toList()
        return updated
    }

    fun removeTab(globalTabId: String) {
        tabs.remove(globalTabId)
        buffers.remove(globalTabId)
        _tabsFlow.value = tabs.values.toList()
    }

    fun removeAllTabsForPlugin(pluginId: String): List<String> {
        val removed = tabs.keys.filter { it.startsWith("$pluginId:") }
        removed.forEach { id ->
            tabs.remove(id)
            buffers.remove(id)
        }
        if (removed.isNotEmpty()) {
            _tabsFlow.value = tabs.values.toList()
        }
        return removed
    }

    fun getTab(globalTabId: String): GlobalTabInfo? = tabs[globalTabId]

    fun getBuffer(globalTabId: String): TabBuffer? = buffers[globalTabId]

    fun getAllTabs(): List<GlobalTabInfo> = tabs.values.toList()

    fun getTabsForPlugin(pluginId: String): List<GlobalTabInfo> =
        tabs.values.filter { it.pluginId == pluginId }

    fun count(): Int = tabs.size
}

package com.remoteclaude.plugin.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TabRegistry {

    private val idGen = AtomicInteger(1)
    private val tabs = ConcurrentHashMap<Int, TabInfo>()
    private val buffers = ConcurrentHashMap<Int, TabBuffer>()
    private val lastOutputTime = ConcurrentHashMap<Int, Long>()

    fun registerTab(title: String, projectPath: String?): TabInfo {
        val id = idGen.getAndIncrement()
        val info = TabInfo(id = id, title = title, state = TabState.STARTING, projectPath = projectPath)
        tabs[id] = info
        buffers[id] = TabBuffer()
        return info
    }

    fun updateTabState(tabId: Int, state: TabState, message: String? = null): TabInfo? {
        val existing = tabs[tabId] ?: return null
        val updated = existing.copy(state = state)
        tabs[tabId] = updated
        return updated
    }

    fun touchOutput(tabId: Int) {
        lastOutputTime[tabId] = System.currentTimeMillis()
    }

    /** Find the tab with the most recent output activity */
    fun findMostRecentlyActiveTab(): Int? {
        return lastOutputTime.entries
            .maxByOrNull { it.value }
            ?.key
    }

    fun removeTab(tabId: Int) {
        tabs.remove(tabId)
        buffers.remove(tabId)
        lastOutputTime.remove(tabId)
    }

    fun removeTabByTitle(title: String): Int? {
        val entry = tabs.entries.find { it.value.title == title } ?: return null
        val tabId = entry.key
        removeTab(tabId)
        return tabId
    }

    fun getBuffer(tabId: Int): TabBuffer? = buffers[tabId]

    fun getLastOutputTime(tabId: Int): Long? = lastOutputTime[tabId]

    fun getAllTabs(): List<TabInfo> = tabs.values.toList()

    fun getTabInfo(tabId: Int): TabInfo? = tabs[tabId]
}

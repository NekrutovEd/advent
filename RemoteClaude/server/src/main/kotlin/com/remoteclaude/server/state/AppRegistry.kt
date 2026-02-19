package com.remoteclaude.server.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

data class AppEntry(
    val sessionId: String,
    val deviceName: String,
    val platform: String,
)

class AppRegistry {

    private val apps = ConcurrentHashMap<String, AppEntry>()
    private val _appsFlow = MutableStateFlow<List<AppEntry>>(emptyList())
    val appsFlow: StateFlow<List<AppEntry>> = _appsFlow

    fun register(sessionId: String, deviceName: String, platform: String): AppEntry {
        val entry = AppEntry(sessionId, deviceName, platform)
        apps[sessionId] = entry
        _appsFlow.value = apps.values.toList()
        return entry
    }

    fun unregister(sessionId: String) {
        apps.remove(sessionId)
        _appsFlow.value = apps.values.toList()
    }

    fun getAll(): List<AppEntry> = apps.values.toList()

    fun count(): Int = apps.size
}

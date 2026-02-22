package com.remoteclaude.server.state

import com.remoteclaude.server.protocol.PluginInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

data class PluginEntry(
    val pluginId: String,
    val ideName: String,
    val projectName: String,
    val hostname: String,
    val projectPath: String = "",
    val ideHomePath: String = "",
)

class PluginRegistry {

    private val plugins = ConcurrentHashMap<String, PluginEntry>()
    private val _pluginsFlow = MutableStateFlow<List<PluginEntry>>(emptyList())
    val pluginsFlow: StateFlow<List<PluginEntry>> = _pluginsFlow

    fun register(pluginId: String, ideName: String, projectName: String, hostname: String, projectPath: String = "", ideHomePath: String = ""): PluginEntry {
        val entry = PluginEntry(pluginId, ideName, projectName, hostname, projectPath, ideHomePath)
        plugins[pluginId] = entry
        _pluginsFlow.value = plugins.values.toList()
        return entry
    }

    fun unregister(pluginId: String) {
        plugins.remove(pluginId)
        _pluginsFlow.value = plugins.values.toList()
    }

    fun get(pluginId: String): PluginEntry? = plugins[pluginId]

    fun getAll(): List<PluginEntry> = plugins.values.toList()

    fun count(): Int = plugins.size

    fun toPluginInfoList(tabRegistry: GlobalTabRegistry): List<PluginInfo> =
        plugins.values.map { entry ->
            PluginInfo(
                pluginId = entry.pluginId,
                ideName = entry.ideName,
                projectName = entry.projectName,
                hostname = entry.hostname,
                tabCount = tabRegistry.getTabsForPlugin(entry.pluginId).size,
                connected = true,
                projectPath = entry.projectPath,
                ideHomePath = entry.ideHomePath,
            )
        }
}

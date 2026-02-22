package com.remoteclaude.server.state

import com.remoteclaude.server.config.KnownPlugin
import com.remoteclaude.server.config.KnownPluginStore
import java.util.concurrent.ConcurrentHashMap

class KnownPluginRegistry {

    private val plugins = ConcurrentHashMap<String, KnownPlugin>()  // projectPath -> KnownPlugin

    fun loadFromDisk() {
        for (p in KnownPluginStore.load()) {
            if (p.projectPath.isNotEmpty()) {
                plugins[p.projectPath] = p
            }
        }
    }

    fun saveToDisk() {
        KnownPluginStore.save(plugins.values.toList())
    }

    fun upsert(plugin: KnownPlugin) {
        if (plugin.projectPath.isEmpty()) return
        plugins[plugin.projectPath] = plugin
    }

    fun getAll(): List<KnownPlugin> = plugins.values.toList()

    fun get(projectPath: String): KnownPlugin? = plugins[projectPath]

    fun getDisconnectedPaths(connectedPaths: Set<String>): List<KnownPlugin> =
        plugins.values.filter { it.projectPath !in connectedPaths }
}

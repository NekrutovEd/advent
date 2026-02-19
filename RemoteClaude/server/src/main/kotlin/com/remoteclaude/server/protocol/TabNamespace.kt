package com.remoteclaude.server.protocol

object TabNamespace {
    fun toGlobal(pluginId: String, localTabId: Int): String = "$pluginId:$localTabId"

    fun fromGlobal(globalTabId: String): Pair<String, Int> {
        val sep = globalTabId.lastIndexOf(':')
        require(sep > 0) { "Invalid global tab ID: $globalTabId" }
        val pluginId = globalTabId.substring(0, sep)
        val localTabId = globalTabId.substring(sep + 1).toInt()
        return pluginId to localTabId
    }
}

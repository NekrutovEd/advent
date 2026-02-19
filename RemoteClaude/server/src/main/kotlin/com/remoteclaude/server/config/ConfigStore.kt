package com.remoteclaude.server.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class PersistedConfig(
    val port: Int = 8765,
    val enableMdns: Boolean = true,
    val enableUdpBroadcast: Boolean = true,
)

object ConfigStore {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val configFile = File(System.getProperty("user.home"), ".remoteclaude-server.json")

    fun load(): ServerConfig {
        if (!configFile.exists()) return ServerConfig()
        return try {
            val persisted = json.decodeFromString<PersistedConfig>(configFile.readText())
            ServerConfig(
                port = persisted.port,
                enableMdns = persisted.enableMdns,
                enableUdpBroadcast = persisted.enableUdpBroadcast,
            )
        } catch (e: Exception) {
            ServerConfig()
        }
    }

    fun save(config: ServerConfig) {
        try {
            val persisted = PersistedConfig(
                port = config.port,
                enableMdns = config.enableMdns,
                enableUdpBroadcast = config.enableUdpBroadcast,
            )
            configFile.writeText(json.encodeToString(PersistedConfig.serializer(), persisted))
        } catch (e: Exception) {
            // Silently ignore write failures
        }
    }
}

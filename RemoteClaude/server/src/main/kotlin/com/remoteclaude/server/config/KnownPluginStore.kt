package com.remoteclaude.server.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class KnownPlugin(
    val projectPath: String,
    val projectName: String,
    val hostname: String,
    val ideName: String,
    val ideHomePath: String = "",
    val lastSeenMs: Long = 0,
    val metadata: Map<String, String> = emptyMap(),
)

object KnownPluginStore {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(System.getProperty("user.home"), ".remoteclaude-plugins.json")

    fun load(): List<KnownPlugin> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<List<KnownPlugin>>(file.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(plugins: List<KnownPlugin>) {
        try {
            file.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(KnownPlugin.serializer()), plugins))
        } catch (_: Exception) {
            // Silently ignore write failures
        }
    }
}

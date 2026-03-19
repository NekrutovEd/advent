import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import api.ChatApi
import indexing.RagEngine
import mcp.McpClient
import state.AppState
import storage.FileStorageManager
import ui.App
import java.io.File

fun main() = application {
    val windowState = rememberWindowState(width = 1100.dp, height = 700.dp)
    val appState = remember {
        val storageDir = System.getProperty("user.home") + "/.ai-advent"
        val fileKeys = loadApiKeysFromFile(storageDir)

        // Resolve OpenAI API key for embeddings (RAG)
        val openaiKey = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
            ?: fileKeys["OPENAI_API_KEY"] ?: ""

        val ragEngine = if (openaiKey.isNotBlank()) {
            RagEngine(apiKey = openaiKey)
        } else null

        AppState(
            ChatApi(),
            FileStorageManager(storageDir),
            mcpClientFactory = { McpClient() },
            ragProvider = ragEngine
        ).also {
            it.settings.apiConfigs.forEach { config ->
                val envName = "${config.id.uppercase()}_API_KEY"
                config.apiKey = System.getenv(envName)?.takeIf { k -> k.isNotBlank() }
                    ?: fileKeys[envName] ?: ""
            }
        }
    }

    Window(
        onCloseRequest = { appState.saveToStorage(); exitApplication() },
        title = "Day 22 — RAG: Retrieval-Augmented Generation",
        state = windowState
    ) {
        // Auto-load RAG index on startup
        LaunchedEffect(Unit) {
            val indexFile = File("document-index.json")
            if (indexFile.exists() && appState.ragProvider != null) {
                try {
                    appState.ragProvider.loadIndex(indexFile.absolutePath)
                } catch (e: Exception) {
                    System.err.println("Failed to load RAG index: ${e.message}")
                }
            }
        }

        App(appState)
    }
}

/**
 * Reads API keys from ~/.ai-advent/api-keys.properties.
 * Format: KEY_NAME=value (one per line, # for comments).
 * Environment variables take precedence over this file.
 */
private fun loadApiKeysFromFile(dir: String): Map<String, String> {
    val file = File(dir, "api-keys.properties")
    if (!file.exists()) return emptyMap()
    return file.readLines()
        .filter { it.contains("=") && !it.trimStart().startsWith("#") }
        .associate { line ->
            val idx = line.indexOf("=")
            line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }
}

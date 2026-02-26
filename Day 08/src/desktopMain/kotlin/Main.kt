import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import api.ChatApi
import state.AppState
import storage.FileStorageManager
import ui.App

fun main() = application {
    val windowState = rememberWindowState(width = 1100.dp, height = 700.dp)
    val appState = remember {
        AppState(
            ChatApi(),
            FileStorageManager(System.getProperty("user.home") + "/.ai-advent")
        ).also {
            it.settings.apiConfigs[0].apiKey = System.getenv("OPENAI_API_KEY") ?: ""
        }
    }

    Window(
        onCloseRequest = { appState.saveToStorage(); exitApplication() },
        title = "Day 07 — Multi-Session Chat",
        state = windowState
    ) {
        App(appState)
    }
}

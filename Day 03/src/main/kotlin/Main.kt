import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import state.AppState
import ui.App

fun main() = application {
    val windowState = rememberWindowState(width = 1100.dp, height = 700.dp)
    val appState = remember { AppState() }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Day 02 — Dual Chat Comparison",
        state = windowState
    ) {
        App(appState)
    }
}

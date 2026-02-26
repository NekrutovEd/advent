package dev.aiadvent.day03

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import api.ChatApi
import state.AppState
import storage.FileStorageManager
import ui.App

class MainActivity : ComponentActivity() {
    private val appState by lazy {
        AppState(
            ChatApi(),
            FileStorageManager(applicationContext.filesDir.absolutePath)
        ).also { state ->
            if (BuildConfig.API_KEY.isNotEmpty()) {
                state.settings.apiConfigs[0].apiKey = BuildConfig.API_KEY
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appState.loadFromStorage()
        enableEdgeToEdge()
        setContent {
            App(appState)
        }
    }

    override fun onStop() {
        super.onStop()
        appState.saveToStorage()
    }
}

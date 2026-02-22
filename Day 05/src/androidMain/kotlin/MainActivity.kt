package dev.aiadvent.day03

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import api.ChatApi
import state.AppState
import ui.App

class MainActivity : ComponentActivity() {
    private val appState by lazy {
        AppState(ChatApi()).also { state ->
            if (BuildConfig.API_KEY.isNotEmpty()) {
                state.settings.apiKey = BuildConfig.API_KEY
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            App(appState)
        }
    }
}

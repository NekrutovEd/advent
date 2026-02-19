package com.remoteclaude.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF569CD6),
    secondary = Color(0xFF4EC9B0),
    background = Color(0xFF1E1E1E),
    surface = Color(0xFF252526),
    surfaceVariant = Color(0xFF2D2D30),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0066B8),
    secondary = Color(0xFF007ACC),
)

@Composable
fun RemoteClaudeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        content = content,
    )
}

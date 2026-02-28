package ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable

@Composable
internal actual fun InfoTooltip(
    tooltipContent: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    var showTooltip by remember { mutableStateOf(false) }
    Box {
        Box(androidx.compose.ui.Modifier.clickable { showTooltip = true }) {
            content()
        }
        DropdownMenu(
            expanded = showTooltip,
            onDismissRequest = { showTooltip = false }
        ) {
            tooltipContent()
        }
    }
}

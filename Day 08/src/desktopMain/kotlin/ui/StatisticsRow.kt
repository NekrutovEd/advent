package ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import i18n.LocalStrings
import state.ChatState

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal actual fun StatisticsRow(chatState: ChatState) {
    val s = LocalStrings.current
    TooltipArea(
        tooltip = {
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 4.dp,
                shadowElevation = 4.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    val usage = chatState.lastUsage
                    if (usage != null) {
                        Text(s.lastRequest, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        TooltipEntry(usage.promptTokens, s.promptTokens, s.promptTokensDesc)
                        TooltipEntry(usage.completionTokens, s.completionTokens, s.completionTokensDesc)
                        TooltipEntry(usage.totalTokens, s.totalTokens, s.totalTokensDesc)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                    Text(s.sessionTotal, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    TooltipEntry(chatState.totalPromptTokens, s.promptTokens, s.allPromptTokensDesc)
                    TooltipEntry(chatState.totalCompletionTokens, s.completionTokens, s.allCompletionTokensDesc)
                    TooltipEntry(chatState.totalTokens, s.totalTokens, s.allTotalTokensDesc)
                }
            }
        },
        delayMillis = 300
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            val usage = chatState.lastUsage
            if (usage != null) {
                Text(
                    text = s.lastStatsLine(usage.promptTokens, usage.completionTokens, usage.totalTokens),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = s.totalStatsLine(chatState.totalPromptTokens, chatState.totalCompletionTokens, chatState.totalTokens),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun TooltipEntry(value: Int, label: String, description: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        text = description,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
}

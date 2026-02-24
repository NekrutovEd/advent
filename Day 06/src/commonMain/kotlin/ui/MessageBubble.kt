package ui

import api.ChatMessage
import api.RequestSnapshot
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import i18n.LocalStrings

@Composable
fun MessageBubble(message: ChatMessage) {
    if (message.role == "summary") {
        SummaryBubble(message.content)
        return
    }

    val isUser = message.role == "user"
    val snapshot = message.requestSnapshot
    var jsonExpanded by remember { mutableStateOf(false) }

    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bgColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = alignment
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = bgColor,
            modifier = Modifier.widthIn(max = 500.dp)
        ) {
            Column {
                // Top row: expand button at top-right (only for user messages with snapshot)
                if (isUser && snapshot != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = if (jsonExpanded) "{ ▴ }" else "{ }",
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.4f),
                            modifier = Modifier
                                .clickable { jsonExpanded = !jsonExpanded }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                // JSON panel inside the bubble (when expanded)
                if (isUser && snapshot != null && jsonExpanded) {
                    RequestJsonPanel(
                        snapshot = snapshot,
                        modifier = Modifier.padding(horizontal = 6.dp).padding(bottom = 6.dp)
                    )
                }

                // Message text
                SelectionContainer {
                    Text(
                        text = message.content,
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(
                            start = 12.dp, end = 12.dp,
                            top = if (isUser && snapshot != null) 2.dp else 12.dp,
                            bottom = 12.dp
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun RequestJsonPanel(snapshot: RequestSnapshot, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    var freshExpanded by remember { mutableStateOf(false) }
    var historyExpanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {

            // Parameters block (model, temperature, etc.)
            SelectionContainer {
                Text(
                    text = snapshot.metaJson,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "\"messages\": [",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline
            )

            // Fresh summarization block — always visible but collapsible
            if (snapshot.freshSummaryJson != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { freshExpanded = !freshExpanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = s.freshSummaryLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = if (freshExpanded) "▴" else "▾",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (freshExpanded) {
                    SelectionContainer {
                        Text(
                            text = snapshot.freshSummaryJson + ",",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Prior history block (collapsible, default closed)
            if (snapshot.historyCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { historyExpanded = !historyExpanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = s.requestHistoryLabel(snapshot.historyCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (historyExpanded) "▴" else "▾",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (historyExpanded) {
                    SelectionContainer {
                        Text(
                            text = snapshot.historyJson + ",",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Current message (always visible)
            SelectionContainer {
                Text(
                    text = snapshot.currentJson,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "]",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun SummaryBubble(content: String) {
    val s = LocalStrings.current
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = s.summaryLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                text = if (expanded) "[-]" else "[+]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        if (expanded) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
        HorizontalDivider()
    }
}

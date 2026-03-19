package ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import state.ModelInfo
import state.ModelInfoProvider

@Composable
internal expect fun InfoTooltip(
    tooltipContent: @Composable () -> Unit,
    content: @Composable () -> Unit
)

@Composable
internal fun ModelInfoIcon(modelId: String) {
    val info = ModelInfoProvider.get(modelId) ?: return
    InfoTooltip(
        tooltipContent = { ModelInfoCard(modelId, info) }
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = "Model info",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun ModelInfoCard(modelId: String, info: ModelInfo) {
    Column(modifier = Modifier.padding(12.dp).widthIn(max = 280.dp)) {
        Text(
            text = modelId,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        ModelInfoRow("Provider", info.provider)
        ModelInfoRow("Context", "${ModelInfoProvider.formatContext(info.contextTokens)} tokens")
        if (info.params != null) {
            ModelInfoRow("Parameters", info.params)
        }
        ModelInfoRow("Category", info.category)
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        Text(
            text = info.description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ModelInfoRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

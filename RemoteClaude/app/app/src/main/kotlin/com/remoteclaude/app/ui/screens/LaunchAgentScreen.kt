package com.remoteclaude.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.remoteclaude.app.data.ws.AgentMode
import com.remoteclaude.app.data.ws.ProjectInfo
import com.remoteclaude.app.viewmodel.TerminalViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaunchAgentScreen(
    viewModel: TerminalViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    var selectedProject by remember { mutableStateOf<ProjectInfo?>(null) }
    var prompt by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(AgentMode.INTERACTIVE) }

    LaunchedEffect(Unit) { viewModel.requestProjects() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Agent") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        modifier = modifier,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("Project", style = MaterialTheme.typography.titleMedium)
            }

            if (uiState.projects.isEmpty()) {
                item {
                    Text(
                        "Loading projects...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(uiState.projects) { project ->
                val isSelected = project == selectedProject
                Card(
                    onClick = { selectedProject = project },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                         else MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(project.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            project.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Text("Mode", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == AgentMode.INTERACTIVE,
                        onClick = { mode = AgentMode.INTERACTIVE },
                        label = { Text("Interactive") },
                    )
                    FilterChip(
                        selected = mode == AgentMode.BATCH,
                        onClick = { mode = AgentMode.BATCH },
                        label = { Text("Batch") },
                    )
                }
            }

            item {
                Text("Prompt", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("What should Claude do?") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                )
            }

            item {
                Button(
                    onClick = {
                        selectedProject?.let { proj ->
                            viewModel.launchAgent(proj.path, mode, prompt)
                            onBack()
                        }
                    },
                    enabled = selectedProject != null && prompt.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Launch Agent")
                }
            }
        }
    }
}

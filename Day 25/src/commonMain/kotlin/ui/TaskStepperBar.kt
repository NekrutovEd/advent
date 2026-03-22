package ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import i18n.LocalStrings
import state.TaskMemory
import state.TaskPhase
import state.TaskTracker

@Composable
fun TaskStepperBar(
    taskTracker: TaskTracker,
    taskMemory: TaskMemory? = null,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    val phase = taskTracker.phase

    val hasTaskMemory = taskMemory != null && (
        taskMemory.goal != null ||
        taskMemory.clarifications.isNotEmpty() ||
        taskMemory.constraints.isNotEmpty() ||
        taskMemory.coveredTopics.isNotEmpty() ||
        taskMemory.isExtracting
    )
    if (phase == TaskPhase.IDLE && !taskTracker.isExtracting && !hasTaskMemory) return

    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        // Only show phase stepper UI when not in IDLE (skip for pure Q&A with task memory only)
        val showPhaseStepper = phase != TaskPhase.IDLE || taskTracker.isExtracting

        // Task description + pause button
        if (showPhaseStepper) Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (taskTracker.taskDescription.isNotBlank()) {
                Text(
                    text = taskTracker.taskDescription,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }

            if (taskTracker.isExtracting) {
                Text(
                    text = s.taskExtracting,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.outline,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            // Pause/Resume button
            if (phase != TaskPhase.IDLE && phase != TaskPhase.DONE) {
                TextButton(
                    onClick = {
                        if (taskTracker.isPaused) taskTracker.resume()
                        else taskTracker.pause()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (taskTracker.isPaused) s.taskResume else s.taskPause,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // Reset button
            if (phase != TaskPhase.IDLE) {
                TextButton(
                    onClick = { taskTracker.reset() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        s.taskReset,
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.outline
                    )
                }
            }
        }

        // Horizontal phase stepper
        if (showPhaseStepper) Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val phases = listOf(TaskPhase.PLANNING, TaskPhase.EXECUTION, TaskPhase.VALIDATION, TaskPhase.DONE)
            val labels = listOf(s.taskPlanning, s.taskExecution, s.taskValidation, s.taskDone)

            phases.forEachIndexed { index, p ->
                val isCurrent = p == phase
                val isCompleted = phase.ordinal > p.ordinal
                val isPaused = isCurrent && taskTracker.isPaused

                PhaseChip(
                    label = labels[index],
                    isCurrent = isCurrent,
                    isCompleted = isCompleted,
                    isPaused = isPaused,
                    modifier = Modifier.weight(1f)
                )

                if (index < phases.size - 1) {
                    val lineColor = if (isCompleted) colorScheme.primary else colorScheme.outlineVariant
                    Box(
                        modifier = Modifier
                            .height(2.dp)
                            .width(12.dp)
                            .background(lineColor)
                    )
                }
            }
        }

        // Sub-steps (collapsible detail)
        if (showPhaseStepper) AnimatedVisibility(
            visible = taskTracker.steps.isNotEmpty() && phase != TaskPhase.IDLE,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
            ) {
                taskTracker.steps.forEachIndexed { index, step ->
                    val isCurrent = index == taskTracker.currentStepIndex && !step.completed
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 1.dp)
                    ) {
                        val icon = when {
                            step.completed -> "\u2713"
                            isCurrent -> "\u25B6"
                            else -> "\u25CB"
                        }
                        val iconColor = when {
                            step.completed -> colorScheme.primary
                            isCurrent -> colorScheme.tertiary
                            else -> colorScheme.outline
                        }
                        Text(
                            text = icon,
                            color = iconColor,
                            fontSize = 10.sp,
                            modifier = Modifier.width(16.dp)
                        )
                        Text(
                            text = step.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (step.completed) colorScheme.outline else colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Phase action label
                val actionLabel = when (phase) {
                    TaskPhase.PLANNING -> s.taskPlanning + "..."
                    TaskPhase.EXECUTION -> s.taskExecution + "..."
                    TaskPhase.VALIDATION -> s.taskValidation + "..."
                    TaskPhase.DONE -> s.taskDone
                    TaskPhase.IDLE -> ""
                }
                if (actionLabel.isNotBlank()) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.tertiary,
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Transition blocked banner
        val rejection = taskTracker.lastRejection
        if (showPhaseStepper && rejection != null) {
            Surface(
                color = colorScheme.errorContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = s.taskTransitionBlocked(rejection.current.name, rejection.attempted.name),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { taskTracker.dismissRejection() },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text("✕", style = MaterialTheme.typography.labelSmall, color = colorScheme.error)
                    }
                }
            }
        }

        // Paused banner
        if (showPhaseStepper && taskTracker.isPaused) {
            Surface(
                color = colorScheme.errorContainer.copy(alpha = 0.3f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
            ) {
                Text(
                    text = s.taskPausedBanner,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.error,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        // Task memory section (Day 25)
        if (taskMemory != null) {
            val hasMemory = taskMemory.goal != null ||
                taskMemory.clarifications.isNotEmpty() ||
                taskMemory.constraints.isNotEmpty() ||
                taskMemory.coveredTopics.isNotEmpty()

            if (hasMemory || taskMemory.isExtracting) {
                var memoryExpanded by remember { mutableStateOf(false) }
                Surface(
                    color = colorScheme.tertiaryContainer.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .let { if (hasMemory) it.clickable { memoryExpanded = !memoryExpanded } else it },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = s.taskMemoryLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onTertiaryContainer
                                )
                                if (taskMemory.isExtracting) {
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = s.taskExtracting,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colorScheme.outline
                                    )
                                }
                            }
                            if (hasMemory) {
                                Text(
                                    text = if (memoryExpanded) "\u25B4" else "\u25BE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onTertiaryContainer
                                )
                            }
                        }
                        AnimatedVisibility(visible = memoryExpanded && hasMemory) {
                            Column(modifier = Modifier.padding(top = 2.dp)) {
                                if (taskMemory.goal != null) {
                                    Text(
                                        text = "${s.taskMemoryGoal}: ${taskMemory.goal}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colorScheme.primary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (taskMemory.clarifications.isNotEmpty()) {
                                    Text(
                                        text = "${s.taskMemoryClarifications}: ${taskMemory.clarifications.joinToString("; ")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colorScheme.onTertiaryContainer,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (taskMemory.constraints.isNotEmpty()) {
                                    Text(
                                        text = "${s.taskMemoryConstraints}: ${taskMemory.constraints.joinToString("; ")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colorScheme.onTertiaryContainer,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (taskMemory.coveredTopics.isNotEmpty()) {
                                    Text(
                                        text = "${s.taskMemoryCovered}: ${taskMemory.coveredTopics.joinToString("; ")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colorScheme.outline,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhaseChip(
    label: String,
    isCurrent: Boolean,
    isCompleted: Boolean,
    isPaused: Boolean,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    val bgColor = when {
        isPaused -> colorScheme.errorContainer.copy(alpha = 0.5f)
        isCurrent -> colorScheme.primaryContainer
        isCompleted -> colorScheme.primary.copy(alpha = 0.15f)
        else -> colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    val textColor = when {
        isPaused -> colorScheme.error
        isCurrent -> colorScheme.onPrimaryContainer
        isCompleted -> colorScheme.primary
        else -> colorScheme.outline
    }

    val dotColor = when {
        isPaused -> colorScheme.error
        isCompleted -> colorScheme.primary
        isCurrent -> colorScheme.primary
        else -> colorScheme.outline
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

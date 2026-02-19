package com.remoteclaude.plugin.orchestration

import com.intellij.openapi.project.ProjectManager
import com.remoteclaude.plugin.server.ProjectInfo
import com.remoteclaude.plugin.settings.RemoteClaudeSettings
import java.io.File

class ProjectRegistry {

    fun allProjects(): List<ProjectInfo> {
        val open = openProjects()
        val saved = savedProjects()
        return (open + saved).distinctBy { it.path }
    }

    private fun openProjects(): List<ProjectInfo> =
        ProjectManager.getInstance().openProjects.mapNotNull { p ->
            val path = p.basePath ?: return@mapNotNull null
            ProjectInfo(
                path = path,
                name = p.name,
                hasGit = File(path, ".git").exists(),
            )
        }

    private fun savedProjects(): List<ProjectInfo> =
        RemoteClaudeSettings.getInstance().state.savedProjectPaths.map { path ->
            ProjectInfo(
                path = path,
                name = File(path).name,
                hasGit = File(path, ".git").exists(),
            )
        }

    fun addProject(path: String) {
        val settings = RemoteClaudeSettings.getInstance()
        if (!settings.state.savedProjectPaths.contains(path)) {
            settings.state.savedProjectPaths.add(path)
        }
    }

    companion object {
        fun getInstance() = ProjectRegistry()
    }
}

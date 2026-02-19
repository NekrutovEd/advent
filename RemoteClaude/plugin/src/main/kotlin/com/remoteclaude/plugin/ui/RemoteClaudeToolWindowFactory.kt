package com.remoteclaude.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class RemoteClaudeToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val remoteClaudeWindow = RemoteClaudeToolWindow(project)
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(
            remoteClaudeWindow.getContent(),
            "",
            false
        )

        // Dispose the window when content is removed
        content.setDisposer { remoteClaudeWindow.dispose() }

        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

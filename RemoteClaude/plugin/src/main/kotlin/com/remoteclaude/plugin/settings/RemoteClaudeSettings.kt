package com.remoteclaude.plugin.settings

import com.intellij.openapi.components.*

@Service(Service.Level.APP)
@State(name = "RemoteClaudeSettings", storages = [Storage("remoteclaude.xml")])
class RemoteClaudeSettings : PersistentStateComponent<RemoteClaudeSettings.State> {

    data class State(
        var port: Int = 8765,
        var savedProjectPaths: MutableList<String> = mutableListOf(),
        var ntfyTopic: String = "",
        var enablePatternMatching: Boolean = true,
    )

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }

    companion object {
        fun getInstance(): RemoteClaudeSettings =
            service<RemoteClaudeSettings>()
    }
}

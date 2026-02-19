package com.remoteclaude.plugin.terminal

import com.remoteclaude.plugin.server.TabState

object AgentLifecycleMonitor {
    private val WAITING_TOOL = listOf(
        Regex("""Allow .+ tool\?"""),
        Regex("""Run bash command\?"""),
        Regex("""Write to file\?"""),
        Regex("""Create file\?"""),
        Regex("""Edit file\?"""),
    )
    private val WAITING_INPUT = listOf(
        Regex("""❯\s*$""", RegexOption.MULTILINE),
        Regex("""Do you want to"""),
        Regex("""\(y/n\)""", RegexOption.IGNORE_CASE),
        Regex("""Human turn"""),
        Regex("""Press Enter"""),
        Regex("""Press any key"""),
    )
    private val STRIP_ANSI = Regex("""\x1B\[[0-9;]*[mABCDEFGHJKSTf]""")

    fun analyze(output: String, currentState: TabState): TabState {
        val clean = STRIP_ANSI.replace(output, "")
        return when {
            WAITING_TOOL.any { it.containsMatchIn(clean) } -> TabState.WAITING_TOOL
            WAITING_INPUT.any { it.containsMatchIn(clean) } -> TabState.WAITING_INPUT
            clean.isNotBlank() -> TabState.RUNNING
            else -> currentState
        }
    }
}

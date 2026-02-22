package com.remoteclaude.server.terminal

import com.remoteclaude.server.protocol.TabState

/**
 * Analyzes terminal output to detect state transitions.
 *
 * Two modes:
 * - [analyzeChunk]: called on each output chunk for immediate detection (WAITING_TOOL)
 * - [analyzeIdle]: called after idle timeout on buffer tail (WAITING_INPUT from prompts)
 */
object OutputAnalyzer {

    private val WAITING_TOOL_PATTERNS = listOf(
        Regex("""Allow .+ tool\?"""),
        Regex("""Run bash command\?"""),
        Regex("""Write to file\?"""),
        Regex("""Create file\?"""),
        Regex("""Edit file\?"""),
    )

    private val WAITING_INPUT_PATTERNS = listOf(
        // Claude Code / AI agent prompts
        Regex("""❯\s*$""", RegexOption.MULTILINE),
        Regex("""Do you want to"""),
        Regex("""\(y/n\)""", RegexOption.IGNORE_CASE),
        Regex("""\(yes/no\)""", RegexOption.IGNORE_CASE),
        Regex("""Human turn"""),
        Regex("""Press Enter"""),
        Regex("""Press any key"""),
    )

    // Matches most ANSI escape sequences: CSI, OSC, simple escapes
    private val STRIP_ANSI = Regex("""\x1B\[[0-9;]*[a-zA-Z]|\x1B\][^\x07\x1B]*(?:\x07|\x1B\\)|\x1B[()][0-9A-B]""")

    // Shell prompt patterns — checked only on the LAST line of the buffer during idle
    private val SHELL_PROMPT_PATTERNS = listOf(
        Regex("""\$\s*$"""),                              // bash: user@host:~$
        Regex("""#\s*$"""),                               // root: user@host:~#
        Regex("""❯\s*$"""),                               // starship / claude code
        Regex(""">\s*$"""),                                // zsh / fish / generic
        Regex("""PS [A-Za-z]:\\.*>\s*$"""),                // PowerShell: PS C:\Users>
        Regex("""%\s*$"""),                                // zsh default
        Regex(""">>>\s*$"""),                              // Python REPL
        Regex("""\.{3}\s*$"""),                            // Python continuation ...
        Regex("""\]\$\s*$"""),                             // [user@host path]$
        Regex("""\]#\s*$"""),                              // [root@host path]#
    )

    /**
     * Analyze an output chunk for immediate state changes (WAITING_TOOL).
     * Returns new state or null if no immediate transition detected.
     */
    fun analyzeChunk(output: String): TabState? {
        val clean = STRIP_ANSI.replace(output, "")
        return when {
            WAITING_TOOL_PATTERNS.any { it.containsMatchIn(clean) } -> TabState.WAITING_TOOL
            WAITING_INPUT_PATTERNS.any { it.containsMatchIn(clean) } -> TabState.WAITING_INPUT
            else -> null
        }
    }

    /**
     * Analyze the tail of the buffer after an idle period.
     * Checks the last line for shell prompt patterns.
     * Returns WAITING_INPUT if a prompt is detected, null otherwise.
     */
    fun analyzeIdle(bufferTail: String): TabState? {
        val clean = STRIP_ANSI.replace(bufferTail, "")

        // Check for tool/input patterns in the whole tail first
        if (WAITING_TOOL_PATTERNS.any { it.containsMatchIn(clean) }) return TabState.WAITING_TOOL
        if (WAITING_INPUT_PATTERNS.any { it.containsMatchIn(clean) }) return TabState.WAITING_INPUT

        // Check the last non-empty line for shell prompt patterns
        val lastLine = clean.trimEnd('\n', '\r').substringAfterLast('\n').trimEnd()
        if (lastLine.isNotEmpty() && SHELL_PROMPT_PATTERNS.any { it.containsMatchIn(lastLine) }) {
            return TabState.WAITING_INPUT
        }

        return null
    }
}

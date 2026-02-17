package state

enum class ChatOption(val label: String) {
    STATISTICS("Statistics"),
    SYSTEM_PROMPT("System Prompt"),
    CONSTRAINTS("Constraints"),
    STOP_WORDS("Stop Words"),
    MAX_TOKENS("Max Tokens"),
    RESPONSE_FORMAT("Response Format")
}

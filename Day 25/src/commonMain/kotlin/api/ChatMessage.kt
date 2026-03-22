package api

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

data class ChatMessage(
    val role: String,
    val content: String,
    val requestSnapshot: RequestSnapshot? = null,
    val invariantViolation: String? = null,
    // Tool calling support
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    // RAG sources used for this response
    val ragSources: String? = null,
    // Citation validation result (Day 25)
    val citationResult: state.CitationResult? = null
)

package api

interface ChatApiInterface {
    suspend fun sendMessage(
        history: List<ChatMessage>,
        apiKey: String,
        model: String,
        temperature: Double?,
        maxTokens: Int?,
        systemPrompt: String?,
        connectTimeoutSec: Int?,
        readTimeoutSec: Int?,
        stop: List<String>?,
        responseFormat: String?,
        jsonSchema: String?
    ): ChatResponse

    fun buildSnapshot(
        history: List<ChatMessage>,
        model: String,
        temperature: Double?,
        maxTokens: Int?,
        systemPrompt: String?,
        stop: List<String>?,
        responseFormat: String?,
        jsonSchema: String?,
        userContent: String,
        freshSummarization: Boolean = false
    ): RequestSnapshot = RequestSnapshot("{}", 0, "[]", null, "{}")
}

data class ChatResponse(val content: String, val usage: TokenUsage?)

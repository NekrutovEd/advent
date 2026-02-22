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
}

data class ChatResponse(val content: String, val usage: TokenUsage?)

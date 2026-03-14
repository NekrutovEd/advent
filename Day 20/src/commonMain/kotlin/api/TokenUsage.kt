package api

data class TokenUsage(val promptTokens: Int, val completionTokens: Int, val totalTokens: Int)

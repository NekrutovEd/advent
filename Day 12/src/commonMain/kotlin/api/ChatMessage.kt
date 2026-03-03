package api

data class ChatMessage(val role: String, val content: String, val requestSnapshot: RequestSnapshot? = null)

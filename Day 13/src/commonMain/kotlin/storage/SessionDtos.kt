package storage

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessageDto(val role: String, val content: String)

@Serializable
data class MemoryItemDto(
    val id: String,
    val content: String,
    val source: String,
    val timestamp: Long
)

@Serializable
data class ChatStateDto(
    val id: String,
    val constraints: String,
    val systemPrompt: String,
    val stopWords: List<String>,
    val maxTokensOverride: String,
    val temperatureOverride: Float?,
    val modelOverride: String?,
    val responseFormatType: String,
    val jsonSchema: String,
    val sendHistory: Boolean,
    val autoSummarize: Boolean,
    val summarizeThreshold: String,
    val keepLastMessages: String,
    val summaryCount: Int,
    val slidingWindow: String = "",
    val extractFacts: Boolean = false,
    val stickyFacts: String = "",
    val extractMemory: Boolean = false,
    val visibleOptions: List<String>,
    val messages: List<ChatMessageDto>,
    val history: List<ChatMessageDto>
)

@Serializable
data class SessionDto(
    val id: String,
    val name: String,
    val chats: List<ChatStateDto>,
    val workingMemory: List<MemoryItemDto> = emptyList()
)

@Serializable
data class ArchivedSessionDto(val id: String, val name: String, val json: String, val timestamp: Long = 0L)

@Serializable
data class UserProfileDto(
    val id: String,
    val name: String,
    val items: List<String>,
    val isNameCustom: Boolean = false
)

@Serializable
data class AppStateDto(
    val activeSessionIndex: Int,
    val sessions: List<SessionDto>,
    val archivedSessions: List<ArchivedSessionDto>,
    val longTermMemory: List<MemoryItemDto> = emptyList(),
    val profiles: List<UserProfileDto> = emptyList(),
    val activeProfileId: String? = null
)

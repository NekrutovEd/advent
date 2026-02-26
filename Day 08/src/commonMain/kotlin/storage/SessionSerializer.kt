package storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import state.AppState
import state.SessionState

object SessionSerializer {
    internal val json = Json { ignoreUnknownKeys = true }

    fun encodeAll(appState: AppState): String {
        val dto = AppStateDto(
            activeSessionIndex = appState.activeSessionIndex,
            sessions = appState.sessions.map { encodeSessionToDto(it) },
            archivedSessions = appState.archivedSessions.toList()
        )
        return json.encodeToString(dto)
    }

    fun decodeAll(data: String, appState: AppState) {
        try {
            val dto = json.decodeFromString<AppStateDto>(data)
            appState.sessions.clear()
            dto.sessions.forEach { sessionDto ->
                appState.sessions.add(decodeSessionFromDto(sessionDto, appState))
            }
            if (appState.sessions.isEmpty()) appState.sessions.add(appState.createNewSession())
            // Note: this path is now unused — decodeAll kept for restore-from-archive only
            appState.archivedSessions.clear()
            appState.archivedSessions.addAll(dto.archivedSessions)
            appState.activeSessionIndex = dto.activeSessionIndex.coerceIn(0, appState.sessions.size - 1)
        } catch (_: Exception) {
            // Leave state as-is on parse failure
        }
    }

    fun encodeSession(session: SessionState): String {
        return json.encodeToString(encodeSessionToDto(session))
    }

    fun decodeAppStateDto(data: String): AppStateDto? = try {
        json.decodeFromString<AppStateDto>(data)
    } catch (_: Exception) { null }

    fun encodeSessionDto(dto: SessionDto): String = json.encodeToString(dto)

    fun decodeSession(data: String, appState: AppState): SessionState? {
        return try {
            val dto = json.decodeFromString<SessionDto>(data)
            decodeSessionFromDto(dto, appState)
        } catch (_: Exception) { null }
    }

    private fun encodeSessionToDto(session: SessionState): SessionDto {
        return SessionDto(
            id = session.id,
            name = session.name,
            chats = session.chats.map { chat ->
                ChatStateDto(
                    id = chat.id,
                    constraints = chat.constraints,
                    systemPrompt = chat.systemPrompt,
                    stopWords = chat.stopWords.toList(),
                    maxTokensOverride = chat.maxTokensOverride,
                    temperatureOverride = chat.temperatureOverride,
                    modelOverride = chat.modelOverride,
                    responseFormatType = chat.responseFormatType,
                    jsonSchema = chat.jsonSchema,
                    sendHistory = chat.sendHistory,
                    autoSummarize = chat.autoSummarize,
                    summarizeThreshold = chat.summarizeThreshold,
                    keepLastMessages = chat.keepLastMessages,
                    summaryCount = chat.summaryCount,
                    visibleOptions = chat.visibleOptions.map { it.name },
                    messages = chat.messages.map { ChatMessageDto(it.role, it.content) },
                    history = chat.historySnapshot().map { ChatMessageDto(it.role, it.content) }
                )
            }
        )
    }

    private fun decodeSessionFromDto(dto: SessionDto, appState: AppState): SessionState {
        val session = SessionState(
            chatApi = appState.chatApi,
            settings = appState.settings,
            id = dto.id,
            name = dto.name
        )
        session.chats.clear()
        dto.chats.forEach { chatDto ->
            val chat = session.createChat(chatDto.id)
            chat.constraints = chatDto.constraints
            chat.systemPrompt = chatDto.systemPrompt
            chat.stopWords.clear()
            chat.stopWords.addAll(chatDto.stopWords.ifEmpty { listOf("") })
            chat.maxTokensOverride = chatDto.maxTokensOverride
            chat.temperatureOverride = chatDto.temperatureOverride
            chat.modelOverride = chatDto.modelOverride
            chat.responseFormatType = chatDto.responseFormatType
            chat.jsonSchema = chatDto.jsonSchema
            chat.sendHistory = chatDto.sendHistory
            chat.autoSummarize = chatDto.autoSummarize
            chat.summarizeThreshold = chatDto.summarizeThreshold
            chat.keepLastMessages = chatDto.keepLastMessages
            chat.summaryCount = chatDto.summaryCount
            chat.visibleOptions = chatDto.visibleOptions.mapNotNull {
                runCatching { state.ChatOption.valueOf(it) }.getOrNull()
            }.toSet()
            chat.messages.addAll(chatDto.messages.map { api.ChatMessage(it.role, it.content) })
            chat.restoreHistory(chatDto.history.map { api.ChatMessage(it.role, it.content) })
            session.chats.add(chat)
        }
        if (session.chats.isEmpty()) session.chats.add(session.createChat())
        return session
    }
}

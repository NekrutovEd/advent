package storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import state.AppState
import state.InvariantItem
import state.MemoryItem
import state.MemorySource
import state.SessionState
import state.TaskPhase
import state.TaskStep
import state.UserProfile
import state.RagMode
import state.buildMemoryId

object SessionSerializer {
    internal val json = Json { ignoreUnknownKeys = true }

    fun encodeAll(appState: AppState): String {
        val s = appState.settings
        val settingsDto = SettingsDto(
            lang = s.lang.name,
            systemPrompt = s.systemPrompt,
            selectedModel = s.selectedModel,
            defaultSendHistory = s.defaultSendHistory,
            defaultAutoSummarize = s.defaultAutoSummarize,
            defaultSummarizeThreshold = s.defaultSummarizeThreshold,
            defaultKeepLastMessages = s.defaultKeepLastMessages,
            defaultSlidingWindow = s.defaultSlidingWindow,
            defaultExtractMemory = s.defaultExtractMemory,
            defaultTaskTracking = s.defaultTaskTracking,
            apiConfigs = s.apiConfigs.map { c ->
                ApiConfigDto(
                    id = c.id,
                    temperature = c.temperature,
                    maxTokens = c.maxTokens,
                    connectTimeout = c.connectTimeout,
                    readTimeout = c.readTimeout
                )
            }
        )
        val mcpDto = McpConfigDto() // legacy, kept for backward compat
        val mcpServersDto = appState.orchestrator?.servers?.map { entry ->
            McpServerConfigDto(
                id = entry.id,
                label = entry.label,
                serverCommand = entry.serverCommand,
                serverArgs = entry.serverArgs,
                autoConnect = entry.isConnected
            )
        } ?: emptyList()

        val dto = AppStateDto(
            activeSessionIndex = appState.activeSessionIndex,
            sessions = appState.sessions.map { encodeSessionToDto(it) },
            archivedSessions = appState.archivedSessions.toList(),
            longTermMemory = appState.longTermMemory.map { MemoryItemDto(it.id, it.content, it.source.name, it.timestamp) },
            profiles = appState.profiles.map { UserProfileDto(it.id, it.name, it.items.toList(), it.isNameCustom) },
            activeProfileId = appState.activeProfileId,
            invariants = appState.invariants.map { InvariantItemDto(it.id, it.content, it.timestamp) },
            settings = settingsDto,
            mcpConfig = mcpDto,
            mcpServers = mcpServersDto
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

            // Restore long-term memory
            appState.longTermMemory.clear()
            dto.longTermMemory.forEach { memDto ->
                val source = try { MemorySource.valueOf(memDto.source) } catch (_: Exception) { MemorySource.MANUAL }
                appState.longTermMemory.add(MemoryItem(id = memDto.id, content = memDto.content, source = source, timestamp = memDto.timestamp))
            }

            // Restore profiles
            appState.profiles.clear()
            dto.profiles.forEach { profileDto ->
                appState.profiles.add(UserProfile(
                    id = profileDto.id,
                    name = profileDto.name,
                    items = profileDto.items,
                    isNameCustom = profileDto.isNameCustom
                ))
            }
            appState.activeProfileId = dto.activeProfileId

            // Restore invariants
            appState.invariants.clear()
            dto.invariants.forEach { invDto ->
                appState.invariants.add(InvariantItem(id = invDto.id, content = invDto.content, timestamp = invDto.timestamp))
            }
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
                    slidingWindow = chat.slidingWindow,
                    extractFacts = chat.extractMemory, // backward compat
                    extractMemory = chat.extractMemory,
                    taskTracking = chat.taskTracking,
                    ragEnabled = chat.ragEnabled,
                    ragMode = chat.ragMode.name,
                    taskTracker = TaskTrackerDto(
                        phase = chat.taskTracker.phase.name,
                        isPaused = chat.taskTracker.isPaused,
                        steps = chat.taskTracker.steps.map { TaskStepDto(it.description, it.completed) },
                        currentStepIndex = chat.taskTracker.currentStepIndex,
                        taskDescription = chat.taskTracker.taskDescription
                    ),
                    visibleOptions = chat.visibleOptions.map { it.name },
                    messages = chat.messages.map { ChatMessageDto(it.role, it.content) },
                    history = chat.historySnapshot().map { ChatMessageDto(it.role, it.content) }
                )
            },
            workingMemory = session.workingMemory.map { MemoryItemDto(it.id, it.content, it.source.name, it.timestamp) }
        )
    }

    internal fun decodeSessionFromDto(dto: SessionDto, appState: AppState): SessionState {
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
            chat.slidingWindow = chatDto.slidingWindow
            chat.extractMemory = chatDto.extractMemory || chatDto.extractFacts
            chat.taskTracking = chatDto.taskTracking
            chat.ragEnabled = chatDto.ragEnabled
            chat.ragMode = runCatching { RagMode.valueOf(chatDto.ragMode) }.getOrDefault(RagMode.RERANKED)
            val tt = chatDto.taskTracker
            chat.taskTracker.phase = runCatching { TaskPhase.valueOf(tt.phase) }.getOrDefault(TaskPhase.IDLE)
            chat.taskTracker.isPaused = tt.isPaused
            chat.taskTracker.steps.addAll(tt.steps.map { TaskStep(it.description, it.completed) })
            chat.taskTracker.currentStepIndex = tt.currentStepIndex
            chat.taskTracker.taskDescription = tt.taskDescription
            chat.visibleOptions = chatDto.visibleOptions.mapNotNull { name ->
                // Backward compat: map old HISTORY/SUMMARIZATION to CONTEXT
                val mapped = when (name) {
                    "HISTORY", "SUMMARIZATION" -> "CONTEXT"
                    else -> name
                }
                runCatching { state.ChatOption.valueOf(mapped) }.getOrNull()
            }.toSet()
            chat.messages.addAll(chatDto.messages.map { api.ChatMessage(it.role, it.content) })
            chat.restoreHistory(chatDto.history.map { api.ChatMessage(it.role, it.content) })
            session.chats.add(chat)
        }
        if (session.chats.isEmpty()) session.chats.add(session.createChat())

        // Restore working memory
        dto.workingMemory.forEach { memDto ->
            val source = try { MemorySource.valueOf(memDto.source) } catch (_: Exception) { MemorySource.MANUAL }
            session.workingMemory.add(MemoryItem(id = memDto.id, content = memDto.content, source = source, timestamp = memDto.timestamp))
        }

        // Migration: if old stickyFacts exist and no working memory was stored, migrate
        if (session.workingMemory.isEmpty()) {
            dto.chats.forEach { chatDto ->
                if (chatDto.stickyFacts.isNotBlank()) {
                    chatDto.stickyFacts.lines()
                        .map { it.trimStart('-', ' ') }
                        .filter { it.isNotBlank() }
                        .forEach { fact ->
                            session.workingMemory.add(
                                MemoryItem(
                                    id = buildMemoryId(),
                                    content = fact,
                                    source = MemorySource.AUTO_EXTRACTED,
                                    timestamp = 0L
                                )
                            )
                        }
                }
            }
        }

        return session
    }
}

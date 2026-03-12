package state

import api.ChatApiInterface
import api.ChatMessage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mcp.McpClientInterface
import kotlin.random.Random
import storage.ArchivedSessionDto
import storage.NoOpStorage
import storage.SessionSerializer
import storage.StorageManager

class AppState(
    internal val chatApi: ChatApiInterface,
    private val storage: StorageManager = NoOpStorage,
    mcpClient: McpClientInterface? = null
) {
    internal val mcpClientRef: McpClientInterface? = mcpClient
    val mcpState: McpState? = mcpClient?.let { McpState(it) }
    val settings = SettingsState()
    val sessions = mutableStateListOf<SessionState>()
    val archivedSessions = mutableStateListOf<ArchivedSessionDto>()
    var activeSessionIndex by mutableStateOf(0)
    var showSettings by mutableStateOf(false)

    val longTermMemory = mutableStateListOf<MemoryItem>()
    val invariants = mutableStateListOf<InvariantItem>()
    var showMemoryPanel by mutableStateOf(false)

    // User profiles
    val profiles = mutableStateListOf<UserProfile>()
    var activeProfileId by mutableStateOf<String?>(null)

    init {
        sessions.add(createNewSession())
    }

    val activeSession: SessionState get() = sessions[activeSessionIndex]
    val isBusy: Boolean get() = activeSession.isBusy

    fun currentTimeMs(): Long = storage.currentTimeMs()

    fun addLongTermMemoryItem(content: String, source: MemorySource, timestamp: Long): MemoryItem {
        val item = MemoryItem(id = buildMemoryId(), content = content, source = source, timestamp = timestamp)
        longTermMemory.add(item)
        return item
    }

    fun removeLongTermMemoryItem(itemId: String) {
        longTermMemory.removeAll { it.id == itemId }
    }

    fun updateLongTermMemoryItem(itemId: String, newContent: String) {
        val index = longTermMemory.indexOfFirst { it.id == itemId }
        if (index >= 0) {
            longTermMemory[index] = longTermMemory[index].copy(content = newContent)
        }
    }

    fun longTermMemoryText(): String =
        longTermMemory.joinToString("\n") { "- ${it.content}" }

    // Invariant CRUD
    fun addInvariant(content: String, timestamp: Long): InvariantItem {
        val item = InvariantItem(id = buildMemoryId(), content = content, timestamp = timestamp)
        invariants.add(item)
        return item
    }

    fun removeInvariant(itemId: String) {
        invariants.removeAll { it.id == itemId }
    }

    fun updateInvariant(itemId: String, newContent: String) {
        val index = invariants.indexOfFirst { it.id == itemId }
        if (index >= 0) {
            invariants[index] = invariants[index].copy(content = newContent)
        }
    }

    fun invariantsText(): String =
        invariants.joinToString("\n") { "- ${it.content}" }

    // Profile CRUD
    fun addProfile(): UserProfile {
        val profile = UserProfile.create(profiles.size)
        profiles.add(profile)
        if (activeProfileId == null) activeProfileId = profile.id
        return profile
    }

    fun removeProfile(id: String) {
        profiles.removeAll { it.id == id }
        if (activeProfileId == id) {
            activeProfileId = profiles.firstOrNull()?.id
        }
    }

    fun selectProfile(id: String?) {
        activeProfileId = id
    }

    fun renameProfile(id: String, name: String) {
        profiles.firstOrNull { it.id == id }?.let {
            it.name = name
            it.isNameCustom = true
        }
    }

    fun addProfileItem(id: String, content: String) {
        val profile = profiles.firstOrNull { it.id == id } ?: return
        profile.items.add(content)
        if (!profile.isNameCustom) {
            profile.name = UserProfile.generateAutoName(profile.items, profiles.indexOf(profile))
        }
    }

    fun removeProfileItem(id: String, index: Int) {
        val profile = profiles.firstOrNull { it.id == id } ?: return
        if (index in profile.items.indices) {
            profile.items.removeAt(index)
            if (!profile.isNameCustom) {
                profile.name = UserProfile.generateAutoName(profile.items, profiles.indexOf(profile))
            }
        }
    }

    fun updateProfileItem(id: String, index: Int, content: String) {
        val profile = profiles.firstOrNull { it.id == id } ?: return
        if (index in profile.items.indices) {
            profile.items[index] = content
            if (!profile.isNameCustom) {
                profile.name = UserProfile.generateAutoName(profile.items, profiles.indexOf(profile))
            }
        }
    }

    fun activeProfileText(): String {
        val profile = profiles.firstOrNull { it.id == activeProfileId } ?: return ""
        return profile.toText()
    }

    fun promoteToLongTerm(session: SessionState, itemId: String, timestamp: Long) {
        val index = session.workingMemory.indexOfFirst { it.id == itemId }
        if (index < 0) return
        val item = session.workingMemory[index]
        session.workingMemory.removeAt(index)
        addLongTermMemoryItem(item.content, MemorySource.PROMOTED, timestamp)
    }

    // MCP helpers
    private fun mcpToolsOrNull(): List<mcp.McpTool>? =
        mcpState?.tools?.toList()?.takeIf { it.isNotEmpty() }

    private fun mcpToolExecutor(): (suspend (String, String) -> String)? {
        val client = mcpClientRef ?: return null
        if (mcpState?.isConnected != true) return null
        val executor: suspend (String, String) -> String = { name, args ->
            client.callTool(name, args).content
        }
        return executor
    }

    // Delegates to active session
    fun sendToAll(prompt: String, scope: CoroutineScope): List<Job> =
        activeSession.sendToAll(
            prompt, scope,
            longTermMemoryText = longTermMemoryText(),
            profileText = activeProfileText(),
            invariantsText = invariantsText(),
            timestamp = currentTimeMs(),
            onLongTermExtracted = { items ->
                items.forEach { addLongTermMemoryItem(it, MemorySource.AUTO_EXTRACTED, currentTimeMs()) }
            },
            mcpTools = mcpToolsOrNull(),
            toolExecutor = mcpToolExecutor()
        )

    fun sendToOne(chat: ChatState, prompt: String, scope: CoroutineScope): Job? =
        activeSession.sendToOne(
            chat, prompt, scope,
            longTermMemoryText = longTermMemoryText(),
            profileText = activeProfileText(),
            invariantsText = invariantsText(),
            timestamp = currentTimeMs(),
            onLongTermExtracted = { items ->
                items.forEach { addLongTermMemoryItem(it, MemorySource.AUTO_EXTRACTED, currentTimeMs()) }
            },
            mcpTools = mcpToolsOrNull(),
            toolExecutor = mcpToolExecutor()
        )

    // Scheduled message delivery — sends a prompt into a specific chat as an AI roundtrip
    // No MCP tools passed — agent just responds to the text, no tool calling
    fun deliverScheduledMessage(sessionId: String, chatId: String, prompt: String, scope: CoroutineScope): Job? {
        val session = sessions.firstOrNull { it.id == sessionId } ?: return null
        val chat = session.chats.firstOrNull { it.id == chatId } ?: return null
        if (chat.isLoading) return null // avoid concurrent sends
        return session.sendToOne(
            chat, prompt, scope,
            longTermMemoryText = longTermMemoryText(),
            profileText = activeProfileText(),
            invariantsText = invariantsText(),
            timestamp = currentTimeMs(),
            onLongTermExtracted = { items ->
                items.forEach { addLongTermMemoryItem(it, MemorySource.AUTO_EXTRACTED, currentTimeMs()) }
            },
            mcpTools = null,
            toolExecutor = null,
            hideUserMessage = true
        )
    }

    // Session management
    fun addSession() {
        sessions.add(createNewSession())
        activeSessionIndex = sessions.size - 1
    }

    fun selectSession(index: Int) {
        if (index in sessions.indices) activeSessionIndex = index
    }

    /** Just removes the session — no archiving. */
    fun deleteSession(index: Int) {
        if (index !in sessions.indices) return
        sessions.removeAt(index)
        if (sessions.isEmpty()) sessions.add(createNewSession())
        activeSessionIndex = activeSessionIndex.coerceIn(0, sessions.size - 1)
    }

    fun restoreSession(dto: ArchivedSessionDto) {
        val session = SessionSerializer.decodeSession(dto.json, this)
        if (session != null) {
            sessions.add(session)
            activeSessionIndex = sessions.size - 1
        }
        archivedSessions.remove(dto)
    }

    fun deleteFromArchive(dto: ArchivedSessionDto) {
        archivedSessions.remove(dto)
    }

    fun clearArchive() {
        archivedSessions.clear()
    }

    // Persistence
    fun saveToStorage() { storage.save(SessionSerializer.encodeAll(this)) }

    /**
     * On load: previous active sessions are moved to archive (timestamped now),
     * old archive entries are merged in, entries older than 7 days are pruned.
     * The app always starts fresh with a new "New" session.
     */
    fun loadFromStorage() {
        val data = storage.load() ?: return
        val dto = SessionSerializer.decodeAppStateDto(data) ?: return
        val now = storage.currentTimeMs()
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000

        // Archive previous active sessions (skip empty "New" sessions with no messages)
        dto.sessions.forEach { sessionDto ->
            val hasContent = sessionDto.chats.any { it.messages.isNotEmpty() } || sessionDto.name != "New"
            if (hasContent && archivedSessions.none { it.id == sessionDto.id }) {
                archivedSessions.add(
                    ArchivedSessionDto(
                        id = sessionDto.id,
                        name = sessionDto.name,
                        json = SessionSerializer.encodeSessionDto(sessionDto),
                        timestamp = now
                    )
                )
            }
        }

        // Merge previous archive entries, dedup by id, prune expired
        dto.archivedSessions.forEach { archived ->
            val expired = archived.timestamp != 0L && (now - archived.timestamp > sevenDaysMs)
            if (!expired && archivedSessions.none { it.id == archived.id }) {
                archivedSessions.add(archived)
            }
        }

        // Restore long-term memory
        dto.longTermMemory.forEach { memDto ->
            val source = try { MemorySource.valueOf(memDto.source) } catch (_: Exception) { MemorySource.MANUAL }
            longTermMemory.add(MemoryItem(id = memDto.id, content = memDto.content, source = source, timestamp = memDto.timestamp))
        }

        // Restore profiles
        dto.profiles.forEach { profileDto ->
            profiles.add(UserProfile(
                id = profileDto.id,
                name = profileDto.name,
                items = profileDto.items,
                isNameCustom = profileDto.isNameCustom
            ))
        }
        activeProfileId = dto.activeProfileId

        // Restore invariants
        dto.invariants.forEach { invDto ->
            invariants.add(InvariantItem(id = invDto.id, content = invDto.content, timestamp = invDto.timestamp))
        }

        // Restore settings
        val sd = dto.settings
        settings.lang = runCatching { i18n.Lang.valueOf(sd.lang) }.getOrDefault(i18n.Lang.EN)
        settings.systemPrompt = sd.systemPrompt
        settings.selectedModel = sd.selectedModel
        settings.defaultSendHistory = sd.defaultSendHistory
        settings.defaultAutoSummarize = sd.defaultAutoSummarize
        settings.defaultSummarizeThreshold = sd.defaultSummarizeThreshold
        settings.defaultKeepLastMessages = sd.defaultKeepLastMessages
        settings.defaultSlidingWindow = sd.defaultSlidingWindow
        settings.defaultExtractMemory = sd.defaultExtractMemory
        settings.defaultTaskTracking = sd.defaultTaskTracking
        sd.apiConfigs.forEach { acd ->
            settings.apiConfigs.firstOrNull { it.id == acd.id }?.let { config ->
                config.temperature = acd.temperature
                config.maxTokens = acd.maxTokens
                config.connectTimeout = acd.connectTimeout
                config.readTimeout = acd.readTimeout
            }
        }

        // Restore MCP config and auto-connect
        val mcp = dto.mcpConfig
        mcpState?.let { state ->
            state.serverCommand = mcp.serverCommand
            state.serverArgs = mcp.serverArgs
        }
        pendingMcpAutoConnect = mcp.autoConnect && mcp.serverCommand.isNotBlank()
    }

    /** Set by loadFromStorage, consumed by UI to trigger auto-connect once. */
    var pendingMcpAutoConnect by mutableStateOf(false)

    /** Auto-renames a "New" session using the cheapest available model. */
    fun autoRenameSession(sessionIndex: Int, firstPrompt: String, scope: CoroutineScope) {
        if (sessionIndex !in sessions.indices) return
        val session = sessions[sessionIndex]
        if (session.name != "New") return

        val cheapModels = listOf("gpt-4o-mini", "gpt-4o-mini-2024-07-18", "gpt-4o", "gpt-3.5-turbo")
        val allModels = settings.allModels()
        val model = cheapModels.firstOrNull { it in allModels } ?: allModels.firstOrNull() ?: return
        val apiConfig = settings.configForModel(model) ?: return
        if (apiConfig.apiKey.isBlank()) return

        scope.launch {
            try {
                val response = chatApi.sendMessage(
                    history = listOf(
                        ChatMessage(
                            "user",
                            "Give a very short 2-5 word title for a chat starting with: \"${firstPrompt.take(200)}\""
                        )
                    ),
                    apiKey = apiConfig.apiKey,
                    model = model,
                    temperature = 0.3,
                    maxTokens = 20,
                    systemPrompt = "Reply with ONLY the title. No quotes, no punctuation, no explanation.",
                    connectTimeoutSec = 10,
                    readTimeoutSec = 30,
                    stop = null,
                    responseFormat = null,
                    jsonSchema = null
                )
                val name = response.content.trim()
                    .trimStart('"', '\'', '\u00AB', '\u201C')
                    .trimEnd('"', '\'', '\u00BB', '\u201D', '.', ',', '!')
                    .trim()
                    .take(50)
                if (name.isNotBlank() && sessionIndex in sessions.indices && sessions[sessionIndex].name == "New") {
                    sessions[sessionIndex].name = name
                }
            } catch (_: Exception) { /* silently keep "New" */ }
        }
    }

    internal fun createNewSession(): SessionState = SessionState(
        chatApi = chatApi,
        settings = settings,
        id = generateId(),
        name = "New"
    )

    private fun generateId(): String = Random.Default.nextBytes(4).joinToString("") {
        val v = it.toInt() and 0xFF
        val h = v.toString(16)
        if (h.length == 1) "0$h" else h
    }
}

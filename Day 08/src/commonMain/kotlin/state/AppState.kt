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
import kotlin.random.Random
import storage.ArchivedSessionDto
import storage.NoOpStorage
import storage.SessionSerializer
import storage.StorageManager

class AppState(
    internal val chatApi: ChatApiInterface,
    private val storage: StorageManager = NoOpStorage
) {
    val settings = SettingsState()
    val sessions = mutableStateListOf<SessionState>()
    val archivedSessions = mutableStateListOf<ArchivedSessionDto>()
    var activeSessionIndex by mutableStateOf(0)
    var showSettings by mutableStateOf(false)

    init {
        sessions.add(createNewSession())
    }

    val activeSession: SessionState get() = sessions[activeSessionIndex]
    val isBusy: Boolean get() = activeSession.isBusy

    // Delegates to active session
    fun sendToAll(prompt: String, scope: CoroutineScope): List<Job> = activeSession.sendToAll(prompt, scope)
    fun sendToOne(chat: ChatState, prompt: String, scope: CoroutineScope): Job? = activeSession.sendToOne(chat, prompt, scope)

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
    }

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
                    .trimStart('"', '\'', '«', '\u201C')
                    .trimEnd('"', '\'', '»', '\u201D', '.', ',', '!')
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

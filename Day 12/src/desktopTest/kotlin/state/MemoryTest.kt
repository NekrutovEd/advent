package state

import api.ChatApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import storage.SessionSerializer

class MemoryTest {

    private lateinit var server: MockWebServer
    private lateinit var appState: AppState

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = ChatApi(
            baseUrl = server.url("/").toString().trimEnd('/'),
            ioDispatcher = Dispatchers.Unconfined
        )
        appState = AppState(api)
        appState.settings.apiConfigs[0].apiKey = "test-key"
        appState.settings.apiConfigs[0].baseUrl = server.url("/").toString().trimEnd('/')
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueSuccess(content: String = "Response") {
        val body = JSONObject().apply {
            put("choices", JSONArray().apply {
                put(JSONObject().apply {
                    put("message", JSONObject().apply {
                        put("role", "assistant")
                        put("content", content)
                    })
                })
            })
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(body.toString()))
    }

    // --- Working Memory CRUD ---

    @Test
    fun `addWorkingMemoryItem adds to session list`() {
        val session = appState.activeSession
        val item = session.addWorkingMemoryItem("User prefers dark mode", MemorySource.MANUAL, 1000L)
        assertEquals(1, session.workingMemory.size)
        assertEquals("User prefers dark mode", session.workingMemory[0].content)
        assertEquals(MemorySource.MANUAL, session.workingMemory[0].source)
        assertEquals(item.id, session.workingMemory[0].id)
    }

    @Test
    fun `removeWorkingMemoryItem removes by id`() {
        val session = appState.activeSession
        val item = session.addWorkingMemoryItem("fact1", MemorySource.MANUAL, 1000L)
        session.addWorkingMemoryItem("fact2", MemorySource.MANUAL, 1000L)
        assertEquals(2, session.workingMemory.size)

        session.removeWorkingMemoryItem(item.id)
        assertEquals(1, session.workingMemory.size)
        assertEquals("fact2", session.workingMemory[0].content)
    }

    @Test
    fun `updateWorkingMemoryItem updates content`() {
        val session = appState.activeSession
        val item = session.addWorkingMemoryItem("old fact", MemorySource.MANUAL, 1000L)
        session.updateWorkingMemoryItem(item.id, "new fact")
        assertEquals("new fact", session.workingMemory[0].content)
    }

    @Test
    fun `workingMemoryText formats as bullet list`() {
        val session = appState.activeSession
        session.addWorkingMemoryItem("fact1", MemorySource.MANUAL, 1000L)
        session.addWorkingMemoryItem("fact2", MemorySource.MANUAL, 1000L)
        assertEquals("- fact1\n- fact2", session.workingMemoryText())
    }

    // --- Long-term Memory CRUD ---

    @Test
    fun `addLongTermMemoryItem adds to app list`() {
        val item = appState.addLongTermMemoryItem("User likes Kotlin", MemorySource.MANUAL, 2000L)
        assertEquals(1, appState.longTermMemory.size)
        assertEquals("User likes Kotlin", appState.longTermMemory[0].content)
        assertEquals(item.id, appState.longTermMemory[0].id)
    }

    @Test
    fun `removeLongTermMemoryItem removes by id`() {
        val item = appState.addLongTermMemoryItem("fact1", MemorySource.MANUAL, 1000L)
        appState.addLongTermMemoryItem("fact2", MemorySource.MANUAL, 1000L)
        appState.removeLongTermMemoryItem(item.id)
        assertEquals(1, appState.longTermMemory.size)
        assertEquals("fact2", appState.longTermMemory[0].content)
    }

    @Test
    fun `updateLongTermMemoryItem updates content`() {
        val item = appState.addLongTermMemoryItem("old", MemorySource.MANUAL, 1000L)
        appState.updateLongTermMemoryItem(item.id, "new")
        assertEquals("new", appState.longTermMemory[0].content)
    }

    // --- Promote ---

    @Test
    fun `promoteToLongTerm moves item and changes source`() {
        val session = appState.activeSession
        val item = session.addWorkingMemoryItem("task fact", MemorySource.AUTO_EXTRACTED, 1000L)
        appState.promoteToLongTerm(session, item.id, 2000L)

        assertEquals(0, session.workingMemory.size)
        assertEquals(1, appState.longTermMemory.size)
        assertEquals("task fact", appState.longTermMemory[0].content)
        assertEquals(MemorySource.PROMOTED, appState.longTermMemory[0].source)
    }

    @Test
    fun `promoteToLongTerm with nonexistent id is no-op`() {
        val session = appState.activeSession
        session.addWorkingMemoryItem("fact", MemorySource.MANUAL, 1000L)
        appState.promoteToLongTerm(session, "nonexistent", 2000L)
        assertEquals(1, session.workingMemory.size)
        assertEquals(0, appState.longTermMemory.size)
    }

    // --- Memory injection in sendMessage ---

    @Test
    fun `sendMessage injects working memory as system message`() = runTest {
        enqueueSuccess("Hi")
        val session = appState.activeSession
        session.addWorkingMemoryItem("working fact 1", MemorySource.MANUAL, 1000L)
        val chat = session.chats[0]

        chat.sendMessage(
            "Hello", "key", "gpt-4o", null, null,
            workingMemoryText = session.workingMemoryText()
        )

        val req = server.takeRequest()
        val body = JSONObject(req.body.readUtf8())
        val msgs = body.getJSONArray("messages")
        // First message should be [Task Context] system message
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertTrue(msgs.getJSONObject(0).getString("content").contains("[Task Context]"))
        assertTrue(msgs.getJSONObject(0).getString("content").contains("working fact 1"))
    }

    @Test
    fun `sendMessage injects long-term memory as system message`() = runTest {
        enqueueSuccess("Hi")
        appState.addLongTermMemoryItem("long term fact", MemorySource.MANUAL, 1000L)
        val chat = appState.activeSession.chats[0]

        chat.sendMessage(
            "Hello", "key", "gpt-4o", null, null,
            longTermMemoryText = appState.longTermMemoryText()
        )

        val req = server.takeRequest()
        val body = JSONObject(req.body.readUtf8())
        val msgs = body.getJSONArray("messages")
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertTrue(msgs.getJSONObject(0).getString("content").contains("[Long-Term Memory]"))
        assertTrue(msgs.getJSONObject(0).getString("content").contains("long term fact"))
    }

    @Test
    fun `injection order is long-term before working`() = runTest {
        enqueueSuccess("Hi")
        val session = appState.activeSession
        session.addWorkingMemoryItem("working fact", MemorySource.MANUAL, 1000L)
        appState.addLongTermMemoryItem("long fact", MemorySource.MANUAL, 1000L)
        val chat = session.chats[0]

        chat.sendMessage(
            "Hello", "key", "gpt-4o", null, null,
            workingMemoryText = session.workingMemoryText(),
            longTermMemoryText = appState.longTermMemoryText()
        )

        val req = server.takeRequest()
        val body = JSONObject(req.body.readUtf8())
        val msgs = body.getJSONArray("messages")
        // Long-term first, then working, then user
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertTrue(msgs.getJSONObject(0).getString("content").contains("[Long-Term Memory]"))
        assertEquals("system", msgs.getJSONObject(1).getString("role"))
        assertTrue(msgs.getJSONObject(1).getString("content").contains("[Task Context]"))
        assertEquals("user", msgs.getJSONObject(2).getString("role"))
    }

    @Test
    fun `empty memory produces no extra system messages`() = runTest {
        enqueueSuccess("Hi")
        val chat = appState.activeSession.chats[0]

        chat.sendMessage("Hello", "key", "gpt-4o", null, null)

        val req = server.takeRequest()
        val body = JSONObject(req.body.readUtf8())
        val msgs = body.getJSONArray("messages")
        assertEquals(1, msgs.length())
        assertEquals("user", msgs.getJSONObject(0).getString("role"))
    }

    // --- Serialization round-trip ---

    @Test
    fun `working memory survives serialize-deserialize round-trip`() {
        val session = appState.activeSession
        session.addWorkingMemoryItem("persisted fact", MemorySource.MANUAL, 5000L)

        val encoded = SessionSerializer.encodeSession(session)
        val decoded = SessionSerializer.decodeSession(encoded, appState)!!

        assertEquals(1, decoded.workingMemory.size)
        assertEquals("persisted fact", decoded.workingMemory[0].content)
        assertEquals(MemorySource.MANUAL, decoded.workingMemory[0].source)
        assertEquals(5000L, decoded.workingMemory[0].timestamp)
    }

    @Test
    fun `long-term memory survives serialize-deserialize round-trip`() {
        appState.addLongTermMemoryItem("global fact", MemorySource.PROMOTED, 3000L)

        val encoded = SessionSerializer.encodeAll(appState)
        val api = ChatApi(baseUrl = server.url("/").toString().trimEnd('/'), ioDispatcher = Dispatchers.Unconfined)
        val newAppState = AppState(api)
        newAppState.settings.apiConfigs[0].apiKey = "test-key"

        val dto = SessionSerializer.decodeAppStateDto(encoded)!!
        // Simulate loadFromStorage for long-term memory
        dto.longTermMemory.forEach { memDto ->
            val source = try { MemorySource.valueOf(memDto.source) } catch (_: Exception) { MemorySource.MANUAL }
            newAppState.longTermMemory.add(MemoryItem(id = memDto.id, content = memDto.content, source = source, timestamp = memDto.timestamp))
        }

        assertEquals(1, newAppState.longTermMemory.size)
        assertEquals("global fact", newAppState.longTermMemory[0].content)
        assertEquals(MemorySource.PROMOTED, newAppState.longTermMemory[0].source)
    }

    @Test
    fun `old stickyFacts migrated to working memory on load`() {
        // Simulate old JSON with stickyFacts but no workingMemory
        val oldJson = """
            {
                "id": "abc123",
                "name": "Test",
                "chats": [{
                    "id": "chat1",
                    "constraints": "",
                    "systemPrompt": "",
                    "stopWords": [""],
                    "maxTokensOverride": "",
                    "temperatureOverride": null,
                    "modelOverride": null,
                    "responseFormatType": "text",
                    "jsonSchema": "",
                    "sendHistory": true,
                    "autoSummarize": false,
                    "summarizeThreshold": "10",
                    "keepLastMessages": "4",
                    "summaryCount": 0,
                    "slidingWindow": "",
                    "extractFacts": true,
                    "stickyFacts": "- User prefers dark mode\n- User uses Kotlin",
                    "visibleOptions": [],
                    "messages": [],
                    "history": []
                }]
            }
        """.trimIndent()

        val decoded = SessionSerializer.decodeSession(oldJson, appState)!!
        // extractMemory should be set from extractFacts
        assertTrue(decoded.chats[0].extractMemory)
        // stickyFacts should be migrated to working memory
        assertEquals(2, decoded.workingMemory.size)
        assertEquals("User prefers dark mode", decoded.workingMemory[0].content)
        assertEquals("User uses Kotlin", decoded.workingMemory[1].content)
        assertEquals(MemorySource.AUTO_EXTRACTED, decoded.workingMemory[0].source)
    }
}

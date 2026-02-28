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

class AppStateTest {

    private lateinit var server: MockWebServer
    private lateinit var appState: AppState

    // Convenience accessor: active session's chats
    private val chats get() = appState.activeSession.chats

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
        appState.activeSession.addChat() // now we have 2 chats for existing tests
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
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(body.toString())
        )
    }

    @Test
    fun `applyConstraints with empty constraints returns prompt`() {
        assertEquals("Hello", SessionState.applyConstraints("Hello", ""))
        assertEquals("Hello", SessionState.applyConstraints("Hello", "   "))
    }

    @Test
    fun `applyConstraints appends constraints`() {
        val result = SessionState.applyConstraints("Write a poem", "Use rhymes")
        assertEquals("Write a poem\n\nUse rhymes", result)
    }

    @Test
    fun `combineSystemPrompts both empty returns null`() {
        assertNull(SessionState.combineSystemPrompts("", ""))
        assertNull(SessionState.combineSystemPrompts("  ", "  "))
    }

    @Test
    fun `combineSystemPrompts only global returns global`() {
        assertEquals("Be helpful", SessionState.combineSystemPrompts("Be helpful", ""))
    }

    @Test
    fun `combineSystemPrompts only perChat returns perChat`() {
        assertEquals("Be brief", SessionState.combineSystemPrompts("", "Be brief"))
    }

    @Test
    fun `combineSystemPrompts both present combines with double newline`() {
        val result = SessionState.combineSystemPrompts("Be helpful", "Be brief")
        assertEquals("Be helpful\n\nBe brief", result)
    }

    @Test
    fun `sendToAll sends to both chats without constraints`() = runTest {
        enqueueSuccess("Response 1")
        enqueueSuccess("Response 2")

        val jobs = appState.sendToAll("Hello", this)
        jobs.forEach { it.join() }

        assertEquals(2, chats[0].messages.size)
        assertEquals(2, chats[1].messages.size)
        assertEquals("Hello", chats[0].messages[0].content)
        assertEquals("Hello", chats[1].messages[0].content)
    }

    @Test
    fun `sendToAll applies per-chat constraints independently`() = runTest {
        enqueueSuccess("Response 1")
        enqueueSuccess("Response 2")

        chats[0].constraints = "Be verbose"
        chats[1].constraints = "Be brief"
        val jobs = appState.sendToAll("Tell me a story", this)
        jobs.forEach { it.join() }

        assertEquals("Tell me a story\n\nBe verbose", chats[0].messages[0].content)
        assertEquals("Tell me a story\n\nBe brief", chats[1].messages[0].content)
    }

    @Test
    fun `sendToAll with constraints on one chat only`() = runTest {
        enqueueSuccess("Response 1")
        enqueueSuccess("Response 2")

        chats[1].constraints = "Be brief"
        val jobs = appState.sendToAll("Tell me a story", this)
        jobs.forEach { it.join() }

        assertEquals("Tell me a story", chats[0].messages[0].content)
        assertEquals("Tell me a story\n\nBe brief", chats[1].messages[0].content)
    }

    @Test
    fun `sendToAll routes per-chat system prompt`() = runTest {
        enqueueSuccess("Response 1")
        enqueueSuccess("Response 2")

        chats[0].systemPrompt = "You are a poet"
        val jobs = appState.sendToAll("Hello", this)
        jobs.forEach { it.join() }

        val req1 = server.takeRequest()
        val body1 = JSONObject(req1.body.readUtf8())
        val msgs1 = body1.getJSONArray("messages")
        assertEquals("system", msgs1.getJSONObject(0).getString("role"))
        assertEquals("You are a poet", msgs1.getJSONObject(0).getString("content"))

        val req2 = server.takeRequest()
        val body2 = JSONObject(req2.body.readUtf8())
        val msgs2 = body2.getJSONArray("messages")
        assertEquals("user", msgs2.getJSONObject(0).getString("role"))
    }

    @Test
    fun `sendToAll combines global and per-chat system prompt`() = runTest {
        enqueueSuccess("Response 1")
        enqueueSuccess("Response 2")

        appState.settings.systemPrompt = "Be helpful"
        chats[0].systemPrompt = "You are a poet"
        val jobs = appState.sendToAll("Hello", this)
        jobs.forEach { it.join() }

        val req1 = server.takeRequest()
        val body1 = JSONObject(req1.body.readUtf8())
        val msgs1 = body1.getJSONArray("messages")
        assertEquals("system", msgs1.getJSONObject(0).getString("role"))
        assertEquals("Be helpful\n\nYou are a poet", msgs1.getJSONObject(0).getString("content"))
    }

    @Test
    fun `sendToAll does nothing with blank prompt`() = runTest {
        val jobs = appState.sendToAll("", this)
        assertTrue(jobs.isEmpty())

        assertEquals(0, chats[0].messages.size)
        assertEquals(0, chats[1].messages.size)
    }

    @Test
    fun `sendToAll does nothing with blank apiKey`() = runTest {
        appState.settings.apiConfigs[0].apiKey = ""
        val jobs = appState.sendToAll("Hello", this)
        assertTrue(jobs.isEmpty())

        assertEquals(0, chats[0].messages.size)
        assertEquals(0, chats[1].messages.size)
    }

    @Test
    fun `one chat failure does not affect the other`() = runTest {
        enqueueSuccess("Success")
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))

        val jobs = appState.sendToAll("Hello", this)
        jobs.forEach { it.join() }

        val successChat = if (chats[0].messages.size == 2) chats[0] else chats[1]
        val failChat = if (chats[0].messages.size == 2) chats[1] else chats[0]

        assertEquals(2, successChat.messages.size)
        assertNotNull(failChat.error)
    }

    @Test
    fun `init creates one session with one chat by default`() {
        val api = ChatApi(baseUrl = "http://localhost")
        val state = AppState(api)
        assertEquals(1, state.sessions.size)
        assertEquals(1, state.activeSession.chats.size)
    }

    @Test
    fun `addChat increases chat count in active session`() {
        val initialSize = chats.size
        appState.activeSession.addChat()
        assertEquals(initialSize + 1, chats.size)
    }

    @Test
    fun `removeChat removes chat at given index`() {
        appState.activeSession.addChat()
        val sizeBefore = chats.size
        appState.activeSession.removeChat(1)
        assertEquals(sizeBefore - 1, chats.size)
    }

    @Test
    fun `removeChat index 0 is ignored`() {
        val sizeBefore = chats.size
        appState.activeSession.removeChat(0)
        assertEquals(sizeBefore, chats.size)
    }

    @Test
    fun `removeChat out of bounds is ignored`() {
        val sizeBefore = chats.size
        appState.activeSession.removeChat(100)
        assertEquals(sizeBefore, chats.size)
    }

    @Test
    fun `clearAll clears all chats`() = runTest {
        enqueueSuccess("R1")
        enqueueSuccess("R2")

        val jobs = appState.sendToAll("Hello", this)
        jobs.forEach { it.join() }

        assertTrue(chats[0].messages.isNotEmpty())
        assertTrue(chats[1].messages.isNotEmpty())

        appState.activeSession.clearAll()

        assertEquals(0, chats[0].messages.size)
        assertEquals(0, chats[1].messages.size)
    }

    @Test
    fun `isBusy returns false when no chats are loading`() {
        assertFalse(appState.isBusy)
    }

    @Test
    fun `sendToAll uses per-chat maxTokens override`() = runTest {
        enqueueSuccess("R1")
        enqueueSuccess("R2")

        appState.settings.apiConfigs[0].maxTokens = "500"
        chats[0].maxTokensOverride = "100"
        val jobs = appState.sendToAll("Hello", this)
        jobs.forEach { it.join() }

        val req1 = server.takeRequest()
        val body1 = JSONObject(req1.body.readUtf8())
        assertEquals(100, body1.getInt("max_tokens"))

        val req2 = server.takeRequest()
        val body2 = JSONObject(req2.body.readUtf8())
        assertEquals(500, body2.getInt("max_tokens"))
    }

    @Test
    fun `sendToAll falls back to global maxTokens when override is blank`() = runTest {
        enqueueSuccess("R1")
        enqueueSuccess("R2")

        appState.settings.apiConfigs[0].maxTokens = "300"
        chats[0].maxTokensOverride = ""
        chats[1].maxTokensOverride = ""
        val jobs = appState.sendToAll("Hello", this)
        jobs.forEach { it.join() }

        val req1 = server.takeRequest()
        val body1 = JSONObject(req1.body.readUtf8())
        assertEquals(300, body1.getInt("max_tokens"))

        val req2 = server.takeRequest()
        val body2 = JSONObject(req2.body.readUtf8())
        assertEquals(300, body2.getInt("max_tokens"))
    }

    @Test
    fun `sendToAll passes stop words`() = runTest {
        enqueueSuccess("R1")
        enqueueSuccess("R2")

        chats[0].stopWords[0] = "END"
        chats[0].addStopWord()
        chats[0].stopWords[1] = "STOP"
        val jobs = appState.sendToAll("Hello", this)
        jobs.forEach { it.join() }

        val req1 = server.takeRequest()
        val body1 = JSONObject(req1.body.readUtf8())
        assertTrue(body1.has("stop"))
        val stopArr = body1.getJSONArray("stop")
        assertEquals(2, stopArr.length())
        assertEquals("END", stopArr.getString(0))
        assertEquals("STOP", stopArr.getString(1))

        val req2 = server.takeRequest()
        val body2 = JSONObject(req2.body.readUtf8())
        assertFalse(body2.has("stop"))
    }

    @Test
    fun `sendToAll passes response format`() = runTest {
        enqueueSuccess("R1")
        enqueueSuccess("R2")

        chats[0].responseFormatType = "json_object"
        val jobs = appState.sendToAll("Hello", this)
        jobs.forEach { it.join() }

        val req1 = server.takeRequest()
        val body1 = JSONObject(req1.body.readUtf8())
        assertTrue(body1.has("response_format"))
        assertEquals("json_object", body1.getJSONObject("response_format").getString("type"))

        val req2 = server.takeRequest()
        val body2 = JSONObject(req2.body.readUtf8())
        assertFalse(body2.has("response_format"))
    }

    @Test
    fun `sendToOne sends only to target chat`() = runTest {
        enqueueSuccess("R1")

        val job = appState.sendToOne(chats[0], "Hello", this)
        job?.join()

        assertEquals(2, chats[0].messages.size)
        assertEquals(0, chats[1].messages.size)
    }

    @Test
    fun `sendToOne returns null for blank prompt`() = runTest {
        val job = appState.sendToOne(chats[0], "", this)
        assertNull(job)
    }

    @Test
    fun `sendToOne returns null for blank apiKey`() = runTest {
        appState.settings.apiConfigs[0].apiKey = ""
        val job = appState.sendToOne(chats[0], "Hello", this)
        assertNull(job)
    }

    @Test
    fun `sendToAll uses per-chat temperature override`() = runTest {
        enqueueSuccess("R1")
        enqueueSuccess("R2")

        appState.settings.apiConfigs[0].temperature = 1.0f
        chats[0].temperatureOverride = 0.3f
        val jobs = appState.sendToAll("Hello", this)
        jobs.forEach { it.join() }

        val req1 = server.takeRequest()
        val body1 = JSONObject(req1.body.readUtf8())
        assertEquals(0.3, body1.getDouble("temperature"), 0.05)

        val req2 = server.takeRequest()
        val body2 = JSONObject(req2.body.readUtf8())
        assertEquals(1.0, body2.getDouble("temperature"), 0.05)
    }

    @Test
    fun `sendToOne uses per-chat temperature override`() = runTest {
        enqueueSuccess("R1")

        appState.settings.apiConfigs[0].temperature = 1.0f
        chats[0].temperatureOverride = 0.5f
        val job = appState.sendToOne(chats[0], "Hello", this)
        job?.join()

        val req = server.takeRequest()
        val body = JSONObject(req.body.readUtf8())
        assertEquals(0.5, body.getDouble("temperature"), 0.05)
    }

    @Test
    fun `sendToOne uses per-chat maxTokens override`() = runTest {
        enqueueSuccess("R1")

        appState.settings.apiConfigs[0].maxTokens = "500"
        chats[0].maxTokensOverride = "100"
        val job = appState.sendToOne(chats[0], "Hello", this)
        job?.join()

        val req = server.takeRequest()
        val body = JSONObject(req.body.readUtf8())
        assertEquals(100, body.getInt("max_tokens"))
    }
}

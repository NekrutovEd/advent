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

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = ChatApi(baseUrl = server.url("/").toString().trimEnd('/'))
        appState = AppState(api, Dispatchers.Unconfined)
        appState.settings.apiKey = "test-key"
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
        assertEquals("Hello", AppState.applyConstraints("Hello", ""))
        assertEquals("Hello", AppState.applyConstraints("Hello", "   "))
    }

    @Test
    fun `applyConstraints appends constraints`() {
        val result = AppState.applyConstraints("Write a poem", "Use rhymes")
        assertEquals("Write a poem\n\nUse rhymes", result)
    }

    @Test
    fun `combineSystemPrompts both empty returns null`() {
        assertNull(AppState.combineSystemPrompts("", ""))
        assertNull(AppState.combineSystemPrompts("  ", "  "))
    }

    @Test
    fun `combineSystemPrompts only global returns global`() {
        assertEquals("Be helpful", AppState.combineSystemPrompts("Be helpful", ""))
    }

    @Test
    fun `combineSystemPrompts only perChat returns perChat`() {
        assertEquals("Be brief", AppState.combineSystemPrompts("", "Be brief"))
    }

    @Test
    fun `combineSystemPrompts both present combines with double newline`() {
        val result = AppState.combineSystemPrompts("Be helpful", "Be brief")
        assertEquals("Be helpful\n\nBe brief", result)
    }

    @Test
    fun `sendToAll sends to both chats without constraints`() = runTest {
        enqueueSuccess("Response 1")
        enqueueSuccess("Response 2")

        val jobs = appState.sendToAll("Hello", this)
        jobs.forEach { it.join() }

        assertEquals(2, appState.chat1.messages.size)
        assertEquals(2, appState.chat2.messages.size)
        assertEquals("Hello", appState.chat1.messages[0].content)
        assertEquals("Hello", appState.chat2.messages[0].content)
    }

    @Test
    fun `sendToAll applies per-chat constraints independently`() = runTest {
        enqueueSuccess("Response 1")
        enqueueSuccess("Response 2")

        appState.chat1.constraints = "Be verbose"
        appState.chat2.constraints = "Be brief"
        val jobs = appState.sendToAll("Tell me a story", this)
        jobs.forEach { it.join() }

        assertEquals("Tell me a story\n\nBe verbose", appState.chat1.messages[0].content)
        assertEquals("Tell me a story\n\nBe brief", appState.chat2.messages[0].content)
    }

    @Test
    fun `sendToAll with constraints on one chat only`() = runTest {
        enqueueSuccess("Response 1")
        enqueueSuccess("Response 2")

        appState.chat2.constraints = "Be brief"
        val jobs = appState.sendToAll("Tell me a story", this)
        jobs.forEach { it.join() }

        assertEquals("Tell me a story", appState.chat1.messages[0].content)
        assertEquals("Tell me a story\n\nBe brief", appState.chat2.messages[0].content)
    }

    @Test
    fun `sendToAll routes per-chat system prompt`() = runTest {
        enqueueSuccess("Response 1")
        enqueueSuccess("Response 2")

        appState.chat1.systemPrompt = "You are a poet"
        val jobs = appState.sendToAll("Hello", this)
        jobs.forEach { it.join() }

        // Verify chat1 got system prompt
        val req1 = server.takeRequest()
        val body1 = JSONObject(req1.body.readUtf8())
        val msgs1 = body1.getJSONArray("messages")
        assertEquals("system", msgs1.getJSONObject(0).getString("role"))
        assertEquals("You are a poet", msgs1.getJSONObject(0).getString("content"))

        // Verify chat2 has no system prompt
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
        appState.chat1.systemPrompt = "You are a poet"
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

        assertEquals(0, appState.chat1.messages.size)
        assertEquals(0, appState.chat2.messages.size)
    }

    @Test
    fun `sendToAll does nothing with blank apiKey`() = runTest {
        appState.settings.apiKey = ""
        val jobs = appState.sendToAll("Hello", this)
        assertTrue(jobs.isEmpty())

        assertEquals(0, appState.chat1.messages.size)
        assertEquals(0, appState.chat2.messages.size)
    }

    @Test
    fun `one chat failure does not affect the other`() = runTest {
        enqueueSuccess("Success")
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))

        val jobs = appState.sendToAll("Hello", this)
        jobs.forEach { it.join() }

        // One chat succeeded, one failed
        val successChat = if (appState.chat1.messages.size == 2) appState.chat1 else appState.chat2
        val failChat = if (appState.chat1.messages.size == 2) appState.chat2 else appState.chat1

        assertEquals(2, successChat.messages.size)
        assertNotNull(failChat.error)
    }
}

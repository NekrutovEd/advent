package state

import api.ChatApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ChatStateTest {

    private lateinit var server: MockWebServer
    private lateinit var chatState: ChatState

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = ChatApi(
            baseUrl = server.url("/").toString().trimEnd('/'),
            ioDispatcher = Dispatchers.Unconfined
        )
        chatState = ChatState(api)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueSuccess(content: String = "Response", promptTokens: Int = 0, completionTokens: Int = 0, totalTokens: Int = 0) {
        val body = JSONObject().apply {
            put("choices", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("message", JSONObject().apply {
                        put("role", "assistant")
                        put("content", content)
                    })
                })
            })
            if (promptTokens > 0 || completionTokens > 0 || totalTokens > 0) {
                put("usage", JSONObject().apply {
                    put("prompt_tokens", promptTokens)
                    put("completion_tokens", completionTokens)
                    put("total_tokens", totalTokens)
                })
            }
        }
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(body.toString())
        )
    }

    @Test
    fun `sendMessage adds user and assistant messages`() = runTest {
        enqueueSuccess("Hi there!")
        chatState.sendMessage("Hello", "key", "gpt-4o", null, null)

        assertEquals(2, chatState.messages.size)
        assertEquals("user", chatState.messages[0].role)
        assertEquals("Hello", chatState.messages[0].content)
        assertEquals("assistant", chatState.messages[1].role)
        assertEquals("Hi there!", chatState.messages[1].content)
    }

    @Test
    fun `loading is false after completion`() = runTest {
        enqueueSuccess()
        chatState.sendMessage("Hello", "key", "gpt-4o", null, null)
        assertFalse(chatState.isLoading)
    }

    @Test
    fun `error is set on failure`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error": {"message": "Invalid key"}}""")
        )
        chatState.sendMessage("Hello", "bad-key", "gpt-4o", null, null)

        assertNotNull(chatState.error)
        assertTrue(chatState.error!!.contains("401"))
        assertFalse(chatState.isLoading)
    }

    @Test
    fun `error removes user message from display`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        chatState.sendMessage("Hello", "key", "gpt-4o", null, null)

        assertEquals(0, chatState.messages.size)
    }

    @Test
    fun `clear resets all state`() = runTest {
        enqueueSuccess("Response")
        chatState.sendMessage("Hello", "key", "gpt-4o", null, null)
        assertEquals(2, chatState.messages.size)

        chatState.clear()
        assertEquals(0, chatState.messages.size)
        assertNull(chatState.error)
        assertFalse(chatState.isLoading)
    }

    @Test
    fun `clear after error resets error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        chatState.sendMessage("Hello", "key", "gpt-4o", null, null)
        assertNotNull(chatState.error)

        chatState.clear()
        assertNull(chatState.error)
    }

    @Test
    fun `sendMessage with system prompt includes it in request`() = runTest {
        enqueueSuccess("Response")
        chatState.sendMessage("Hello", "key", "gpt-4o", null, null, systemPrompt = "Be helpful")

        val recorded = server.takeRequest()
        val sentBody = JSONObject(recorded.body.readUtf8())
        val msgs = sentBody.getJSONArray("messages")
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertEquals("Be helpful", msgs.getJSONObject(0).getString("content"))
        assertEquals("user", msgs.getJSONObject(1).getString("role"))
    }

    @Test
    fun `sendMessage with timeouts succeeds`() = runTest {
        enqueueSuccess("Response")
        chatState.sendMessage(
            "Hello", "key", "gpt-4o", null, null,
            connectTimeoutSec = 5, readTimeoutSec = 10
        )

        assertEquals(2, chatState.messages.size)
    }

    @Test
    fun `clear preserves visibleOptions`() = runTest {
        chatState.toggleOption(ChatOption.SYSTEM_PROMPT)
        enqueueSuccess()
        chatState.sendMessage("Hello", "key", "gpt-4o", null, null)

        chatState.clear()

        assertTrue(ChatOption.SYSTEM_PROMPT in chatState.visibleOptions)
        assertEquals(0, chatState.messages.size)
    }

    @Test
    fun `sendMessage parses usage from response`() = runTest {
        enqueueSuccess("Hi", promptTokens = 10, completionTokens = 5, totalTokens = 15)
        chatState.sendMessage("Hello", "key", "gpt-4o", null, null)

        assertNotNull(chatState.lastUsage)
        assertEquals(10, chatState.lastUsage!!.promptTokens)
        assertEquals(5, chatState.lastUsage!!.completionTokens)
        assertEquals(15, chatState.lastUsage!!.totalTokens)
    }

    @Test
    fun `sendMessage accumulates total tokens`() = runTest {
        enqueueSuccess("R1", promptTokens = 10, completionTokens = 5, totalTokens = 15)
        chatState.sendMessage("Hello", "key", "gpt-4o", null, null)

        enqueueSuccess("R2", promptTokens = 20, completionTokens = 8, totalTokens = 28)
        chatState.sendMessage("Again", "key", "gpt-4o", null, null)

        assertEquals(30, chatState.totalPromptTokens)
        assertEquals(13, chatState.totalCompletionTokens)
        assertEquals(43, chatState.totalTokens)
    }

    @Test
    fun `clear resets usage but preserves options`() = runTest {
        enqueueSuccess("R1", promptTokens = 10, completionTokens = 5, totalTokens = 15)
        chatState.sendMessage("Hello", "key", "gpt-4o", null, null)

        chatState.stopWords[0] = "END"
        chatState.maxTokensOverride = "100"
        chatState.responseFormatType = "json_object"
        chatState.toggleOption(ChatOption.STOP_WORDS)

        chatState.clear()

        assertNull(chatState.lastUsage)
        assertEquals(0, chatState.totalPromptTokens)
        assertEquals(0, chatState.totalCompletionTokens)
        assertEquals(0, chatState.totalTokens)
        assertEquals("END", chatState.stopWords[0])
        assertEquals("100", chatState.maxTokensOverride)
        assertEquals("json_object", chatState.responseFormatType)
        assertTrue(ChatOption.STOP_WORDS in chatState.visibleOptions)
    }

    @Test
    fun `addStopWord increases list`() {
        assertEquals(1, chatState.stopWords.size)
        chatState.addStopWord()
        assertEquals(2, chatState.stopWords.size)
    }

    @Test
    fun `addStopWord caps at 4`() {
        chatState.addStopWord()
        chatState.addStopWord()
        chatState.addStopWord()
        assertEquals(4, chatState.stopWords.size)
        chatState.addStopWord()
        assertEquals(4, chatState.stopWords.size)
    }

    @Test
    fun `removeStopWord decreases list`() {
        chatState.addStopWord()
        assertEquals(2, chatState.stopWords.size)
        chatState.removeStopWord()
        assertEquals(1, chatState.stopWords.size)
    }

    @Test
    fun `removeStopWord does not go below 1`() {
        assertEquals(1, chatState.stopWords.size)
        chatState.removeStopWord()
        assertEquals(1, chatState.stopWords.size)
    }

    @Test
    fun `maxTokensOverrideOrNull returns null for blank`() {
        chatState.maxTokensOverride = ""
        assertNull(chatState.maxTokensOverrideOrNull())
        chatState.maxTokensOverride = "  "
        assertNull(chatState.maxTokensOverrideOrNull())
    }

    @Test
    fun `maxTokensOverrideOrNull returns int for valid string`() {
        chatState.maxTokensOverride = "256"
        assertEquals(256, chatState.maxTokensOverrideOrNull())
    }
}

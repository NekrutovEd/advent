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

class ChatOptionTest {

    private lateinit var server: MockWebServer
    private lateinit var chatState: ChatState

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = ChatApi(baseUrl = server.url("/").toString().trimEnd('/'))
        chatState = ChatState(api, Dispatchers.Unconfined)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueSuccess(content: String = "Response") {
        val body = JSONObject().apply {
            put("choices", org.json.JSONArray().apply {
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
    fun `visibleOptions is empty by default`() {
        assertTrue(chatState.visibleOptions.isEmpty())
    }

    @Test
    fun `toggleOption adds option`() {
        chatState.toggleOption(ChatOption.SYSTEM_PROMPT)
        assertTrue(ChatOption.SYSTEM_PROMPT in chatState.visibleOptions)
    }

    @Test
    fun `toggleOption twice removes option`() {
        chatState.toggleOption(ChatOption.SYSTEM_PROMPT)
        chatState.toggleOption(ChatOption.SYSTEM_PROMPT)
        assertFalse(ChatOption.SYSTEM_PROMPT in chatState.visibleOptions)
    }

    @Test
    fun `multiple options visible simultaneously`() {
        chatState.toggleOption(ChatOption.SYSTEM_PROMPT)
        chatState.toggleOption(ChatOption.CONSTRAINTS)
        assertTrue(ChatOption.SYSTEM_PROMPT in chatState.visibleOptions)
        assertTrue(ChatOption.CONSTRAINTS in chatState.visibleOptions)
        assertEquals(2, chatState.visibleOptions.size)
    }

    @Test
    fun `clear does not reset visibleOptions`() = runTest {
        chatState.toggleOption(ChatOption.SYSTEM_PROMPT)
        chatState.toggleOption(ChatOption.CONSTRAINTS)

        enqueueSuccess()
        chatState.sendMessage("Hello", "key", "gpt-4o", null, null)
        chatState.clear()

        assertTrue(ChatOption.SYSTEM_PROMPT in chatState.visibleOptions)
        assertTrue(ChatOption.CONSTRAINTS in chatState.visibleOptions)
    }

    @Test
    fun `ChatOption entries have correct labels`() {
        assertEquals("System Prompt", ChatOption.SYSTEM_PROMPT.label)
        assertEquals("Constraints", ChatOption.CONSTRAINTS.label)
        assertEquals("Stop Words", ChatOption.STOP_WORDS.label)
        assertEquals("Max Tokens", ChatOption.MAX_TOKENS.label)
        assertEquals("Statistics", ChatOption.STATISTICS.label)
        assertEquals("Response Format", ChatOption.RESPONSE_FORMAT.label)
    }
}

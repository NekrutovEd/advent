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
}

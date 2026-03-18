package api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ChatApiIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var chatApi: ChatApi

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        chatApi = ChatApi(
            baseUrl = server.url("/").toString().trimEnd('/'),
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun mockSuccessResponse(content: String = "Hello!"): MockResponse {
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
        return MockResponse()
            .setResponseCode(200)
            .setBody(body.toString())
            .addHeader("Content-Type", "application/json")
    }

    @Test
    fun `successful request returns parsed content`() = runTest {
        server.enqueue(mockSuccessResponse("Test response"))

        val messages = listOf(ChatMessage("user", "Hi"))
        val response = chatApi.sendMessage(messages, "test-key", "gpt-4o", null, null, null, null, null, null, null, null)

        assertEquals("Test response", response.content)
    }

    @Test
    fun `request includes authorization header`() = runTest {
        server.enqueue(mockSuccessResponse())

        val messages = listOf(ChatMessage("user", "Hi"))
        chatApi.sendMessage(messages, "my-secret-key", "gpt-4o", null, null, null, null, null, null, null, null)

        val recorded = server.takeRequest()
        assertEquals("Bearer my-secret-key", recorded.getHeader("Authorization"))
    }

    @Test
    fun `request hits correct endpoint`() = runTest {
        server.enqueue(mockSuccessResponse())

        val messages = listOf(ChatMessage("user", "Hi"))
        chatApi.sendMessage(messages, "test-key", "gpt-4o", null, null, null, null, null, null, null, null)

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
    }

    @Test
    fun `401 error throws with message`() = runTest {
        val errorBody = JSONObject().apply {
            put("error", JSONObject().apply {
                put("message", "Invalid API key")
            })
        }
        server.enqueue(MockResponse().setResponseCode(401).setBody(errorBody.toString()))

        val messages = listOf(ChatMessage("user", "Hi"))
        var caught: RuntimeException? = null
        try {
            chatApi.sendMessage(messages, "bad-key", "gpt-4o", null, null, null, null, null, null, null, null)
        } catch (e: RuntimeException) {
            caught = e
        }
        assertNotNull(caught)
        assertTrue(caught!!.message!!.contains("401"))
        assertTrue(caught.message!!.contains("Invalid API key"))
    }

    @Test
    fun `500 error throws with code`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error": {"message": "Server error"}}""")
        )

        val messages = listOf(ChatMessage("user", "Hi"))
        var caught: RuntimeException? = null
        try {
            chatApi.sendMessage(messages, "test-key", "gpt-4o", null, null, null, null, null, null, null, null)
        } catch (e: RuntimeException) {
            caught = e
        }
        assertNotNull(caught)
        assertTrue(caught!!.message!!.contains("500"))
    }

    @Test
    fun `request with custom timeouts succeeds`() = runTest {
        server.enqueue(mockSuccessResponse("Timeout test"))

        val messages = listOf(ChatMessage("user", "Hi"))
        val response = chatApi.sendMessage(messages, "test-key", "gpt-4o", null, null, null, 5, 10, null, null, null)

        assertEquals("Timeout test", response.content)
    }

    @Test
    fun `multi-turn context is sent correctly`() = runTest {
        server.enqueue(mockSuccessResponse("Response 2"))

        val messages = listOf(
            ChatMessage("user", "Hello"),
            ChatMessage("assistant", "Hi there!"),
            ChatMessage("user", "How are you?")
        )
        chatApi.sendMessage(messages, "test-key", "gpt-4o", null, null, null, null, null, null, null, null)

        val recorded = server.takeRequest()
        val sentBody = JSONObject(recorded.body.readUtf8())
        val sentMessages = sentBody.getJSONArray("messages")
        assertEquals(3, sentMessages.length())
        assertEquals("user", sentMessages.getJSONObject(0).getString("role"))
        assertEquals("assistant", sentMessages.getJSONObject(1).getString("role"))
        assertEquals("user", sentMessages.getJSONObject(2).getString("role"))
    }
}

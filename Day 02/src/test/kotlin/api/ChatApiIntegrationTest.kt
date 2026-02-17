package api

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
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
        chatApi = ChatApi(baseUrl = server.url("/").toString().trimEnd('/'))
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
    fun `successful request returns parsed content`() {
        server.enqueue(mockSuccessResponse("Test response"))

        val messages = JSONArray()
        ChatApi.addMessage(messages, "user", "Hi")
        val requestBody = ChatApi.buildRequestBody(messages)
        val responseBody = chatApi.sendMessage("test-key", requestBody)
        val content = ChatApi.parseResponseContent(responseBody)

        assertEquals("Test response", content)
    }

    @Test
    fun `request includes authorization header`() {
        server.enqueue(mockSuccessResponse())

        val messages = JSONArray()
        ChatApi.addMessage(messages, "user", "Hi")
        val requestBody = ChatApi.buildRequestBody(messages)
        chatApi.sendMessage("my-secret-key", requestBody)

        val recorded = server.takeRequest()
        assertEquals("Bearer my-secret-key", recorded.getHeader("Authorization"))
    }

    @Test
    fun `request hits correct endpoint`() {
        server.enqueue(mockSuccessResponse())

        val messages = JSONArray()
        ChatApi.addMessage(messages, "user", "Hi")
        val requestBody = ChatApi.buildRequestBody(messages)
        chatApi.sendMessage("test-key", requestBody)

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
    }

    @Test
    fun `401 error throws with message`() {
        val errorBody = JSONObject().apply {
            put("error", JSONObject().apply {
                put("message", "Invalid API key")
            })
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody(errorBody.toString())
        )

        val messages = JSONArray()
        ChatApi.addMessage(messages, "user", "Hi")
        val requestBody = ChatApi.buildRequestBody(messages)

        val exception = assertThrows(RuntimeException::class.java) {
            chatApi.sendMessage("bad-key", requestBody)
        }
        assertTrue(exception.message!!.contains("401"))
        assertTrue(exception.message!!.contains("Invalid API key"))
    }

    @Test
    fun `500 error throws with code`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error": {"message": "Server error"}}""")
        )

        val messages = JSONArray()
        ChatApi.addMessage(messages, "user", "Hi")
        val requestBody = ChatApi.buildRequestBody(messages)

        val exception = assertThrows(RuntimeException::class.java) {
            chatApi.sendMessage("test-key", requestBody)
        }
        assertTrue(exception.message!!.contains("500"))
    }

    @Test
    fun `request with custom timeouts succeeds`() {
        server.enqueue(mockSuccessResponse("Timeout test"))

        val messages = JSONArray()
        ChatApi.addMessage(messages, "user", "Hi")
        val requestBody = ChatApi.buildRequestBody(messages)
        val responseBody = chatApi.sendMessage("test-key", requestBody, connectTimeoutSec = 5, readTimeoutSec = 10)
        val content = ChatApi.parseResponseContent(responseBody)

        assertEquals("Timeout test", content)
    }

    @Test
    fun `multi-turn context is sent correctly`() {
        server.enqueue(mockSuccessResponse("Response 2"))

        val messages = JSONArray()
        ChatApi.addMessage(messages, "user", "Hello")
        ChatApi.addMessage(messages, "assistant", "Hi there!")
        ChatApi.addMessage(messages, "user", "How are you?")
        val requestBody = ChatApi.buildRequestBody(messages)
        chatApi.sendMessage("test-key", requestBody)

        val recorded = server.takeRequest()
        val sentBody = JSONObject(recorded.body.readUtf8())
        val sentMessages = sentBody.getJSONArray("messages")
        assertEquals(3, sentMessages.length())
        assertEquals("user", sentMessages.getJSONObject(0).getString("role"))
        assertEquals("assistant", sentMessages.getJSONObject(1).getString("role"))
        assertEquals("user", sentMessages.getJSONObject(2).getString("role"))
    }
}

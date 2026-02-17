package api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ChatApiUnitTest {

    @Test
    fun `addMessage appends to array`() {
        val messages = JSONArray()
        ChatApi.addMessage(messages, "user", "Hello")
        assertEquals(1, messages.length())
        val msg = messages.getJSONObject(0)
        assertEquals("user", msg.getString("role"))
        assertEquals("Hello", msg.getString("content"))
    }

    @Test
    fun `addMessage preserves existing messages`() {
        val messages = JSONArray()
        ChatApi.addMessage(messages, "user", "First")
        ChatApi.addMessage(messages, "assistant", "Second")
        assertEquals(2, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
    }

    @Test
    fun `buildRequestBody uses default model`() {
        val messages = JSONArray()
        ChatApi.addMessage(messages, "user", "Hi")
        val body = ChatApi.buildRequestBody(messages)
        val json = JSONObject(body)
        assertEquals("gpt-4o", json.getString("model"))
        assertEquals(1, json.getJSONArray("messages").length())
    }

    @Test
    fun `buildRequestBody uses custom model`() {
        val messages = JSONArray()
        ChatApi.addMessage(messages, "user", "Hi")
        val body = ChatApi.buildRequestBody(messages, model = "gpt-3.5-turbo")
        val json = JSONObject(body)
        assertEquals("gpt-3.5-turbo", json.getString("model"))
    }

    @Test
    fun `buildRequestBody includes temperature when set`() {
        val messages = JSONArray()
        ChatApi.addMessage(messages, "user", "Hi")
        val body = ChatApi.buildRequestBody(messages, temperature = 0.5)
        val json = JSONObject(body)
        assertEquals(0.5, json.getDouble("temperature"), 0.001)
    }

    @Test
    fun `buildRequestBody excludes temperature when null`() {
        val messages = JSONArray()
        ChatApi.addMessage(messages, "user", "Hi")
        val body = ChatApi.buildRequestBody(messages)
        val json = JSONObject(body)
        assertFalse(json.has("temperature"))
    }

    @Test
    fun `buildRequestBody includes maxTokens when set`() {
        val messages = JSONArray()
        ChatApi.addMessage(messages, "user", "Hi")
        val body = ChatApi.buildRequestBody(messages, maxTokens = 100)
        val json = JSONObject(body)
        assertEquals(100, json.getInt("max_tokens"))
    }

    @Test
    fun `buildRequestBody excludes maxTokens when null`() {
        val messages = JSONArray()
        ChatApi.addMessage(messages, "user", "Hi")
        val body = ChatApi.buildRequestBody(messages)
        val json = JSONObject(body)
        assertFalse(json.has("max_tokens"))
    }

    @Test
    fun `buildRequestBody with systemPrompt inserts system message first`() {
        val messages = JSONArray()
        ChatApi.addMessage(messages, "user", "Hi")
        val body = ChatApi.buildRequestBody(messages, systemPrompt = "You are helpful")
        val json = JSONObject(body)
        val msgs = json.getJSONArray("messages")
        assertEquals(2, msgs.length())
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertEquals("You are helpful", msgs.getJSONObject(0).getString("content"))
        assertEquals("user", msgs.getJSONObject(1).getString("role"))
    }

    @Test
    fun `buildRequestBody without systemPrompt has no system message`() {
        val messages = JSONArray()
        ChatApi.addMessage(messages, "user", "Hi")
        val body = ChatApi.buildRequestBody(messages)
        val json = JSONObject(body)
        val msgs = json.getJSONArray("messages")
        assertEquals(1, msgs.length())
        assertEquals("user", msgs.getJSONObject(0).getString("role"))
    }

    @Test
    fun `buildRequestBody with blank systemPrompt has no system message`() {
        val messages = JSONArray()
        ChatApi.addMessage(messages, "user", "Hi")
        val body = ChatApi.buildRequestBody(messages, systemPrompt = "   ")
        val json = JSONObject(body)
        val msgs = json.getJSONArray("messages")
        assertEquals(1, msgs.length())
        assertEquals("user", msgs.getJSONObject(0).getString("role"))
    }

    @Test
    fun `buildRequestBody with systemPrompt does not mutate original messages`() {
        val messages = JSONArray()
        ChatApi.addMessage(messages, "user", "Hi")
        val originalLength = messages.length()
        ChatApi.buildRequestBody(messages, systemPrompt = "You are helpful")
        assertEquals(originalLength, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
    }

    @Test
    fun `parseResponseContent extracts content`() {
        val response = """
            {
                "choices": [{
                    "message": {
                        "role": "assistant",
                        "content": "Hello there!"
                    }
                }]
            }
        """.trimIndent()
        assertEquals("Hello there!", ChatApi.parseResponseContent(response))
    }

    @Test
    fun `parseResponseContent throws on invalid json`() {
        assertThrows(Exception::class.java) {
            ChatApi.parseResponseContent("not json")
        }
    }

    @Test
    fun `parseResponseContent throws on missing choices`() {
        assertThrows(Exception::class.java) {
            ChatApi.parseResponseContent("""{"id": "123"}""")
        }
    }
}

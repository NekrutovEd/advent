package api

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ChatApiUnitTest {

    @Test
    fun `buildRequestBody uses default model`() {
        val messages = listOf(ChatMessage("user", "Hi"))
        val body = ChatApi.buildRequestBody(messages)
        val json = JSONObject(body)
        assertEquals("gpt-4o", json.getString("model"))
        assertEquals(1, json.getJSONArray("messages").length())
    }

    @Test
    fun `buildRequestBody uses custom model`() {
        val messages = listOf(ChatMessage("user", "Hi"))
        val body = ChatApi.buildRequestBody(messages, model = "gpt-3.5-turbo")
        val json = JSONObject(body)
        assertEquals("gpt-3.5-turbo", json.getString("model"))
    }

    @Test
    fun `buildRequestBody includes temperature when set`() {
        val messages = listOf(ChatMessage("user", "Hi"))
        val body = ChatApi.buildRequestBody(messages, temperature = 0.5)
        val json = JSONObject(body)
        assertEquals(0.5, json.getDouble("temperature"), 0.001)
    }

    @Test
    fun `buildRequestBody excludes temperature when null`() {
        val messages = listOf(ChatMessage("user", "Hi"))
        val body = ChatApi.buildRequestBody(messages)
        val json = JSONObject(body)
        assertFalse(json.has("temperature"))
    }

    @Test
    fun `buildRequestBody includes maxTokens when set`() {
        val messages = listOf(ChatMessage("user", "Hi"))
        val body = ChatApi.buildRequestBody(messages, maxTokens = 100)
        val json = JSONObject(body)
        assertEquals(100, json.getInt("max_tokens"))
    }

    @Test
    fun `buildRequestBody excludes maxTokens when null`() {
        val messages = listOf(ChatMessage("user", "Hi"))
        val body = ChatApi.buildRequestBody(messages)
        val json = JSONObject(body)
        assertFalse(json.has("max_tokens"))
    }

    @Test
    fun `buildRequestBody with systemPrompt inserts system message first`() {
        val messages = listOf(ChatMessage("user", "Hi"))
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
        val messages = listOf(ChatMessage("user", "Hi"))
        val body = ChatApi.buildRequestBody(messages)
        val json = JSONObject(body)
        val msgs = json.getJSONArray("messages")
        assertEquals(1, msgs.length())
        assertEquals("user", msgs.getJSONObject(0).getString("role"))
    }

    @Test
    fun `buildRequestBody with blank systemPrompt has no system message`() {
        val messages = listOf(ChatMessage("user", "Hi"))
        val body = ChatApi.buildRequestBody(messages, systemPrompt = "   ")
        val json = JSONObject(body)
        val msgs = json.getJSONArray("messages")
        assertEquals(1, msgs.length())
        assertEquals("user", msgs.getJSONObject(0).getString("role"))
    }

    @Test
    fun `buildRequestBody with systemPrompt does not mutate original messages`() {
        val messages = listOf(ChatMessage("user", "Hi"))
        val originalSize = messages.size
        ChatApi.buildRequestBody(messages, systemPrompt = "You are helpful")
        assertEquals(originalSize, messages.size)
        assertEquals("user", messages[0].role)
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

    @Test
    fun `buildRequestBody includes stop when set`() {
        val messages = listOf(ChatMessage("user", "Hi"))
        val body = ChatApi.buildRequestBody(messages, stop = listOf("END", "STOP"))
        val json = JSONObject(body)
        assertTrue(json.has("stop"))
        val stopArr = json.getJSONArray("stop")
        assertEquals(2, stopArr.length())
        assertEquals("END", stopArr.getString(0))
        assertEquals("STOP", stopArr.getString(1))
    }

    @Test
    fun `buildRequestBody excludes stop when null`() {
        val messages = listOf(ChatMessage("user", "Hi"))
        val body = ChatApi.buildRequestBody(messages, stop = null)
        val json = JSONObject(body)
        assertFalse(json.has("stop"))
    }

    @Test
    fun `buildRequestBody excludes stop when all blank`() {
        val messages = listOf(ChatMessage("user", "Hi"))
        val body = ChatApi.buildRequestBody(messages, stop = listOf("", "  ", ""))
        val json = JSONObject(body)
        assertFalse(json.has("stop"))
    }

    @Test
    fun `buildRequestBody includes response_format json_object`() {
        val messages = listOf(ChatMessage("user", "Hi"))
        val body = ChatApi.buildRequestBody(messages, responseFormat = "json_object")
        val json = JSONObject(body)
        assertTrue(json.has("response_format"))
        assertEquals("json_object", json.getJSONObject("response_format").getString("type"))
    }

    @Test
    fun `buildRequestBody includes response_format json_schema with schema`() {
        val messages = listOf(ChatMessage("user", "Hi"))
        val schema = """{"type":"object","properties":{"name":{"type":"string"}}}"""
        val body = ChatApi.buildRequestBody(messages, responseFormat = "json_schema", jsonSchema = schema)
        val json = JSONObject(body)
        assertTrue(json.has("response_format"))
        val rf = json.getJSONObject("response_format")
        assertEquals("json_schema", rf.getString("type"))
        val js = rf.getJSONObject("json_schema")
        assertEquals("custom_schema", js.getString("name"))
        assertTrue(js.getBoolean("strict"))
        assertEquals("object", js.getJSONObject("schema").getString("type"))
    }

    @Test
    fun `buildRequestBody excludes response_format for text`() {
        val messages = listOf(ChatMessage("user", "Hi"))
        val body = ChatApi.buildRequestBody(messages, responseFormat = "text")
        val json = JSONObject(body)
        assertFalse(json.has("response_format"))
    }

    @Test
    fun `parseUsage extracts token counts`() {
        val response = """
            {
                "choices": [{"message": {"role": "assistant", "content": "Hi"}}],
                "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 5,
                    "total_tokens": 15
                }
            }
        """.trimIndent()
        val usage = ChatApi.parseUsage(response)
        assertNotNull(usage)
        assertEquals(10, usage!!.promptTokens)
        assertEquals(5, usage.completionTokens)
        assertEquals(15, usage.totalTokens)
    }

    @Test
    fun `parseUsage returns null when usage missing`() {
        val response = """
            {
                "choices": [{"message": {"role": "assistant", "content": "Hi"}}]
            }
        """.trimIndent()
        assertNull(ChatApi.parseUsage(response))
    }
}

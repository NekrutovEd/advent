package state

import api.ChatApi
import i18n.EnStrings
import i18n.RuStrings
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
    fun `toggleOption off resets system prompt`() {
        chatState.toggleOption(ChatOption.SYSTEM_PROMPT)
        chatState.systemPrompt = "Custom prompt"
        chatState.toggleOption(ChatOption.SYSTEM_PROMPT)
        assertEquals("", chatState.systemPrompt)
    }

    @Test
    fun `toggleOption off resets constraints`() {
        chatState.toggleOption(ChatOption.CONSTRAINTS)
        chatState.constraints = "Be brief"
        chatState.toggleOption(ChatOption.CONSTRAINTS)
        assertEquals("", chatState.constraints)
    }

    @Test
    fun `toggleOption off resets stop words`() {
        chatState.toggleOption(ChatOption.STOP_WORDS)
        chatState.stopWords[0] = "END"
        chatState.addStopWord()
        chatState.toggleOption(ChatOption.STOP_WORDS)
        assertEquals(1, chatState.stopWords.size)
        assertEquals("", chatState.stopWords[0])
    }

    @Test
    fun `toggleOption off resets max tokens`() {
        chatState.toggleOption(ChatOption.MAX_TOKENS)
        chatState.maxTokensOverride = "100"
        chatState.toggleOption(ChatOption.MAX_TOKENS)
        assertEquals("", chatState.maxTokensOverride)
    }

    @Test
    fun `toggleOption off resets statistics`() = runTest {
        chatState.toggleOption(ChatOption.STATISTICS)
        val body = JSONObject().apply {
            put("choices", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("message", JSONObject().apply {
                        put("role", "assistant")
                        put("content", "Hi")
                    })
                })
            })
            put("usage", JSONObject().apply {
                put("prompt_tokens", 10)
                put("completion_tokens", 5)
                put("total_tokens", 15)
            })
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(body.toString()))
        chatState.sendMessage("Hello", "key", "gpt-4o", null, null)
        assertNotNull(chatState.lastUsage)

        chatState.toggleOption(ChatOption.STATISTICS)
        assertNull(chatState.lastUsage)
        assertEquals(0, chatState.totalPromptTokens)
    }

    @Test
    fun `toggleOption off resets response format`() {
        chatState.toggleOption(ChatOption.RESPONSE_FORMAT)
        chatState.responseFormatType = "json_object"
        chatState.jsonSchema = """{"type":"object"}"""
        chatState.toggleOption(ChatOption.RESPONSE_FORMAT)
        assertEquals("text", chatState.responseFormatType)
        assertEquals("", chatState.jsonSchema)
    }

    @Test
    fun `ChatOption entries have correct EN labels`() {
        assertEquals("System Prompt", ChatOption.SYSTEM_PROMPT.label(EnStrings))
        assertEquals("Constraints", ChatOption.CONSTRAINTS.label(EnStrings))
        assertEquals("Stop Words", ChatOption.STOP_WORDS.label(EnStrings))
        assertEquals("Max Tokens", ChatOption.MAX_TOKENS.label(EnStrings))
        assertEquals("Statistics", ChatOption.STATISTICS.label(EnStrings))
        assertEquals("Response Format", ChatOption.RESPONSE_FORMAT.label(EnStrings))
    }

    @Test
    fun `ChatOption entries have correct RU labels`() {
        assertEquals("Системный промпт", ChatOption.SYSTEM_PROMPT.label(RuStrings))
        assertEquals("Ограничения", ChatOption.CONSTRAINTS.label(RuStrings))
        assertEquals("Стоп-слова", ChatOption.STOP_WORDS.label(RuStrings))
        assertEquals("Макс. токенов", ChatOption.MAX_TOKENS.label(RuStrings))
        assertEquals("Статистика", ChatOption.STATISTICS.label(RuStrings))
        assertEquals("Формат ответа", ChatOption.RESPONSE_FORMAT.label(RuStrings))
    }
}

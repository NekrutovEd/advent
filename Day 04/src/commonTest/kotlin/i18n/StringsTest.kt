package i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class StringsTest {

    @Test
    fun `stringsFor returns EnStrings for EN`() {
        assertSame(EnStrings, stringsFor(Lang.EN))
    }

    @Test
    fun `stringsFor returns RuStrings for RU`() {
        assertSame(RuStrings, stringsFor(Lang.RU))
    }

    @Test
    fun `all EN strings are not blank`() {
        assertAllStringsNotBlank(EnStrings)
    }

    @Test
    fun `all RU strings are not blank`() {
        assertAllStringsNotBlank(RuStrings)
    }

    @Test
    fun `EN and RU differ for key strings`() {
        val en = EnStrings
        val ru = RuStrings
        assertNotEquals(en.send, ru.send)
        assertNotEquals(en.clear, ru.clear)
        assertNotEquals(en.settingsTitle, ru.settingsTitle)
        assertNotEquals(en.enterMessage, ru.enterMessage)
        assertNotEquals(en.optionStatistics, ru.optionStatistics)
        assertNotEquals(en.save, ru.save)
        assertNotEquals(en.cancel, ru.cancel)
        assertNotEquals(en.language, ru.language)
    }

    @Test
    fun `parameterized strings format correctly for EN`() {
        val en = EnStrings
        assertEquals("Stop 1", en.stopWordPlaceholder(0))
        assertEquals("Stop 3", en.stopWordPlaceholder(2))
        assertEquals("Chat 1", en.chatTitle(0))
        assertEquals("Chat 2", en.chatTitle(1))
        assertEquals("Temperature: 0.7", en.temperatureValue("0.7"))
        assertEquals("Last: P:10 C:5 T:15", en.lastStatsLine(10, 5, 15))
        assertEquals("Total: P:100 C:50 T:150", en.totalStatsLine(100, 50, 150))
    }

    @Test
    fun `parameterized strings format correctly for RU`() {
        val ru = RuStrings
        assertEquals("Стоп 1", ru.stopWordPlaceholder(0))
        assertEquals("Стоп 3", ru.stopWordPlaceholder(2))
        assertEquals("Чат 1", ru.chatTitle(0))
        assertEquals("Чат 2", ru.chatTitle(1))
        assertEquals("Температура: 0.7", ru.temperatureValue("0.7"))
        assertEquals("Посл: П:10 О:5 В:15", ru.lastStatsLine(10, 5, 15))
        assertEquals("Итого: П:100 О:50 В:150", ru.totalStatsLine(100, 50, 150))
    }

    @Test
    fun `symbols are the same in both languages`() {
        val en = EnStrings
        val ru = RuStrings
        assertEquals(en.jsonSchemaPlaceholder.contains("JSON"), ru.jsonSchemaPlaceholder.contains("JSON"))
    }

    private fun assertAllStringsNotBlank(strings: Strings) {
        assertFalse(strings.settingsTitle.isBlank(), "settingsTitle")
        assertFalse(strings.apiKey.isBlank(), "apiKey")
        assertFalse(strings.model.isBlank(), "model")
        assertFalse(strings.maxTokensLabel.isBlank(), "maxTokensLabel")
        assertFalse(strings.connectTimeout.isBlank(), "connectTimeout")
        assertFalse(strings.readTimeout.isBlank(), "readTimeout")
        assertFalse(strings.save.isBlank(), "save")
        assertFalse(strings.cancel.isBlank(), "cancel")
        assertFalse(strings.language.isBlank(), "language")
        assertFalse(strings.send.isBlank(), "send")
        assertFalse(strings.sendAll.isBlank(), "sendAll")
        assertFalse(strings.clear.isBlank(), "clear")
        assertFalse(strings.clearAll.isBlank(), "clearAll")
        assertFalse(strings.enterMessage.isBlank(), "enterMessage")
        assertFalse(strings.systemPromptGlobal.isBlank(), "systemPromptGlobal")
        assertFalse(strings.systemPromptPerChat.isBlank(), "systemPromptPerChat")
        assertFalse(strings.constraintsPerChat.isBlank(), "constraintsPerChat")
        assertFalse(strings.constraintsPlaceholder.isBlank(), "constraintsPlaceholder")
        assertFalse(strings.maxTokensOverride.isBlank(), "maxTokensOverride")
        assertFalse(strings.jsonSchemaPlaceholder.isBlank(), "jsonSchemaPlaceholder")
        assertFalse(strings.optionStatistics.isBlank(), "optionStatistics")
        assertFalse(strings.optionSystemPrompt.isBlank(), "optionSystemPrompt")
        assertFalse(strings.optionConstraints.isBlank(), "optionConstraints")
        assertFalse(strings.optionStopWords.isBlank(), "optionStopWords")
        assertFalse(strings.optionMaxTokens.isBlank(), "optionMaxTokens")
        assertFalse(strings.optionResponseFormat.isBlank(), "optionResponseFormat")
        assertFalse(strings.lastRequest.isBlank(), "lastRequest")
        assertFalse(strings.sessionTotal.isBlank(), "sessionTotal")
        assertFalse(strings.promptTokens.isBlank(), "promptTokens")
        assertFalse(strings.completionTokens.isBlank(), "completionTokens")
        assertFalse(strings.totalTokens.isBlank(), "totalTokens")
        assertFalse(strings.promptTokensDesc.isBlank(), "promptTokensDesc")
        assertFalse(strings.completionTokensDesc.isBlank(), "completionTokensDesc")
        assertFalse(strings.totalTokensDesc.isBlank(), "totalTokensDesc")
        assertFalse(strings.allPromptTokensDesc.isBlank(), "allPromptTokensDesc")
        assertFalse(strings.allCompletionTokensDesc.isBlank(), "allCompletionTokensDesc")
        assertFalse(strings.allTotalTokensDesc.isBlank(), "allTotalTokensDesc")
        assertFalse(strings.stopWordPlaceholder(0).isBlank(), "stopWordPlaceholder")
        assertFalse(strings.chatTitle(0).isBlank(), "chatTitle")
        assertFalse(strings.temperatureValue("1.0").isBlank(), "temperatureValue")
        assertFalse(strings.lastStatsLine(1, 2, 3).isBlank(), "lastStatsLine")
        assertFalse(strings.totalStatsLine(1, 2, 3).isBlank(), "totalStatsLine")
    }
}

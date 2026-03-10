package i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import state.SettingsState

class LangSettingsTest {

    @Test
    fun `default language is EN`() {
        val settings = SettingsState()
        assertEquals(Lang.EN, settings.lang)
    }

    @Test
    fun `can switch language to RU`() {
        val settings = SettingsState()
        settings.lang = Lang.RU
        assertEquals(Lang.RU, settings.lang)
    }

    @Test
    fun `stringsFor matches settings lang`() {
        val settings = SettingsState()

        assertEquals(EnStrings, stringsFor(settings.lang))

        settings.lang = Lang.RU
        assertEquals(RuStrings, stringsFor(settings.lang))

        settings.lang = Lang.EN
        assertEquals(EnStrings, stringsFor(settings.lang))
    }
}

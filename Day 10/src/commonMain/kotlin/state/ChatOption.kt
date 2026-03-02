package state

import i18n.Strings

enum class ChatOption {
    STATISTICS,
    SYSTEM_PROMPT,
    CONSTRAINTS,
    STOP_WORDS,
    MAX_TOKENS,
    RESPONSE_FORMAT,
    CONTEXT,
    MODEL,
    TEMPERATURE;

    fun label(strings: Strings): String = when (this) {
        STATISTICS -> strings.optionStatistics
        SYSTEM_PROMPT -> strings.optionSystemPrompt
        CONSTRAINTS -> strings.optionConstraints
        STOP_WORDS -> strings.optionStopWords
        MAX_TOKENS -> strings.optionMaxTokens
        RESPONSE_FORMAT -> strings.optionResponseFormat
        CONTEXT -> strings.optionContext
        MODEL -> strings.model
        TEMPERATURE -> strings.optionTemperature
    }
}

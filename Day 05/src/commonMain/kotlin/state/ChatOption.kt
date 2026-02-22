package state

import i18n.Strings

enum class ChatOption {
    STATISTICS,
    SYSTEM_PROMPT,
    CONSTRAINTS,
    STOP_WORDS,
    MAX_TOKENS,
    TEMPERATURE,
    MODEL,
    RESPONSE_FORMAT;

    fun label(strings: Strings): String = when (this) {
        STATISTICS -> strings.optionStatistics
        SYSTEM_PROMPT -> strings.optionSystemPrompt
        CONSTRAINTS -> strings.optionConstraints
        STOP_WORDS -> strings.optionStopWords
        MAX_TOKENS -> strings.optionMaxTokens
        TEMPERATURE -> strings.optionTemperature
        MODEL -> strings.model
        RESPONSE_FORMAT -> strings.optionResponseFormat
    }
}

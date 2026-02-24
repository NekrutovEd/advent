package i18n

interface Strings {
    // Settings dialog
    val settingsTitle: String
    val apiKey: String
    val model: String
    val maxTokensLabel: String
    val connectTimeout: String
    val readTimeout: String
    val save: String
    val cancel: String
    val language: String

    // Buttons
    val send: String
    val sendAll: String
    val clear: String
    val clearAll: String

    // Placeholders
    val enterMessage: String
    val systemPromptGlobal: String
    val systemPromptPerChat: String
    val constraintsPerChat: String
    val constraintsPlaceholder: String
    val maxTokensOverride: String
    val jsonSchemaPlaceholder: String

    // Chat options
    val optionStatistics: String
    val optionSystemPrompt: String
    val optionConstraints: String
    val optionStopWords: String
    val optionMaxTokens: String
    val optionTemperature: String
    val optionResponseFormat: String
    val optionHistory: String
    val optionSummarization: String

    // Summarization
    val autoSummarize: String
    val summarizing: String
    val summaryLabel: String
    val summarizeThresholdLabel: String
    val keepLastLabel: String
    val sendHistory: String
    val globalHistory: String
    val globalSummarization: String
    val freshSummaryLabel: String
    fun summaryCountLabel(n: Int): String
    fun requestHistoryLabel(n: Int): String

    // Statistics tooltip
    val lastRequest: String
    val sessionTotal: String
    val promptTokens: String
    val completionTokens: String
    val totalTokens: String
    val promptTokensDesc: String
    val completionTokensDesc: String
    val totalTokensDesc: String
    val allPromptTokensDesc: String
    val allCompletionTokensDesc: String
    val allTotalTokensDesc: String

    // Parameterized
    fun stopWordPlaceholder(index: Int): String
    fun chatTitle(index: Int): String
    fun temperatureValue(temp: String): String
    fun lastStatsLine(p: Int, c: Int, t: Int): String
    fun totalStatsLine(p: Int, c: Int, t: Int): String
}

fun stringsFor(lang: Lang): Strings = when (lang) {
    Lang.EN -> EnStrings
    Lang.RU -> RuStrings
}

package i18n

object RuStrings : Strings {
    // Settings dialog
    override val settingsTitle = "Настройки"
    override val apiKey = "API-ключ"
    override val model = "Модель"
    override val maxTokensLabel = "Макс. токенов (пусто = по умолч.)"
    override val connectTimeout = "Таймаут подключения (сек)"
    override val readTimeout = "Таймаут чтения (сек)"
    override val save = "Сохранить"
    override val cancel = "Отмена"
    override val language = "Язык"

    // Buttons
    override val send = "Отправить"
    override val sendAll = "Отправить всем"
    override val clear = "Очистить"
    override val clearAll = "Очистить всё"

    // Placeholders
    override val enterMessage = "Введите сообщение..."
    override val systemPromptGlobal = "Системный промпт (общий)..."
    override val systemPromptPerChat = "Системный промпт (чат)..."
    override val constraintsPerChat = "Доп. промпт (чат)..."
    override val constraintsPlaceholder = "Ограничения (к промпту)..."
    override val maxTokensOverride = "Макс. токенов (перезапись)..."
    override val jsonSchemaPlaceholder = "JSON-схема..."

    // Chat options
    override val optionStatistics = "Статистика"
    override val optionSystemPrompt = "Системный промпт"
    override val optionConstraints = "Ограничения"
    override val optionStopWords = "Стоп-слова"
    override val optionMaxTokens = "Макс. токенов"
    override val optionTemperature = "Температура"
    override val optionResponseFormat = "Формат ответа"
    override val optionContext = "Контекст"

    // Summarization
    override val autoSummarize = "Авто-суммаризация"
    override val summarizing = "Суммаризация истории..."
    override val summaryLabel = "Саммари"
    override val summarizeThresholdLabel = "Суммаризировать после N сообщений"
    override val keepLastLabel = "Оставить последних N сообщений"
    override val sendHistory = "Отправлять историю диалога"
    override val globalContext = "Контекст (глобальные настройки)"
    override val freshSummaryLabel = "Применена суммаризация"
    override val slidingWindowLabel = "Окно сообщений (пусто = все)"
    override val extractFacts = "Извлекать ключевые факты"
    override val extractingFacts = "Извлечение фактов..."
    override val stickyFactsLabel = "Ключевые факты"
    override val stickyFactsPlaceholder = "Ключевые факты из диалога..."
    override fun summaryCountLabel(n: Int) = "Саммари создано: $n"
    override fun requestHistoryLabel(n: Int) = "История ($n сообщений)"

    // Statistics tooltip
    override val lastRequest = "Последний запрос"
    override val sessionTotal = "Итого за сессию"
    override val promptTokens = "Токены запроса"
    override val completionTokens = "Токены ответа"
    override val totalTokens = "Всего токенов"
    override val promptTokensDesc = "Токены, отправленные в запросе"
    override val completionTokensDesc = "Токены, сгенерированные моделью"
    override val totalTokensDesc = "Сумма токенов запроса и ответа"
    override val allPromptTokensDesc = "Все токены запросов за сессию"
    override val allCompletionTokensDesc = "Все токены ответов за сессию"
    override val allTotalTokensDesc = "Все токены за сессию"

    // Session tabs
    override val archiveLabel = "Архив"
    override val clearArchive = "Очистить архив"
    override fun sessionDefaultName(n: Int) = "New"

    // Parameterized
    override fun stopWordPlaceholder(index: Int) = "Стоп ${index + 1}"
    override fun chatTitle(index: Int) = "Чат ${index + 1}"
    override fun temperatureValue(temp: String) = "Температура: $temp"
    override fun lastStatsLine(p: Int, c: Int, t: Int) = "Посл: П:$p О:$c В:$t"
    override fun totalStatsLine(p: Int, c: Int, t: Int) = "Итого: П:$p О:$c В:$t"
}

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
    override val extractMemory = "Извлекать память"
    override val extractingMemory = "Извлечение памяти..."

    // Profile selector
    override val profileSectionTitle = "Профиль"
    override val noProfileSelected = "Нет профиля"
    override val renameProfile = "Переименовать"
    override val addProfileItemPlaceholder = "Добавить предпочтение..."
    override val noProfileItems = "Нет предпочтений"

    // Memory panel
    override val memoryPanelTitle = "Память"
    override val sessionMemoryTab = "Сессия"
    override val globalMemoryTab = "Общая"
    override val addMemoryPlaceholder = "Добавить элемент..."
    override val moveToGlobal = "В общую память"
    override val noMemoryItems = "Нет элементов"
    override val memorySourceAuto = "авто"
    override val memorySourceManual = "вручную"
    override val memorySourcePromoted = "повышен"
    override val sessionMemoryScopeLabel = "только эта сессия"
    override val globalMemoryScopeLabel = "сохраняется между сессиями"
    override fun memoryTokenEstimate(tokens: Int) = "~$tokens токенов"
    override fun summaryCountLabel(n: Int) = "Саммари создано: $n"
    override fun requestHistoryLabel(n: Int) = "История ($n сообщений)"

    // Profile dialog
    override val profileDialogTitle = "Редактирование профилей"
    override val editProfile = "Изменить"
    override val deleteProfileConfirmTitle = "Удалить профиль"
    override fun deleteProfileConfirmBody(name: String) = "Удалить «$name»?"
    override val confirm = "Удалить"
    override val addProfile = "Добавить профиль"
    override val profileItemsHeader = "Предпочтения"

    // Task tracking
    override val optionTaskTracking = "Отслеживание задач"
    override val taskTrackingLabel = "Отслеживать фазы задачи"
    override val taskPlanning = "План"
    override val taskExecution = "Выполн."
    override val taskValidation = "Провер."
    override val taskDone = "Готово"
    override val taskPause = "Пауза"
    override val taskResume = "Продолжить"
    override val taskReset = "Сброс"
    override val taskExtracting = "анализ..."

    override val taskPausedBanner = "Задача приостановлена. Отправьте сообщение для продолжения."
    override fun taskTransitionBlocked(from: String, to: String) =
        "Переход заблокирован: $from → $to. Пропуск фаз запрещён."

    // Invariants
    override val invariantsTab = "Инварианты"
    override val invariantsScopeLabel = "проверяются после каждого ответа"
    override val addInvariantPlaceholder = "Добавить инвариант..."
    override val noInvariants = "Нет инвариантов"
    override val invariantViolationLabel = "Нарушение инварианта"
    override val checkingInvariants = "Проверка инвариантов..."

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

    // MCP
    override val mcpSectionTitle = "MCP-сервер"
    override val mcpServerCommand = "Команда (напр. npx)"
    override val mcpServerArgs = "Аргументы"
    override val mcpConnect = "Подключить"
    override val mcpDisconnect = "Отключить"
    override val mcpConnecting = "Подключение..."
    override val mcpConnected = "Подключён"
    override val mcpDisconnected = "Отключён"
    override val mcpToolsTitle = "Доступные инструменты"
    override val mcpNoTools = "Нет инструментов"
    override fun mcpToolCount(n: Int) = "$n инструментов"
    override fun mcpServerInfo(name: String) = "Сервер: $name"

    // Parameterized
    override fun stopWordPlaceholder(index: Int) = "Стоп ${index + 1}"
    override fun chatTitle(index: Int) = "Чат ${index + 1}"
    override fun temperatureValue(temp: String) = "Температура: $temp"
    override fun lastStatsLine(p: Int, c: Int, t: Int) = "Посл: П:$p О:$c В:$t"
    override fun totalStatsLine(p: Int, c: Int, t: Int) = "Итого: П:$p О:$c В:$t"
}

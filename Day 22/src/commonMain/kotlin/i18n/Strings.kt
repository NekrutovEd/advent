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
    val optionContext: String

    // Summarization
    val autoSummarize: String
    val summarizing: String
    val summaryLabel: String
    val summarizeThresholdLabel: String
    val keepLastLabel: String
    val sendHistory: String
    val globalContext: String
    val freshSummaryLabel: String
    val slidingWindowLabel: String
    val extractFacts: String
    val extractingFacts: String
    val stickyFactsLabel: String
    val stickyFactsPlaceholder: String
    val extractMemory: String
    val extractingMemory: String

    // Profile selector
    val profileSectionTitle: String
    val noProfileSelected: String
    val renameProfile: String
    val addProfileItemPlaceholder: String
    val noProfileItems: String

    // Memory panel
    val memoryPanelTitle: String
    val sessionMemoryTab: String
    val globalMemoryTab: String
    val addMemoryPlaceholder: String
    val moveToGlobal: String
    val noMemoryItems: String
    val memorySourceAuto: String
    val memorySourceManual: String
    val memorySourcePromoted: String
    val sessionMemoryScopeLabel: String
    val globalMemoryScopeLabel: String
    fun memoryTokenEstimate(tokens: Int): String
    fun summaryCountLabel(n: Int): String
    fun requestHistoryLabel(n: Int): String

    // Profile dialog
    val profileDialogTitle: String
    val editProfile: String
    val deleteProfileConfirmTitle: String
    fun deleteProfileConfirmBody(name: String): String
    val confirm: String
    val addProfile: String
    val profileItemsHeader: String

    // Task tracking
    val optionTaskTracking: String
    val optionRag: String
    val ragEnabled: String
    val ragDisabled: String
    val ragLoading: String
    val ragSourcesLabel: String
    val ragNoIndex: String
    val taskTrackingLabel: String
    val taskPlanning: String
    val taskExecution: String
    val taskValidation: String
    val taskDone: String
    val taskPause: String
    val taskResume: String
    val taskReset: String
    val taskExtracting: String

    val taskPausedBanner: String
    fun taskTransitionBlocked(from: String, to: String): String

    // Invariants
    val invariantsTab: String
    val invariantsScopeLabel: String
    val addInvariantPlaceholder: String
    val noInvariants: String
    val invariantViolationLabel: String
    val checkingInvariants: String

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

    // Session tabs
    val archiveLabel: String
    val clearArchive: String
    fun sessionDefaultName(n: Int): String

    // MCP
    val mcpSectionTitle: String
    val mcpServerCommand: String
    val mcpServerArgs: String
    val mcpConnect: String
    val mcpDisconnect: String
    val mcpConnecting: String
    val mcpConnected: String
    val mcpDisconnected: String
    val mcpToolsTitle: String
    val mcpNoTools: String
    fun mcpToolCount(n: Int): String
    fun mcpServerInfo(name: String): String
    val mcpAddServer: String
    val mcpRemoveServer: String
    val mcpConnectAll: String
    val mcpDisconnectAll: String
    val mcpServerLabel: String
    val mcpServerLabelPlaceholder: String
    fun mcpOrchestratorStatus(servers: Int, tools: Int): String

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

package i18n

object EnStrings : Strings {
    // Settings dialog
    override val settingsTitle = "Settings"
    override val apiKey = "API Key"
    override val model = "Model"
    override val maxTokensLabel = "Max Tokens (empty = default)"
    override val connectTimeout = "Connect Timeout (sec)"
    override val readTimeout = "Read Timeout (sec)"
    override val save = "Save"
    override val cancel = "Cancel"
    override val language = "Language"

    // Buttons
    override val send = "Send"
    override val sendAll = "Send All"
    override val clear = "Clear"
    override val clearAll = "Clear All"

    // Placeholders
    override val enterMessage = "Enter your message..."
    override val systemPromptGlobal = "System prompt (global)..."
    override val systemPromptPerChat = "System prompt (per-chat)..."
    override val constraintsPerChat = "Additional prompt (per-chat)..."
    override val constraintsPlaceholder = "Constraints (appended to prompt)..."
    override val maxTokensOverride = "Max tokens (override)..."
    override val jsonSchemaPlaceholder = "JSON Schema..."

    // Chat options
    override val optionStatistics = "Statistics"
    override val optionSystemPrompt = "System Prompt"
    override val optionConstraints = "Constraints"
    override val optionStopWords = "Stop Words"
    override val optionMaxTokens = "Max Tokens"
    override val optionTemperature = "Temperature"
    override val optionResponseFormat = "Response Format"
    override val optionContext = "Context"

    // Summarization
    override val autoSummarize = "Auto-summarize"
    override val summarizing = "Summarizing history..."
    override val summaryLabel = "Summary"
    override val summarizeThresholdLabel = "Summarize after N messages"
    override val keepLastLabel = "Keep last N messages"
    override val sendHistory = "Send conversation history"
    override val globalContext = "Context (global defaults)"
    override val freshSummaryLabel = "Summarization applied"
    override val slidingWindowLabel = "Message window (empty = all)"
    override val extractFacts = "Extract key facts"
    override val extractingFacts = "Extracting facts..."
    override val stickyFactsLabel = "Key facts"
    override val stickyFactsPlaceholder = "Key facts extracted from conversation..."
    override val extractMemory = "Extract memory"
    override val extractingMemory = "Extracting memory..."

    // Profile selector
    override val profileSectionTitle = "Profile"
    override val noProfileSelected = "No profile"
    override val renameProfile = "Rename"
    override val addProfileItemPlaceholder = "Add preference..."
    override val noProfileItems = "No preferences"

    // Memory panel
    override val memoryPanelTitle = "Memory"
    override val sessionMemoryTab = "Session"
    override val globalMemoryTab = "Global"
    override val addMemoryPlaceholder = "Add memory item..."
    override val moveToGlobal = "Move to Global"
    override val noMemoryItems = "No memory items"
    override val memorySourceAuto = "auto"
    override val memorySourceManual = "manual"
    override val memorySourcePromoted = "promoted"
    override val sessionMemoryScopeLabel = "this session only"
    override val globalMemoryScopeLabel = "persists across sessions"
    override fun memoryTokenEstimate(tokens: Int) = "~$tokens tokens injected"
    override fun summaryCountLabel(n: Int) = "Summaries made: $n"
    override fun requestHistoryLabel(n: Int) = "History ($n messages)"

    // Profile dialog
    override val profileDialogTitle = "Edit Profiles"
    override val editProfile = "Edit"
    override val deleteProfileConfirmTitle = "Delete Profile"
    override fun deleteProfileConfirmBody(name: String) = "Delete profile \"$name\"?"
    override val confirm = "Delete"
    override val addProfile = "Add Profile"
    override val profileItemsHeader = "Preferences"

    // Task tracking
    override val optionTaskTracking = "Task Tracking"
    override val optionRag = "RAG"
    override val ragEnabled = "RAG enabled"
    override val ragDisabled = "RAG disabled"
    override val ragLoading = "Loading RAG index..."
    override val ragSourcesLabel = "Sources used:"
    override val ragNoIndex = "No index loaded"
    override val taskTrackingLabel = "Track task phases"
    override val taskPlanning = "Plan"
    override val taskExecution = "Execute"
    override val taskValidation = "Validate"
    override val taskDone = "Done"
    override val taskPause = "Pause"
    override val taskResume = "Resume"
    override val taskReset = "Reset"
    override val taskExtracting = "analyzing..."

    override val taskPausedBanner = "Task paused. Send a message to resume."
    override fun taskTransitionBlocked(from: String, to: String) =
        "Transition blocked: $from → $to. Phase skipping is not allowed."

    // Invariants
    override val invariantsTab = "Invariants"
    override val invariantsScopeLabel = "checked after each response"
    override val addInvariantPlaceholder = "Add invariant..."
    override val noInvariants = "No invariants"
    override val invariantViolationLabel = "Invariant violation"
    override val checkingInvariants = "Checking invariants..."

    // Statistics tooltip
    override val lastRequest = "Last request"
    override val sessionTotal = "Session total"
    override val promptTokens = "Prompt Tokens"
    override val completionTokens = "Completion Tokens"
    override val totalTokens = "Total Tokens"
    override val promptTokensDesc = "Tokens sent in the request"
    override val completionTokensDesc = "Tokens generated by the model"
    override val totalTokensDesc = "Sum of prompt and completion tokens"
    override val allPromptTokensDesc = "All prompt tokens this session"
    override val allCompletionTokensDesc = "All completion tokens this session"
    override val allTotalTokensDesc = "All tokens consumed this session"

    // Session tabs
    override val archiveLabel = "Archive"
    override val clearArchive = "Clear archive"
    override fun sessionDefaultName(n: Int) = "New"

    // MCP
    override val mcpSectionTitle = "MCP Servers (Orchestration)"
    override val mcpServerCommand = "Command (e.g. npx)"
    override val mcpServerArgs = "Arguments"
    override val mcpConnect = "Connect"
    override val mcpDisconnect = "Disconnect"
    override val mcpConnecting = "Connecting..."
    override val mcpConnected = "Connected"
    override val mcpDisconnected = "Disconnected"
    override val mcpToolsTitle = "Available Tools"
    override val mcpNoTools = "No tools available"
    override fun mcpToolCount(n: Int) = "$n tools"
    override fun mcpServerInfo(name: String) = "Server: $name"
    override val mcpAddServer = "+ Add Server"
    override val mcpRemoveServer = "Remove"
    override val mcpConnectAll = "Connect All"
    override val mcpDisconnectAll = "Disconnect All"
    override val mcpServerLabel = "Label"
    override val mcpServerLabelPlaceholder = "e.g. Git, Pipeline..."
    override fun mcpOrchestratorStatus(servers: Int, tools: Int) = "MCP: $servers servers, $tools tools"

    // Parameterized
    override fun stopWordPlaceholder(index: Int) = "Stop ${index + 1}"
    override fun chatTitle(index: Int) = "Chat ${index + 1}"
    override fun temperatureValue(temp: String) = "Temperature: $temp"
    override fun lastStatsLine(p: Int, c: Int, t: Int) = "Last: P:$p C:$c T:$t"
    override fun totalStatsLine(p: Int, c: Int, t: Int) = "Total: P:$p C:$c T:$t"
}

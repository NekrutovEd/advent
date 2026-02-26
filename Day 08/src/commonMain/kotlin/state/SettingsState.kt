package state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import i18n.Lang

class SettingsState {
    var lang by mutableStateOf(Lang.EN)
    var systemPrompt by mutableStateOf("")
    var selectedModel by mutableStateOf("gpt-4o")

    // Global history default
    var defaultSendHistory by mutableStateOf(true)

    // Global summarization defaults (per-chat option overrides these)
    var defaultAutoSummarize by mutableStateOf(true)
    var defaultSummarizeThreshold by mutableStateOf("10")
    var defaultKeepLastMessages by mutableStateOf("4")

    val apiConfigs = mutableStateListOf(
        ApiConfig(
            id = "openai",
            name = "OpenAI",
            availableModels = listOf(
                // o3 — топ reasoning
                "o3-mini",
                "o3-mini-2025-01-31",
                // o1 — сильный reasoning
                "o1",
                "o1-2024-12-17",
                "o1-preview",
                // GPT-4o — флагман general
                "gpt-4o",
                "gpt-4o-2024-11-20",
                "gpt-4o-2024-08-06",
                "gpt-4o-2024-05-13",
                // GPT-4 Turbo — предыдущее поколение
                "gpt-4-turbo",
                "gpt-4-turbo-2024-04-09",
                "gpt-4-turbo-preview",
                // GPT-4
                "gpt-4",
                "gpt-4-0613",
                // o1-mini — reasoning, меньший
                "o1-mini",
                "o1-mini-2024-09-12",
                // GPT-4o mini — быстрый general
                "gpt-4o-mini",
                "gpt-4o-mini-2024-07-18",
                // GPT-3.5 — legacy
                "gpt-3.5-turbo",
                "gpt-3.5-turbo-0125",
                "gpt-3.5-turbo-1106"
            )
        )
    )

    fun allModels(): List<String> = apiConfigs.flatMap { it.availableModels }

    fun configForModel(model: String): ApiConfig? =
        apiConfigs.firstOrNull { model in it.availableModels }
}

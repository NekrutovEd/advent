package state

data class ModelInfo(
    val contextTokens: Int,
    val provider: String,
    val params: String?,
    val category: String,
    val description: String
)

object ModelInfoProvider {

    private val entries = mapOf(
        // ── Groq ─────────────────────────────────────────────────────────────
        "llama-3.3-70b-versatile"       to ModelInfo(128_000, "Meta",     "70B",  "General",   "Flagship LLaMA 3.3 — best all-round Groq model"),
        "llama-3.1-8b-instant"          to ModelInfo(128_000, "Meta",     "8B",   "Fast",      "Ultra-fast lightweight model for simple tasks"),
        "qwen-qwq-32b"                  to ModelInfo(128_000, "Alibaba",  "32B",  "Reasoning", "Step-by-step chain-of-thought reasoning"),
        "deepseek-r1-distill-llama-70b" to ModelInfo(128_000, "DeepSeek", "70B",  "Reasoning", "DeepSeek R1 reasoning distilled into LLaMA-70B"),
        "mixtral-8x7b-32768"            to ModelInfo( 32_768, "Mistral",  "8×7B", "General",   "Mixture-of-Experts: activates 2 of 8 expert layers"),
        "gemma2-9b-it"                  to ModelInfo(  8_192, "Google",   "9B",   "General",   "Google Gemma 2 — instruction-tuned"),
        "llama3-70b-8192"               to ModelInfo(  8_192, "Meta",     "70B",  "General",   "LLaMA 3 70B classic — short context (legacy)"),
        "llama3-8b-8192"                to ModelInfo(  8_192, "Meta",     "8B",   "Fast",      "LLaMA 3 8B compact — short context (legacy)"),

        // ── OpenAI ───────────────────────────────────────────────────────────
        "o3-mini"                       to ModelInfo(200_000, "OpenAI", null, "Reasoning", "Efficient reasoning model, latest o3 series"),
        "o3-mini-2025-01-31"            to ModelInfo(200_000, "OpenAI", null, "Reasoning", "o3-mini snapshot — January 2025"),
        "o1"                            to ModelInfo(200_000, "OpenAI", null, "Reasoning", "Advanced reasoning: deep multi-step problem-solving"),
        "o1-2024-12-17"                 to ModelInfo(200_000, "OpenAI", null, "Reasoning", "o1 snapshot — December 2024"),
        "o1-preview"                    to ModelInfo(128_000, "OpenAI", null, "Reasoning", "Early o1 preview (deprecating)"),
        "gpt-4o"                        to ModelInfo(128_000, "OpenAI", null, "General",   "Flagship multimodal model: text, vision, audio"),
        "gpt-4o-2024-11-20"             to ModelInfo(128_000, "OpenAI", null, "General",   "GPT-4o snapshot — November 2024"),
        "gpt-4o-2024-08-06"             to ModelInfo(128_000, "OpenAI", null, "General",   "GPT-4o snapshot — August 2024"),
        "gpt-4o-2024-05-13"             to ModelInfo(128_000, "OpenAI", null, "General",   "GPT-4o snapshot — May 2024"),
        "gpt-4-turbo"                   to ModelInfo(128_000, "OpenAI", null, "General",   "GPT-4 Turbo — enhanced speed and long context"),
        "gpt-4-turbo-2024-04-09"        to ModelInfo(128_000, "OpenAI", null, "General",   "GPT-4 Turbo snapshot — April 2024"),
        "gpt-4-turbo-preview"           to ModelInfo(128_000, "OpenAI", null, "General",   "GPT-4 Turbo preview (legacy)"),
        "gpt-4"                         to ModelInfo(  8_192, "OpenAI", null, "General",   "Original GPT-4 — solid baseline, short context"),
        "gpt-4-0613"                    to ModelInfo(  8_192, "OpenAI", null, "General",   "GPT-4 snapshot — June 2023"),
        "o1-mini"                       to ModelInfo(128_000, "OpenAI", null, "Reasoning", "Smaller o1 — faster reasoning, lower cost"),
        "o1-mini-2024-09-12"            to ModelInfo(128_000, "OpenAI", null, "Reasoning", "o1-mini snapshot — September 2024"),
        "gpt-4o-mini"                   to ModelInfo(128_000, "OpenAI", null, "General",   "Efficient GPT-4o — best cost-to-quality ratio"),
        "gpt-4o-mini-2024-07-18"        to ModelInfo(128_000, "OpenAI", null, "General",   "GPT-4o mini snapshot — July 2024"),
        "gpt-3.5-turbo"                 to ModelInfo( 16_385, "OpenAI", null, "Fast",      "Legacy fast model — superseded by GPT-4o mini"),
        "gpt-3.5-turbo-0125"            to ModelInfo( 16_385, "OpenAI", null, "Fast",      "GPT-3.5 Turbo snapshot — January 2025"),
        "gpt-3.5-turbo-1106"            to ModelInfo( 16_385, "OpenAI", null, "Fast",      "GPT-3.5 Turbo snapshot — November 2023"),
    )

    fun get(modelId: String): ModelInfo? = entries[modelId]

    fun formatContext(tokens: Int): String = "${tokens / 1000}K"
}

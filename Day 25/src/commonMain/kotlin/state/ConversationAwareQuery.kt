package state

import api.ChatMessage

/**
 * Builds enriched RAG queries that incorporate conversation context.
 * Instead of searching with just the raw user message, this enriches
 * the query with goal/clarifications from TaskMemory for better retrieval.
 */
object ConversationAwareQuery {

    /**
     * Enrich a RAG query with conversation context.
     *
     * @param userMessage The raw user message
     * @param taskMemory Current task memory (may be empty)
     * @param recentHistory Recent conversation history for context
     * @return Enriched query string for RAG search
     */
    fun build(
        userMessage: String,
        taskMemory: TaskMemory? = null,
        recentHistory: List<ChatMessage> = emptyList()
    ): String {
        // If no task memory, just use the raw message
        if (taskMemory == null) return userMessage

        val enrichments = mutableListOf<String>()

        // Add goal context — helps narrow semantic search
        val goal = taskMemory.goal
        if (goal != null && !userMessage.contains(goal, ignoreCase = true)) {
            // Only add goal keywords if they aren't already in the user message
            val goalKeywords = extractKeywords(goal)
            val messageKeywords = extractKeywords(userMessage)
            val newKeywords = goalKeywords - messageKeywords
            if (newKeywords.isNotEmpty()) {
                enrichments.add(newKeywords.take(3).joinToString(" "))
            }
        }

        // Add constraint terms that may help retrieval
        for (constraint in taskMemory.constraints.take(2)) {
            val keywords = extractKeywords(constraint)
            val messageKeywords = extractKeywords(userMessage)
            val newKeywords = keywords - messageKeywords
            if (newKeywords.isNotEmpty()) {
                enrichments.add(newKeywords.take(2).joinToString(" "))
            }
        }

        // Add clarification context if the message is very short (pronoun resolution)
        if (userMessage.split(" ").size <= 4 && taskMemory.clarifications.isNotEmpty()) {
            // Short message likely references prior context
            val lastClarification = taskMemory.clarifications.last()
            val keywords = extractKeywords(lastClarification)
            val messageKeywords = extractKeywords(userMessage)
            val newKeywords = keywords - messageKeywords
            if (newKeywords.isNotEmpty()) {
                enrichments.add(newKeywords.take(2).joinToString(" "))
            }
        }

        if (enrichments.isEmpty()) return userMessage

        return "$userMessage ${enrichments.joinToString(" ")}"
    }

    /**
     * Extract meaningful keywords from text (lowercase, no stop words).
     */
    internal fun extractKeywords(text: String): Set<String> {
        return text.lowercase()
            .replace(Regex("[^a-zA-Zа-яА-ЯёЁ0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in STOP_WORDS }
            .toSet()
    }

    private val STOP_WORDS = setOf(
        // English
        "the", "and", "for", "are", "but", "not", "you", "all", "can", "had",
        "her", "was", "one", "our", "out", "has", "have", "been", "from",
        "this", "that", "with", "they", "will", "what", "when", "where",
        "how", "about", "which", "their", "there", "each", "other",
        // Russian
        "это", "что", "как", "для", "при", "или", "все", "его", "она",
        "они", "мне", "уже", "тут", "там", "нет", "так", "ещё", "еще",
        "был", "мой", "вот", "этот", "эта", "эти"
    )
}

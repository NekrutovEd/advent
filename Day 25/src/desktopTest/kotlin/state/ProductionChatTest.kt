package state

import api.ChatApi
import api.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.json.JSONArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Production-like chat scenarios with RAG + task memory + citation validation.
 * Tests that the system maintains coherence over 10-15 message exchanges.
 */
class ProductionChatTest {

    private lateinit var server: MockWebServer
    private lateinit var chatState: ChatState

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = ChatApi(
            baseUrl = server.url("/").toString().trimEnd('/'),
            ioDispatcher = Dispatchers.Unconfined
        )
        chatState = ChatState(api, defaultTaskTracking = true, defaultAutoSummarize = false)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueAssistant(content: String) {
        val body = JSONObject().apply {
            put("choices", JSONArray().apply {
                put(JSONObject().apply {
                    put("message", JSONObject().apply {
                        put("role", "assistant")
                        put("content", content)
                    })
                })
            })
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(body.toString()))
    }

    private fun enqueueTaskState(phase: String = "idle") {
        val body = JSONObject().apply {
            put("choices", JSONArray().apply {
                put(JSONObject().apply {
                    put("message", JSONObject().apply {
                        put("role", "assistant")
                        put("content", JSONObject().apply {
                            put("phase", phase)
                        }.toString())
                    })
                })
            })
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(body.toString()))
    }

    private fun enqueueTaskMemory(
        goal: String? = null,
        clarifications: List<String> = emptyList(),
        constraints: List<String> = emptyList(),
        covered: List<String> = emptyList()
    ) {
        val memoryObj = JSONObject().apply {
            put("goal", goal ?: JSONObject.NULL)
            put("new_clarifications", JSONArray(clarifications))
            put("new_constraints", JSONArray(constraints))
            put("new_covered", JSONArray(covered))
        }
        val body = JSONObject().apply {
            put("choices", JSONArray().apply {
                put(JSONObject().apply {
                    put("message", JSONObject().apply {
                        put("role", "assistant")
                        put("content", memoryObj.toString())
                    })
                })
            })
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(body.toString()))
    }

    /**
     * Pad a response to exceed 200 chars so the short-response heuristic
     * in TaskTracker.extractState does NOT skip the task state extraction.
     * This ensures a predictable 3-API-call pattern: main + taskState + taskMemory.
     */
    private fun pad(response: String): String {
        if (response.length >= 200) return response
        // Pad to ensure >200 chars so the short-response heuristic doesn't skip task state extraction
        val needed = 201 - response.length
        val padding = " " + "x".repeat(needed.coerceAtLeast(0))
        return response + padding
    }

    /**
     * Send a message and enqueue exactly 3 mock responses:
     * 1. Main assistant response (padded to >200 chars)
     * 2. Task state extraction response
     * 3. Task memory extraction response
     */
    private suspend fun exchange(
        userMsg: String,
        assistantResponse: String,
        taskPhase: String = "idle",
        memoryGoal: String? = null,
        memoryClarifications: List<String> = emptyList(),
        memoryConstraints: List<String> = emptyList(),
        memoryCovered: List<String> = emptyList()
    ) {
        val paddedResponse = pad(assistantResponse)
        enqueueAssistant(paddedResponse)
        enqueueTaskState(taskPhase)
        enqueueTaskMemory(memoryGoal, memoryClarifications, memoryConstraints, memoryCovered)

        chatState.sendMessage(
            userMsg, "test-key", "gpt-4o", null, null
        )
    }

    // ══════════════════════════════════════════════════════════════
    // Scenario A: Gradual refinement (broad → narrow, 12 messages)
    // User starts broad, progressively narrows the topic
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `scenario A - gradual refinement maintains goal and accumulates context`() = runTest {
        // Turn 1: Broad question
        exchange(
            userMsg = "Tell me about the system architecture",
            assistantResponse = "The system consists of an IntelliJ plugin, an Android app, and they communicate via WebSocket.",
            memoryGoal = "Understanding system architecture",
            memoryCovered = listOf("High-level system components")
        )
        assertEquals("Understanding system architecture", chatState.taskMemory.goal)
        assertEquals(1, chatState.taskMemory.coveredTopics.size)

        // Turn 2: Narrowing to communication
        exchange(
            userMsg = "How do the plugin and app communicate?",
            assistantResponse = "They use a Ktor WebSocket server on port 8765. The plugin hosts the server.",
            memoryGoal = "Understanding communication between plugin and app",
            memoryClarifications = listOf("Communication uses WebSocket on port 8765"),
            memoryCovered = listOf("WebSocket communication mechanism")
        )
        assertEquals("Understanding communication between plugin and app", chatState.taskMemory.goal)
        assertTrue(chatState.taskMemory.clarifications.isNotEmpty())
        assertTrue(chatState.taskMemory.coveredTopics.size >= 2)

        // Turn 3: More specific
        exchange(
            userMsg = "What protocol does the WebSocket use?",
            assistantResponse = "The WebSocket uses JSON-based messages with a custom protocol for terminal I/O.",
            memoryClarifications = listOf("WebSocket uses JSON-based custom protocol"),
            memoryCovered = listOf("WebSocket protocol details")
        )

        // Turn 4: Even more specific
        exchange(
            userMsg = "What about authentication?",
            assistantResponse = "Currently there is no authentication. The connection is trusted on the local network.",
            memoryClarifications = listOf("No authentication, relies on local network trust"),
            memoryCovered = listOf("Authentication (or lack thereof)")
        )

        // Turn 5: Tangent but related
        exchange(
            userMsg = "How does the app find the plugin on the network?",
            assistantResponse = "The app uses mDNS (NsdManager on Android) to discover _remoteclaude._tcp services.",
            memoryClarifications = listOf("Discovery uses mDNS via NsdManager"),
            memoryCovered = listOf("Network discovery mechanism")
        )

        // Turn 6: Back to details
        exchange(
            userMsg = "Can multiple apps connect simultaneously?",
            assistantResponse = "The current implementation supports a single WebSocket connection at a time.",
            memoryClarifications = listOf("Single connection only"),
            memoryCovered = listOf("Connection concurrency")
        )

        // Verify accumulated state
        assertTrue(chatState.taskMemory.clarifications.size >= 4,
            "Should have accumulated at least 4 clarifications, got ${chatState.taskMemory.clarifications.size}")
        assertTrue(chatState.taskMemory.coveredTopics.size >= 5,
            "Should have covered at least 5 topics, got ${chatState.taskMemory.coveredTopics.size}")

        // Turn 7-10: Continue narrowing
        exchange(
            userMsg = "What happens when the connection drops?",
            assistantResponse = "The app attempts reconnection with exponential backoff.",
            memoryClarifications = listOf("Reconnection uses exponential backoff"),
            memoryCovered = listOf("Connection failure handling")
        )

        exchange(
            userMsg = "Is there a timeout?",
            assistantResponse = "The default read timeout is 60 seconds, configurable in settings.",
            memoryClarifications = listOf("Read timeout 60s, configurable"),
            memoryCovered = listOf("Timeout configuration")
        )

        exchange(
            userMsg = "What about push notifications when disconnected?",
            assistantResponse = "FCM push notifications are used when the app is backgrounded.",
            memoryClarifications = listOf("FCM push notifications for backgrounded app"),
            memoryCovered = listOf("Push notification mechanism")
        )

        exchange(
            userMsg = "Summarize what we've established so far",
            assistantResponse = "We've covered: WebSocket on port 8765, JSON protocol, no auth, mDNS discovery, single connection, reconnection, timeouts, and FCM push.",
            memoryCovered = listOf("Summary of communication architecture")
        )

        // Final assertions
        val prompt = chatState.taskMemory.toPromptContext()
        assertFalse(prompt.isEmpty(), "Task memory should have content")
        assertTrue(prompt.contains("GOAL:"), "Should have a goal")
        assertTrue(prompt.contains("ESTABLISHED FACTS:"), "Should have established facts")
        assertTrue(prompt.contains("ALREADY COVERED:"), "Should have covered topics")

        // Verify message count
        assertEquals(20, chatState.messages.size, "Should have 10 user + 10 assistant messages")
    }

    // ══════════════════════════════════════════════════════════════
    // Scenario B: Topic switch mid-conversation (12 messages)
    // User discusses topic A, then switches to topic B
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `scenario B - topic switch updates goal and preserves covered topics`() = runTest {
        // Phase 1: Topic A — Push notifications (turns 1-5)
        exchange(
            userMsg = "How do push notifications work in the system?",
            assistantResponse = "FCM push notifications are triggered when the agent produces output while the app is backgrounded.",
            memoryGoal = "Understanding push notification system",
            memoryClarifications = listOf("FCM triggers when app backgrounded"),
            memoryCovered = listOf("Push notification trigger mechanism")
        )
        assertEquals("Understanding push notification system", chatState.taskMemory.goal)

        exchange(
            userMsg = "What data is sent in the push?",
            assistantResponse = "The push payload contains a message preview and a notification ID for dedup.",
            memoryClarifications = listOf("Push contains message preview and dedup ID"),
            memoryCovered = listOf("Push notification payload")
        )

        exchange(
            userMsg = "How is the FCM token managed?",
            assistantResponse = "The token is registered on first launch and refreshed via FirebaseMessagingService.",
            memoryClarifications = listOf("Token registered on first launch, refreshed automatically"),
            memoryCovered = listOf("FCM token lifecycle")
        )

        exchange(
            userMsg = "What if the token changes?",
            assistantResponse = "The onNewToken callback re-registers with the plugin server.",
            memoryClarifications = listOf("onNewToken re-registers with plugin"),
            memoryCovered = listOf("Token refresh handling")
        )

        exchange(
            userMsg = "Is there rate limiting on pushes?",
            assistantResponse = "No explicit rate limiting, but FCM itself has quotas.",
            memoryClarifications = listOf("No app-level rate limiting, relies on FCM quotas"),
            memoryCovered = listOf("Push rate limiting")
        )

        // Verify Topic A state
        assertEquals("Understanding push notification system", chatState.taskMemory.goal)
        val topicAClarifications = chatState.taskMemory.clarifications.size
        assertTrue(topicAClarifications >= 4, "Should have at least 4 clarifications from topic A")
        val topicACovered = chatState.taskMemory.coveredTopics.size
        assertTrue(topicACovered >= 4, "Should have at least 4 covered topics from topic A")

        // Phase 2: Topic switch! (turns 6-10)
        exchange(
            userMsg = "Actually, let me ask about something different. How does mDNS discovery work?",
            assistantResponse = "mDNS discovery uses NsdManager on Android to find _remoteclaude._tcp services on the local network.",
            memoryGoal = "Understanding mDNS discovery",
            memoryClarifications = listOf("mDNS uses NsdManager for _remoteclaude._tcp"),
            memoryCovered = listOf("mDNS basic mechanism")
        )

        // Goal should have updated to new topic
        assertEquals("Understanding mDNS discovery", chatState.taskMemory.goal,
            "Goal should update when topic changes")
        // But covered topics from topic A should be preserved
        assertTrue(chatState.taskMemory.coveredTopics.size > topicACovered,
            "Covered topics should accumulate, not reset")

        exchange(
            userMsg = "What ports does mDNS use?",
            assistantResponse = "mDNS operates on port 5353 (multicast). The service itself registers port 8765.",
            memoryClarifications = listOf("mDNS on port 5353 multicast, service on 8765"),
            memoryCovered = listOf("mDNS port configuration")
        )

        exchange(
            userMsg = "Does it work across subnets?",
            assistantResponse = "No, mDNS is limited to the local subnet by design.",
            memoryClarifications = listOf("mDNS local subnet only"),
            memoryCovered = listOf("mDNS network scope")
        )

        exchange(
            userMsg = "What if discovery fails?",
            assistantResponse = "The app falls back to manual IP entry in the settings screen.",
            memoryClarifications = listOf("Manual IP fallback available"),
            memoryCovered = listOf("Discovery failure fallback")
        )

        exchange(
            userMsg = "Can the user save discovered servers?",
            assistantResponse = "Yes, discovered servers are saved to local preferences for quick reconnect.",
            memoryClarifications = listOf("Discovered servers saved to preferences"),
            memoryCovered = listOf("Server persistence")
        )

        // Final assertions
        assertEquals("Understanding mDNS discovery", chatState.taskMemory.goal)

        // Clarifications should contain items from both topics
        assertTrue(chatState.taskMemory.clarifications.size >= topicAClarifications + 4,
            "Should have clarifications from both topics A and B")

        // Covered topics should contain items from both topics
        assertTrue(chatState.taskMemory.coveredTopics.size >= topicACovered + 4,
            "Should have covered topics from both topics A and B")

        // Verify total message count
        assertEquals(20, chatState.messages.size, "Should have 10 user + 10 assistant messages")

        // Verify prompt context includes all accumulated data
        val prompt = chatState.taskMemory.toPromptContext()
        assertTrue(prompt.contains("GOAL: Understanding mDNS discovery"))
        assertTrue(prompt.contains("ESTABLISHED FACTS:"))
        assertTrue(prompt.contains("ALREADY COVERED:"))
    }

    // ══════════════════════════════════════════════════════════════
    // Citation validation integration test
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `citation validation works with RAG context`() = runTest {
        // Set up RAG chunks
        chatState.lastRagChunks = listOf(
            RagChunk("Plugin runs a Ktor WebSocket server on port 8765", "SYSTEM_OVERVIEW.md", "Components", 0.88f),
            RagChunk("The app discovers via mDNS broadcast", "mdns_discovery.md", "Overview", 0.75f)
        )

        val responseWithCitations = """
            The plugin uses a WebSocket server on port 8765.

            Sources:
            - [SYSTEM_OVERVIEW.md (Components)]

            Quotes:
            > "Plugin runs a Ktor WebSocket server on port 8765" — SYSTEM_OVERVIEW.md
        """.trimIndent()

        // Enqueue: main response + task state + task memory
        enqueueAssistant(responseWithCitations)
        enqueueTaskState("idle")
        enqueueTaskMemory()

        chatState.sendMessage(
            "What port does the WebSocket use?", "test-key", "gpt-4o", null, null,
            ragContextText = "[RAG Context]\nSome context here"
        )

        // Verify citation result was stored
        val lastMsg = chatState.messages.last()
        assertEquals("assistant", lastMsg.role)
        assertNotNull(lastMsg.citationResult, "Citation result should be set for RAG responses")
        assertTrue(lastMsg.citationResult!!.verifiedSources.isNotEmpty(), "Should have verified sources")
        assertTrue(lastMsg.citationResult!!.verifiedQuotes.isNotEmpty(), "Should have verified quotes")
        assertTrue(lastMsg.citationResult!!.groundingScore > 0.5f, "Should have reasonable grounding score")
    }

    @Test
    fun `no citation validation without RAG context`() = runTest {
        enqueueAssistant("Hello! How can I help?")
        enqueueTaskState("idle")
        enqueueTaskMemory()

        chatState.sendMessage("Hello", "test-key", "gpt-4o", null, null)

        val lastMsg = chatState.messages.last()
        assertNull(lastMsg.citationResult, "No citation result without RAG context")
    }

    @Test
    fun `conversation-aware query enrichment with task memory`() {
        val memory = TaskMemory().apply {
            goal = "Understanding MCP server architecture"
            clarifications.add("Uses Kotlin and Ktor framework")
            constraints.add("Must support multiple concurrent connections")
        }

        val enriched = ConversationAwareQuery.build("How does routing work?", memory)
        assertTrue(enriched.length > "How does routing work?".length,
            "Query should be enriched with goal/constraint keywords")
    }
}

package state

import api.ChatApi
import i18n.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import storage.SessionSerializer

class TaskTrackerTest {

    private lateinit var server: MockWebServer
    private lateinit var appState: AppState

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = ChatApi(
            baseUrl = server.url("/").toString().trimEnd('/'),
            ioDispatcher = Dispatchers.Unconfined
        )
        appState = AppState(api)
        appState.settings.apiConfigs[0].apiKey = "test-key"
        appState.settings.apiConfigs[0].baseUrl = server.url("/").toString().trimEnd('/')
        appState.settings.defaultTaskTracking = false
        appState.activeSession.chats.forEach { it.taskTracking = false }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueSuccess(content: String = "Response") {
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

    // --- TaskPhase transitions ---

    @Test
    fun `TaskPhase next follows correct order`() {
        assertEquals(TaskPhase.PLANNING, TaskPhase.IDLE.next())
        assertEquals(TaskPhase.EXECUTION, TaskPhase.PLANNING.next())
        assertEquals(TaskPhase.VALIDATION, TaskPhase.EXECUTION.next())
        assertEquals(TaskPhase.DONE, TaskPhase.VALIDATION.next())
        assertEquals(TaskPhase.DONE, TaskPhase.DONE.next())
    }

    // --- TaskTracker state ---

    @Test
    fun `new TaskTracker starts at IDLE`() {
        val tracker = TaskTracker()
        assertEquals(TaskPhase.IDLE, tracker.phase)
        assertFalse(tracker.isPaused)
        assertTrue(tracker.steps.isEmpty())
        assertEquals(0, tracker.currentStepIndex)
        assertEquals("", tracker.taskDescription)
    }

    @Test
    fun `pause and resume toggle isPaused`() {
        val tracker = TaskTracker()
        tracker.pause()
        assertTrue(tracker.isPaused)
        tracker.resume()
        assertFalse(tracker.isPaused)
    }

    @Test
    fun `reset returns to IDLE`() {
        val tracker = TaskTracker()
        tracker.phase = TaskPhase.EXECUTION
        tracker.isPaused = true
        tracker.steps.add(TaskStep("step1", true))
        tracker.currentStepIndex = 1
        tracker.taskDescription = "Build app"

        tracker.reset()

        assertEquals(TaskPhase.IDLE, tracker.phase)
        assertFalse(tracker.isPaused)
        assertTrue(tracker.steps.isEmpty())
        assertEquals(0, tracker.currentStepIndex)
        assertEquals("", tracker.taskDescription)
    }

    // --- Context string ---

    @Test
    fun `toContextString returns flow rules for IDLE`() {
        val tracker = TaskTracker()
        val ctx = tracker.toContextString(Lang.EN)
        assertTrue(ctx.contains("[Task Flow Rules]"))
        assertTrue(ctx.contains("Complete the ENTIRE task"))
    }

    @Test
    fun `toContextString includes phase and task description`() {
        val tracker = TaskTracker()
        tracker.phase = TaskPhase.EXECUTION
        tracker.taskDescription = "Build login page"

        val ctx = tracker.toContextString(Lang.EN)
        assertTrue(ctx.contains("[Task State]"))
        assertTrue(ctx.contains("Build login page"))
        assertTrue(ctx.contains("EXECUTION"))
    }

    @Test
    fun `toContextString includes pause instructions when paused`() {
        val tracker = TaskTracker()
        tracker.phase = TaskPhase.PLANNING
        tracker.isPaused = true

        val ctx = tracker.toContextString(Lang.EN)
        assertTrue(ctx.contains("Task Resumed"))
        assertTrue(ctx.contains("Continue from where you left off"))
    }

    @Test
    fun `toContextString includes steps with markers`() {
        val tracker = TaskTracker()
        tracker.phase = TaskPhase.EXECUTION
        tracker.steps.addAll(listOf(
            TaskStep("Define API", true),
            TaskStep("Write code", false),
            TaskStep("Add tests", false)
        ))
        tracker.currentStepIndex = 1

        val ctx = tracker.toContextString(Lang.EN)
        assertTrue(ctx.contains("[x] Define API"))
        assertTrue(ctx.contains("[>] Write code"))
        assertTrue(ctx.contains("[ ] Add tests"))
    }

    // --- No-pause behavior tests ---

    @Test
    fun `toContextString IDLE contains no-stop flow rules`() {
        val tracker = TaskTracker()
        val ctx = tracker.toContextString(Lang.EN)
        assertTrue(ctx.contains("Complete the ENTIRE task in a SINGLE response"))
        assertTrue(ctx.contains("Never stop to ask for confirmation"))
        assertTrue(ctx.contains("Never ask 'should I proceed?'"))
    }

    @Test
    fun `toContextString non-IDLE non-paused contains flow rules`() {
        val tracker = TaskTracker()
        tracker.phase = TaskPhase.EXECUTION
        tracker.taskDescription = "Build feature"

        val ctx = tracker.toContextString(Lang.EN)
        assertTrue(ctx.contains("[Task Flow Rules]"))
        assertTrue(ctx.contains("Complete the ENTIRE task in a SINGLE response"))
        assertTrue(ctx.contains("Never stop to ask for confirmation"))
    }

    @Test
    fun `toContextString never contains wait or confirm language when not paused`() {
        // The flow rules contain anti-instructions like "Never ask 'should I proceed?'"
        // We verify the context never *instructs* the AI to wait/pause (outside of anti-instructions)
        for (phase in listOf(TaskPhase.IDLE, TaskPhase.PLANNING, TaskPhase.EXECUTION, TaskPhase.VALIDATION, TaskPhase.DONE)) {
            val tracker = TaskTracker()
            tracker.phase = phase
            tracker.taskDescription = "Some task"

            val ctx = tracker.toContextString(Lang.EN).lowercase()
            // Must not contain language that tells the AI to wait
            assertFalse(ctx.contains("waiting for user"), "Phase $phase should not contain 'waiting for user'")
            assertFalse(ctx.contains("awaiting user"), "Phase $phase should not contain 'awaiting user'")
            assertFalse(ctx.contains("wait for confirmation"), "Phase $phase should not contain 'wait for confirmation'")
            // The flow rules should always say "Never stop" — never the opposite
            assertFalse(ctx.contains("stop and ask"), "Phase $phase should not contain 'stop and ask'")
        }
    }

    @Test
    fun `only manual pause sets isPaused`() {
        val tracker = TaskTracker()
        // Phase transitions never set isPaused
        tracker.phase = TaskPhase.PLANNING
        assertFalse(tracker.isPaused)
        tracker.phase = TaskPhase.EXECUTION
        assertFalse(tracker.isPaused)
        tracker.phase = TaskPhase.VALIDATION
        assertFalse(tracker.isPaused)
        tracker.phase = TaskPhase.DONE
        assertFalse(tracker.isPaused)

        // Only explicit pause() sets it
        tracker.pause()
        assertTrue(tracker.isPaused)
    }

    @Test
    fun `phaseActionLabel returns localized labels without wait language`() {
        val tracker = TaskTracker()
        for (phase in TaskPhase.entries) {
            tracker.phase = phase
            val labelEn = tracker.phaseActionLabel(Lang.EN).lowercase()
            val labelRu = tracker.phaseActionLabel(Lang.RU).lowercase()
            assertFalse(labelEn.contains("wait"), "EN label for $phase should not contain 'wait'")
            assertFalse(labelEn.contains("confirm"), "EN label for $phase should not contain 'confirm'")
            assertFalse(labelRu.contains("ожида"), "RU label for $phase should not contain 'ожида'")
        }
    }

    // --- Task tracking enabled by default ---

    @Test
    fun `ChatState has taskTracking enabled when setting is true`() {
        appState.settings.defaultTaskTracking = true
        appState.addSession()
        val chat = appState.activeSession.chats[0]
        assertTrue(chat.taskTracking)
        assertEquals(TaskPhase.IDLE, chat.taskTracker.phase)
    }

    @Test
    fun `ChatState taskTracking disabled when setting is false`() {
        appState.settings.defaultTaskTracking = false
        appState.addSession()
        val chat = appState.activeSession.chats[0]
        assertFalse(chat.taskTracking)
    }

    // --- Extraction from API response ---

    @Test
    fun `task state extracted after successful response`() = runTest {
        // First enqueue the main response (>200 chars to avoid short-response heuristic)
        enqueueSuccess("Let me plan the implementation. Step 1: Design the API. Step 2: Implement the endpoints. Step 3: Write tests. Step 4: Document. " +
            "This will involve setting up the project structure, defining models, creating controllers, and adding integration tests for each endpoint.")
        // Then enqueue the task extraction response
        val taskJson = """{"phase":"planning","task_description":"Design API","steps":["Gather requirements","Design endpoints"],"current_step":0,"completed_steps":[]}"""
        enqueueSuccess(taskJson)

        val chat = appState.activeSession.chats[0]
        chat.taskTracking = true
        chat.sendMessage("Build a REST API", "key", "llama-3.3-70b-versatile", null, null)

        assertEquals(TaskPhase.PLANNING, chat.taskTracker.phase)
        assertEquals("Design API", chat.taskTracker.taskDescription)
        assertEquals(2, chat.taskTracker.steps.size)
        assertEquals("Gather requirements", chat.taskTracker.steps[0].description)
    }

    @Test
    fun `sending message auto-resumes paused tracker and extracts`() = runTest {
        enqueueSuccess("Sure, continuing the work. I'll now implement the remaining endpoints, add error handling for all edge cases, " +
            "write comprehensive unit tests, and set up the CI pipeline. Let me start with the user authentication module and then move on to the data layer.")
        val taskJson = """{"phase":"execution","task_description":"Build API","steps":["Design","Implement"],"current_step":1,"completed_steps":[0]}"""
        enqueueSuccess(taskJson)

        val chat = appState.activeSession.chats[0]
        chat.taskTracking = true
        chat.taskTracker.phase = TaskPhase.PLANNING
        chat.taskTracker.isPaused = true

        chat.sendMessage("Continue", "key", "llama-3.3-70b-versatile", null, null)

        // isPaused should be auto-cleared
        assertFalse(chat.taskTracker.isPaused)
        // Extraction should have run (2 requests: main + extraction)
        assertEquals(2, server.requestCount)
        assertEquals(TaskPhase.EXECUTION, chat.taskTracker.phase)
    }

    @Test
    fun `task extraction skipped when tracking disabled`() = runTest {
        enqueueSuccess("Response")

        val chat = appState.activeSession.chats[0]
        chat.taskTracking = false

        chat.sendMessage("Do something", "key", "llama-3.3-70b-versatile", null, null)

        assertEquals(1, server.requestCount)
        assertEquals(TaskPhase.IDLE, chat.taskTracker.phase)
    }

    // --- Serialization ---

    @Test
    fun `task tracker survives serialize-deserialize round-trip`() {
        val session = appState.activeSession
        val chat = session.chats[0]
        chat.taskTracker.phase = TaskPhase.EXECUTION
        chat.taskTracker.isPaused = true
        chat.taskTracker.steps.addAll(listOf(
            TaskStep("Step A", true),
            TaskStep("Step B", false)
        ))
        chat.taskTracker.currentStepIndex = 1
        chat.taskTracker.taskDescription = "Build feature X"

        val encoded = SessionSerializer.encodeSession(session)
        val decoded = SessionSerializer.decodeSession(encoded, appState)!!

        val restoredTracker = decoded.chats[0].taskTracker
        assertEquals(TaskPhase.EXECUTION, restoredTracker.phase)
        assertTrue(restoredTracker.isPaused)
        assertEquals(2, restoredTracker.steps.size)
        assertTrue(restoredTracker.steps[0].completed)
        assertEquals("Step B", restoredTracker.steps[1].description)
        assertEquals(1, restoredTracker.currentStepIndex)
        assertEquals("Build feature X", restoredTracker.taskDescription)
    }

    @Test
    fun `old JSON without taskTracker deserializes with defaults`() {
        val oldJson = """
            {
                "id": "abc123",
                "name": "Test",
                "chats": [{
                    "id": "chat1",
                    "constraints": "",
                    "systemPrompt": "",
                    "stopWords": [""],
                    "maxTokensOverride": "",
                    "temperatureOverride": null,
                    "modelOverride": null,
                    "responseFormatType": "text",
                    "jsonSchema": "",
                    "sendHistory": true,
                    "autoSummarize": false,
                    "summarizeThreshold": "10",
                    "keepLastMessages": "4",
                    "summaryCount": 0,
                    "slidingWindow": "",
                    "extractFacts": false,
                    "stickyFacts": "",
                    "visibleOptions": [],
                    "messages": [],
                    "history": []
                }]
            }
        """.trimIndent()

        val decoded = SessionSerializer.decodeSession(oldJson, appState)!!
        val tracker = decoded.chats[0].taskTracker
        assertEquals(TaskPhase.IDLE, tracker.phase)
        assertFalse(tracker.isPaused)
        assertTrue(tracker.steps.isEmpty())
        assertTrue(decoded.chats[0].taskTracking)
    }

    // --- Clone preserves task state ---

    @Test
    fun `cloneChat preserves task tracker state`() {
        val session = appState.activeSession
        val chat = session.chats[0]
        chat.taskTracker.phase = TaskPhase.VALIDATION
        chat.taskTracker.isPaused = true
        chat.taskTracker.steps.add(TaskStep("Review output", false))
        chat.taskTracker.currentStepIndex = 0
        chat.taskTracker.taskDescription = "Validate feature"
        chat.taskTracking = true

        session.cloneChat(0)

        val clone = session.chats[1]
        assertEquals(TaskPhase.VALIDATION, clone.taskTracker.phase)
        assertTrue(clone.taskTracker.isPaused)
        assertEquals(1, clone.taskTracker.steps.size)
        assertEquals("Review output", clone.taskTracker.steps[0].description)
        assertEquals("Validate feature", clone.taskTracker.taskDescription)
        assertTrue(clone.taskTracking)
    }

    // --- ChatState.clear resets tracker ---

    @Test
    fun `clear resets task tracker`() {
        val chat = appState.activeSession.chats[0]
        chat.taskTracker.phase = TaskPhase.DONE
        chat.taskTracker.taskDescription = "Completed task"

        chat.clear()

        assertEquals(TaskPhase.IDLE, chat.taskTracker.phase)
        assertEquals("", chat.taskTracker.taskDescription)
    }

    // --- Controlled state transitions (Day 15) ---

    @Test
    fun `tryTransition allows valid forward transitions`() {
        val tracker = TaskTracker()
        // IDLE → PLANNING
        assertTrue(tracker.tryTransition(TaskPhase.PLANNING))
        assertEquals(TaskPhase.PLANNING, tracker.phase)

        // PLANNING → EXECUTION
        assertTrue(tracker.tryTransition(TaskPhase.EXECUTION))
        assertEquals(TaskPhase.EXECUTION, tracker.phase)

        // EXECUTION → VALIDATION
        assertTrue(tracker.tryTransition(TaskPhase.VALIDATION))
        assertEquals(TaskPhase.VALIDATION, tracker.phase)

        // VALIDATION → DONE
        assertTrue(tracker.tryTransition(TaskPhase.DONE))
        assertEquals(TaskPhase.DONE, tracker.phase)
    }

    @Test
    fun `tryTransition allows staying in same phase`() {
        val tracker = TaskTracker()
        tracker.phase = TaskPhase.EXECUTION
        assertTrue(tracker.tryTransition(TaskPhase.EXECUTION))
        assertEquals(TaskPhase.EXECUTION, tracker.phase)
        assertNull(tracker.lastRejection)
    }

    @Test
    fun `tryTransition allows IDLE to DONE for trivial QA`() {
        val tracker = TaskTracker()
        assertTrue(tracker.tryTransition(TaskPhase.DONE))
        assertEquals(TaskPhase.DONE, tracker.phase)
    }

    @Test
    fun `tryTransition allows VALIDATION to EXECUTION for fixes`() {
        val tracker = TaskTracker()
        tracker.phase = TaskPhase.VALIDATION
        assertTrue(tracker.tryTransition(TaskPhase.EXECUTION))
        assertEquals(TaskPhase.EXECUTION, tracker.phase)
    }

    @Test
    fun `tryTransition rejects IDLE to EXECUTION — cannot skip planning`() {
        val tracker = TaskTracker()
        assertFalse(tracker.tryTransition(TaskPhase.EXECUTION))
        assertEquals(TaskPhase.IDLE, tracker.phase)
        assertNotNull(tracker.lastRejection)
        assertEquals(TaskPhase.EXECUTION, tracker.lastRejection!!.attempted)
        assertEquals(TaskPhase.IDLE, tracker.lastRejection!!.current)
    }

    @Test
    fun `tryTransition rejects IDLE to VALIDATION — cannot skip planning and execution`() {
        val tracker = TaskTracker()
        assertFalse(tracker.tryTransition(TaskPhase.VALIDATION))
        assertEquals(TaskPhase.IDLE, tracker.phase)
        assertNotNull(tracker.lastRejection)
    }

    @Test
    fun `tryTransition rejects PLANNING to VALIDATION — cannot skip execution`() {
        val tracker = TaskTracker()
        tracker.phase = TaskPhase.PLANNING
        assertFalse(tracker.tryTransition(TaskPhase.VALIDATION))
        assertEquals(TaskPhase.PLANNING, tracker.phase)
        assertNotNull(tracker.lastRejection)
    }

    @Test
    fun `tryTransition rejects PLANNING to DONE — cannot skip execution and validation`() {
        val tracker = TaskTracker()
        tracker.phase = TaskPhase.PLANNING
        assertFalse(tracker.tryTransition(TaskPhase.DONE))
        assertEquals(TaskPhase.PLANNING, tracker.phase)
        assertNotNull(tracker.lastRejection)
    }

    @Test
    fun `tryTransition rejects EXECUTION to DONE — cannot skip validation`() {
        val tracker = TaskTracker()
        tracker.phase = TaskPhase.EXECUTION
        assertFalse(tracker.tryTransition(TaskPhase.DONE))
        assertEquals(TaskPhase.EXECUTION, tracker.phase)
        assertNotNull(tracker.lastRejection)
    }

    @Test
    fun `dismissRejection clears lastRejection`() {
        val tracker = TaskTracker()
        tracker.tryTransition(TaskPhase.EXECUTION) // illegal from IDLE
        assertNotNull(tracker.lastRejection)
        tracker.dismissRejection()
        assertNull(tracker.lastRejection)
    }

    @Test
    fun `successful transition clears previous rejection`() {
        val tracker = TaskTracker()
        tracker.tryTransition(TaskPhase.EXECUTION) // rejected
        assertNotNull(tracker.lastRejection)
        tracker.tryTransition(TaskPhase.PLANNING)  // allowed
        assertNull(tracker.lastRejection)
    }

    @Test
    fun `reset clears rejection`() {
        val tracker = TaskTracker()
        tracker.tryTransition(TaskPhase.EXECUTION) // rejected
        assertNotNull(tracker.lastRejection)
        tracker.reset()
        assertNull(tracker.lastRejection)
    }

    @Test
    fun `context string includes transition rules for IDLE`() {
        val tracker = TaskTracker()
        val ctx = tracker.toContextString(Lang.EN)
        assertTrue(ctx.contains("[Phase Transition Rules]"))
        assertTrue(ctx.contains("CANNOT skip phases"))
    }

    @Test
    fun `context string includes allowed next phases`() {
        val tracker = TaskTracker()
        tracker.phase = TaskPhase.PLANNING
        val ctx = tracker.toContextString(Lang.EN)
        assertTrue(ctx.contains("Allowed next phases: EXECUTION"))
    }

    @Test
    fun `context string includes rejection info when transition was blocked`() {
        val tracker = TaskTracker()
        tracker.phase = TaskPhase.PLANNING
        tracker.tryTransition(TaskPhase.DONE) // rejected

        val ctx = tracker.toContextString(Lang.EN)
        assertTrue(ctx.contains("[TRANSITION BLOCKED]"))
        assertTrue(ctx.contains("DONE"))
        assertTrue(ctx.contains("PLANNING"))
    }

    @Test
    fun `illegal transition rejected during extraction — phase stays`() = runTest {
        // Main response (>200 chars to avoid short-response heuristic)
        enqueueSuccess("Here is the complete solution with tests. I've implemented the entire REST API with all endpoints, models, " +
            "controllers, services, and comprehensive test suites. The code covers authentication, authorization, error handling, and data validation layers.")
        // Extraction tries to jump IDLE → EXECUTION (illegal: skips PLANNING)
        val taskJson = """{"phase":"execution","task_description":"Build API","steps":["Write code"],"current_step":0,"completed_steps":[]}"""
        enqueueSuccess(taskJson)

        val chat = appState.activeSession.chats[0]
        chat.taskTracking = true
        chat.sendMessage("Build a REST API", "key", "llama-3.3-70b-versatile", null, null)

        // Phase should NOT have changed to EXECUTION
        assertEquals(TaskPhase.IDLE, chat.taskTracker.phase)
        // But steps/description should still be updated
        assertEquals("Build API", chat.taskTracker.taskDescription)
        assertEquals(1, chat.taskTracker.steps.size)
        // Rejection should be recorded
        assertNotNull(chat.taskTracker.lastRejection)
        assertEquals(TaskPhase.EXECUTION, chat.taskTracker.lastRejection!!.attempted)
    }

    @Test
    fun `legal transition accepted during extraction`() = runTest {
        enqueueSuccess("Let me plan this out first. I'll break this down into several phases: research the requirements, design the API schema, " +
            "implement the endpoints, write tests, and deploy. Here's my detailed approach for each of these steps and the rationale behind each decision.")
        val taskJson = """{"phase":"planning","task_description":"Plan API","steps":["Research","Design"],"current_step":0,"completed_steps":[]}"""
        enqueueSuccess(taskJson)

        val chat = appState.activeSession.chats[0]
        chat.taskTracking = true
        chat.sendMessage("Build a REST API", "key", "llama-3.3-70b-versatile", null, null)

        // IDLE → PLANNING is legal
        assertEquals(TaskPhase.PLANNING, chat.taskTracker.phase)
        assertNull(chat.taskTracker.lastRejection)
    }

    @Test
    fun `all allowed transitions are symmetric in allowedTransitions map`() {
        // Every phase must have an entry
        for (phase in TaskPhase.entries) {
            assertTrue(
                TaskPhase.allowedTransitions.containsKey(phase),
                "Missing entry for $phase in allowedTransitions"
            )
        }
    }

    @Test
    fun `isTransitionAllowed matches allowedTransitions map`() {
        for (from in TaskPhase.entries) {
            for (to in TaskPhase.entries) {
                val expected = to in (TaskPhase.allowedTransitions[from] ?: emptySet())
                assertEquals(
                    expected,
                    TaskPhase.isTransitionAllowed(from, to),
                    "isTransitionAllowed($from, $to) mismatch"
                )
            }
        }
    }
}

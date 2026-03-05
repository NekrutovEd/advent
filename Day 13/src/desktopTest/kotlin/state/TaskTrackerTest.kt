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
        assertEquals("", tracker.expectedAction)
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
        tracker.expectedAction = "something"
        tracker.taskDescription = "Build app"

        tracker.reset()

        assertEquals(TaskPhase.IDLE, tracker.phase)
        assertFalse(tracker.isPaused)
        assertTrue(tracker.steps.isEmpty())
        assertEquals(0, tracker.currentStepIndex)
        assertEquals("", tracker.expectedAction)
        assertEquals("", tracker.taskDescription)
    }

    // --- Context string ---

    @Test
    fun `toContextString returns flow rules for IDLE`() {
        val tracker = TaskTracker()
        val ctx = tracker.toContextString(Lang.EN)
        assertTrue(ctx.contains("[Task Flow Rules]"))
        assertTrue(ctx.contains("PLANNING"))
        assertTrue(ctx.contains("EXECUTION"))
    }

    @Test
    fun `toContextString includes phase and task description`() {
        val tracker = TaskTracker()
        tracker.phase = TaskPhase.EXECUTION
        tracker.taskDescription = "Build login page"
        tracker.expectedAction = "Write HTML template"

        val ctx = tracker.toContextString(Lang.EN)
        assertTrue(ctx.contains("[Task State]"))
        assertTrue(ctx.contains("Build login page"))
        assertTrue(ctx.contains("EXECUTION"))
        assertTrue(ctx.contains("Write HTML template"))
    }

    @Test
    fun `toContextString includes pause instructions`() {
        val tracker = TaskTracker()
        tracker.phase = TaskPhase.PLANNING
        tracker.isPaused = true

        val ctx = tracker.toContextString(Lang.EN)
        assertTrue(ctx.contains("PAUSED"))
        assertTrue(ctx.contains("Continue from where you left off"))
    }

    @Test
    fun `toContextString for EXECUTION says proceed without stopping`() {
        val tracker = TaskTracker()
        tracker.phase = TaskPhase.EXECUTION
        tracker.taskDescription = "Build feature"

        val ctx = tracker.toContextString(Lang.EN)
        assertTrue(ctx.contains("EXECUTION"))
        assertTrue(ctx.contains("WITHOUT stopping"))
    }

    @Test
    fun `toContextString for PLANNING says stop and ask to confirm`() {
        val tracker = TaskTracker()
        tracker.phase = TaskPhase.PLANNING

        val ctx = tracker.toContextString(Lang.EN)
        assertTrue(ctx.contains("PLANNING"))
        assertTrue(ctx.contains("STOP and ask the user to confirm"))
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
        // First enqueue the main response
        enqueueSuccess("Let me plan the implementation. Step 1: Design the API.")
        // Then enqueue the task extraction response
        val taskJson = """{"phase":"planning","task_description":"Design API","steps":["Gather requirements","Design endpoints"],"current_step":0,"completed_steps":[],"expected_action":"User confirms requirements","awaiting_user":true}"""
        enqueueSuccess(taskJson)

        val chat = appState.activeSession.chats[0]
        chat.taskTracking = true
        chat.sendMessage("Build a REST API", "key", "llama-3.3-70b-versatile", null, null)

        assertEquals(TaskPhase.PLANNING, chat.taskTracker.phase)
        assertEquals("Design API", chat.taskTracker.taskDescription)
        assertEquals(2, chat.taskTracker.steps.size)
        assertEquals("Gather requirements", chat.taskTracker.steps[0].description)
        assertEquals("User confirms requirements", chat.taskTracker.expectedAction)
    }

    @Test
    fun `task extraction skipped when paused`() = runTest {
        enqueueSuccess("Sure, continuing...")

        val chat = appState.activeSession.chats[0]
        chat.taskTracker.phase = TaskPhase.EXECUTION
        chat.taskTracker.isPaused = true

        // Pre-populate history so extraction condition is met
        chat.sendMessage("Continue", "key", "llama-3.3-70b-versatile", null, null)

        // Only 1 request (main), no extraction request since paused
        assertEquals(1, server.requestCount)
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
        chat.taskTracker.expectedAction = "Waiting for review"
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
        assertEquals("Waiting for review", restoredTracker.expectedAction)
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
        chat.taskTracker.expectedAction = "Check results"
        chat.taskTracker.taskDescription = "Validate feature"
        chat.taskTracking = true

        session.cloneChat(0)

        val clone = session.chats[1]
        assertEquals(TaskPhase.VALIDATION, clone.taskTracker.phase)
        assertTrue(clone.taskTracker.isPaused)
        assertEquals(1, clone.taskTracker.steps.size)
        assertEquals("Review output", clone.taskTracker.steps[0].description)
        assertEquals("Check results", clone.taskTracker.expectedAction)
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
}

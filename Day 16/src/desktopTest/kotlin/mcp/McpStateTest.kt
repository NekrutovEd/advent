package mcp

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import state.McpState

/** Fake MCP client for testing state management. */
class FakeMcpClient : McpClientInterface {
    var connected = false
    var connectCalled = false
    var disconnectCalled = false
    var toolsToReturn = listOf<McpTool>()
    var shouldThrow: Exception? = null
    var lastCommand: String? = null
    var lastArgs: List<String>? = null

    override val isConnected: Boolean get() = connected

    override suspend fun connect(command: String, args: List<String>, env: Map<String, String>): McpInitResult {
        connectCalled = true
        lastCommand = command
        lastArgs = args
        shouldThrow?.let { throw it }
        connected = true
        return McpInitResult(serverName = "TestServer", serverVersion = "0.1", protocolVersion = "2024-11-05")
    }

    override suspend fun listTools(): List<McpTool> {
        return toolsToReturn
    }

    override fun disconnect() {
        disconnectCalled = true
        connected = false
    }
}

class McpStateTest {

    @Test
    fun `connect succeeds and populates tools`() = runTest {
        val fakeClient = FakeMcpClient()
        fakeClient.toolsToReturn = listOf(
            McpTool("read_file", "Read a file"),
            McpTool("write_file", "Write a file")
        )
        val state = McpState(fakeClient)
        state.serverCommand = "test-server"
        state.serverArgs = "--port 8080"

        state.connect()

        assertTrue(state.isConnected)
        assertFalse(state.isConnecting)
        assertNull(state.error)
        assertEquals(2, state.tools.size)
        assertEquals("read_file", state.tools[0].name)
        assertEquals("write_file", state.tools[1].name)
        assertEquals("TestServer v0.1", state.serverName)
        assertEquals("test-server", fakeClient.lastCommand)
        assertEquals(listOf("--port", "8080"), fakeClient.lastArgs)
    }

    @Test
    fun `connect fails with error`() = runTest {
        val fakeClient = FakeMcpClient()
        fakeClient.shouldThrow = RuntimeException("Connection refused")
        val state = McpState(fakeClient)
        state.serverCommand = "bad-server"

        state.connect()

        assertFalse(state.isConnected)
        assertFalse(state.isConnecting)
        assertEquals("Connection refused", state.error)
        assertTrue(state.tools.isEmpty())
    }

    @Test
    fun `connect requires non-blank command`() = runTest {
        val fakeClient = FakeMcpClient()
        val state = McpState(fakeClient)
        state.serverCommand = ""

        state.connect()

        assertFalse(state.isConnected)
        assertNotNull(state.error)
        assertFalse(fakeClient.connectCalled)
    }

    @Test
    fun `disconnect clears state`() = runTest {
        val fakeClient = FakeMcpClient()
        fakeClient.toolsToReturn = listOf(McpTool("tool1", "A tool"))
        val state = McpState(fakeClient)
        state.serverCommand = "server"

        state.connect()
        assertTrue(state.isConnected)
        assertEquals(1, state.tools.size)

        state.disconnect()
        assertFalse(state.isConnected)
        assertTrue(state.tools.isEmpty())
        assertNull(state.error)
        assertTrue(fakeClient.disconnectCalled)
    }

    @Test
    fun `connect with no tools returns empty list`() = runTest {
        val fakeClient = FakeMcpClient()
        fakeClient.toolsToReturn = emptyList()
        val state = McpState(fakeClient)
        state.serverCommand = "empty-server"

        state.connect()

        assertTrue(state.isConnected)
        assertTrue(state.tools.isEmpty())
    }
}

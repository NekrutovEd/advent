package mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class McpClientTest {

    @Test
    fun `buildJsonRpcRequest creates valid JSON-RPC 2_0 message`() {
        val params = buildJsonObject {
            put("protocolVersion", "2024-11-05")
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject {
                put("name", "test")
                put("version", "1.0")
            })
        }
        val msg = McpClient.buildJsonRpcRequest(1, "initialize", params)
        val json = Json.parseToJsonElement(msg).jsonObject

        assertEquals("2.0", json["jsonrpc"]?.jsonPrimitive?.content)
        assertEquals(1, json["id"]?.jsonPrimitive?.int)
        assertEquals("initialize", json["method"]?.jsonPrimitive?.content)
        assertNotNull(json["params"])
        assertEquals("2024-11-05", json["params"]?.jsonObject?.get("protocolVersion")?.jsonPrimitive?.content)
    }

    @Test
    fun `parseToolsList parses tools from result object`() {
        val json = """
        {
            "tools": [
                {
                    "name": "read_file",
                    "description": "Read the contents of a file",
                    "inputSchema": {
                        "type": "object",
                        "properties": {
                            "path": { "type": "string", "description": "File path" }
                        },
                        "required": ["path"]
                    }
                },
                {
                    "name": "list_directory",
                    "description": "List directory contents"
                }
            ]
        }
        """.trimIndent()

        val tools = McpClient.parseToolsList(json)

        assertEquals(2, tools.size)
        assertEquals("read_file", tools[0].name)
        assertEquals("Read the contents of a file", tools[0].description)
        assertNotNull(tools[0].inputSchema)
        assertEquals("object", tools[0].inputSchema?.get("type")?.jsonPrimitive?.content)

        assertEquals("list_directory", tools[1].name)
        assertEquals("List directory contents", tools[1].description)
        assertNull(tools[1].inputSchema)
    }

    @Test
    fun `parseToolsList handles JSON-RPC response wrapper`() {
        val json = """
        {
            "jsonrpc": "2.0",
            "id": 2,
            "result": {
                "tools": [
                    {
                        "name": "search",
                        "description": "Search the web"
                    }
                ]
            }
        }
        """.trimIndent()

        val tools = McpClient.parseToolsList(json)
        assertEquals(1, tools.size)
        assertEquals("search", tools[0].name)
    }

    @Test
    fun `parseToolsList returns empty list for no tools`() {
        val json = """{ "tools": [] }"""
        val tools = McpClient.parseToolsList(json)
        assertTrue(tools.isEmpty())
    }

    @Test
    fun `parseToolsList returns empty list for missing tools field`() {
        val json = """{ "result": {} }"""
        val tools = McpClient.parseToolsList(json)
        assertTrue(tools.isEmpty())
    }

    @Test
    fun `McpClient starts disconnected`() {
        val client = McpClient()
        assertFalse(client.isConnected)
    }

    @Test
    fun `disconnect on not-connected client is safe`() {
        val client = McpClient()
        assertDoesNotThrow { client.disconnect() }
        assertFalse(client.isConnected)
    }

    @Test
    fun `listTools throws when not connected`() = runTest {
        val client = McpClient()
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.test.TestScope().runTest {
                client.listTools()
            }
        }
    }

    @Test
    fun `buildJsonRpcRequest generates sequential ids`() {
        val req1 = McpClient.buildJsonRpcRequest(1, "initialize", buildJsonObject {})
        val req2 = McpClient.buildJsonRpcRequest(2, "tools/list", buildJsonObject {})

        val id1 = Json.parseToJsonElement(req1).jsonObject["id"]?.jsonPrimitive?.int
        val id2 = Json.parseToJsonElement(req2).jsonObject["id"]?.jsonPrimitive?.int

        assertEquals(1, id1)
        assertEquals(2, id2)
    }

    @Test
    fun `parseToolsList handles tool with full input schema`() {
        val json = """
        {
            "tools": [
                {
                    "name": "write_file",
                    "description": "Write content to a file",
                    "inputSchema": {
                        "type": "object",
                        "properties": {
                            "path": { "type": "string" },
                            "content": { "type": "string" }
                        },
                        "required": ["path", "content"]
                    }
                }
            ]
        }
        """.trimIndent()

        val tools = McpClient.parseToolsList(json)
        assertEquals(1, tools.size)
        val schema = tools[0].inputSchema!!
        val props = schema["properties"]?.jsonObject
        assertNotNull(props)
        assertTrue(props!!.containsKey("path"))
        assertTrue(props.containsKey("content"))
        val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(listOf("path", "content"), required)
    }
}

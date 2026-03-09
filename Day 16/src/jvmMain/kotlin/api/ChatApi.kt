package api

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mcp.McpTool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ChatApi(
    private val baseUrl: String = "https://api.openai.com",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) : ChatApiInterface {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun buildRequestBody(
            messages: List<ChatMessage>,
            model: String = "gpt-4o",
            temperature: Double? = null,
            maxTokens: Int? = null,
            systemPrompt: String? = null,
            stop: List<String>? = null,
            responseFormat: String? = null,
            jsonSchema: String? = null,
            tools: List<McpTool>? = null
        ): String {
            val messageArray = JSONArray()
            if (!systemPrompt.isNullOrBlank()) {
                messageArray.put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            messages.forEach { msg ->
                val msgObj = JSONObject()
                msgObj.put("role", msg.role)

                // Assistant message with tool_calls
                if (msg.toolCalls != null) {
                    msgObj.put("content", JSONObject.NULL)
                    val tcArray = JSONArray()
                    msg.toolCalls.forEach { tc ->
                        tcArray.put(JSONObject().apply {
                            put("id", tc.id)
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", tc.name)
                                put("arguments", tc.arguments)
                            })
                        })
                    }
                    msgObj.put("tool_calls", tcArray)
                } else if (msg.role == "tool") {
                    // Tool result message
                    msgObj.put("content", msg.content)
                    msgObj.put("tool_call_id", msg.toolCallId ?: "")
                } else {
                    msgObj.put("content", msg.content)
                }

                messageArray.put(msgObj)
            }

            val body = JSONObject()
            body.put("model", model)
            body.put("messages", messageArray)
            if (temperature != null) body.put("temperature", temperature)
            if (maxTokens != null) body.put("max_tokens", maxTokens)

            val filtered = stop?.filter { it.isNotBlank() }
            if (!filtered.isNullOrEmpty()) {
                body.put("stop", JSONArray(filtered))
            }

            // Tools
            if (!tools.isNullOrEmpty()) {
                val toolsArray = JSONArray()
                tools.forEach { tool ->
                    toolsArray.put(JSONObject().apply {
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", tool.name)
                            put("description", tool.description)
                            val params = if (tool.inputSchema != null) {
                                sanitizeSchema(JSONObject(tool.inputSchema.toString()))
                            } else {
                                JSONObject().apply {
                                    put("type", "object")
                                    put("properties", JSONObject())
                                }
                            }
                            put("parameters", params)
                        })
                    })
                }
                body.put("tools", toolsArray)
                body.put("tool_choice", "auto")
            }

            when (responseFormat) {
                "json_object" -> {
                    val rf = JSONObject()
                    rf.put("type", "json_object")
                    body.put("response_format", rf)
                }
                "json_schema" -> {
                    val rf = JSONObject()
                    rf.put("type", "json_schema")
                    val js = JSONObject()
                    js.put("name", "custom_schema")
                    js.put("strict", true)
                    js.put("schema", JSONObject(jsonSchema ?: "{}"))
                    rf.put("json_schema", js)
                    body.put("response_format", rf)
                }
            }

            return body.toString()
        }

        fun parseUsage(responseBody: String): TokenUsage? {
            val json = JSONObject(responseBody)
            val usage = json.optJSONObject("usage") ?: return null
            return TokenUsage(
                promptTokens = usage.optInt("prompt_tokens", 0),
                completionTokens = usage.optInt("completion_tokens", 0),
                totalTokens = usage.optInt("total_tokens", 0)
            )
        }

        fun parseResponseContent(responseBody: String): String {
            val json = JSONObject(responseBody)
            val message = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
            return if (message.isNull("content")) "" else message.optString("content", "")
        }

        /**
         * Strip schema fields that Groq/some providers don't support
         * (additionalProperties, $schema, anyOf, allOf, etc.)
         * and ensure top-level type is "object".
         */
        fun sanitizeSchema(schema: JSONObject): JSONObject {
            val unsupported = listOf("additionalProperties", "\$schema", "anyOf", "allOf", "oneOf", "not", "if", "then", "else")
            unsupported.forEach { schema.remove(it) }
            if (!schema.has("type")) {
                schema.put("type", "object")
            }
            if (!schema.has("properties")) {
                schema.put("properties", JSONObject())
            }
            // Recursively clean nested properties
            val props = schema.optJSONObject("properties")
            if (props != null) {
                for (key in props.keys()) {
                    val prop = props.optJSONObject(key)
                    if (prop != null) {
                        unsupported.forEach { prop.remove(it) }
                    }
                }
            }
            return schema
        }

        fun parseToolCalls(responseBody: String): List<ToolCall>? {
            val json = JSONObject(responseBody)
            val message = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
            val toolCalls = message.optJSONArray("tool_calls") ?: return null
            if (toolCalls.length() == 0) return null
            return (0 until toolCalls.length()).map { i ->
                val tc = toolCalls.getJSONObject(i)
                val fn = tc.getJSONObject("function")
                ToolCall(
                    id = tc.getString("id"),
                    name = fn.getString("name"),
                    arguments = fn.getString("arguments")
                )
            }
        }

        /**
         * Parse Groq's failed_generation format:
         *   <function=tool_name>{"arg": "value"}</function>
         * May contain multiple calls and/or trailing text.
         */
        private val FAILED_GEN_REGEX = Regex("""<function=(\w+)>(.*?)</function>""", RegexOption.DOT_MATCHES_ALL)

        fun parseFailedGeneration(text: String): List<ToolCall>? {
            val matches = FAILED_GEN_REGEX.findAll(text).toList()
            if (matches.isEmpty()) return null
            var counter = 0
            return matches.map { match ->
                val name = match.groupValues[1]
                val argsRaw = match.groupValues[2].trim()
                // Ensure valid JSON — default to empty object
                val args = try {
                    JSONObject(argsRaw).toString()
                } catch (_: Exception) {
                    "{}"
                }
                ToolCall(
                    id = "recovered_${counter++}",
                    name = name,
                    arguments = args
                )
            }
        }
    }

    override fun buildSnapshot(
        history: List<ChatMessage>,
        model: String,
        temperature: Double?,
        maxTokens: Int?,
        systemPrompt: String?,
        stop: List<String>?,
        responseFormat: String?,
        jsonSchema: String?,
        userContent: String,
        freshSummarization: Boolean
    ): RequestSnapshot {
        val metaObj = JSONObject()
        metaObj.put("model", model)
        if (temperature != null) metaObj.put("temperature", temperature)
        if (maxTokens != null) metaObj.put("max_tokens", maxTokens)
        val filteredStop = stop?.filter { it.isNotBlank() }
        if (!filteredStop.isNullOrEmpty()) metaObj.put("stop", JSONArray(filteredStop))
        when (responseFormat) {
            "json_object" -> metaObj.put("response_format", JSONObject().put("type", "json_object"))
            "json_schema" -> metaObj.put("response_format", JSONObject().apply {
                put("type", "json_schema")
                put("json_schema", JSONObject().apply {
                    put("name", "custom_schema")
                    put("strict", true)
                    put("schema", JSONObject(jsonSchema ?: "{}"))
                })
            })
        }

        val freshMsg = if (freshSummarization) {
            history.lastOrNull { it.role == "system" && it.content.startsWith("[Summary") }
        } else null

        val historyMessages = if (freshMsg != null) history - freshMsg else history

        val historyArr = JSONArray()
        if (!systemPrompt.isNullOrBlank()) {
            historyArr.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        historyMessages.forEach { msg ->
            historyArr.put(JSONObject().put("role", msg.role).put("content", msg.content))
        }

        val freshSummaryJson = freshMsg?.let {
            JSONObject().put("role", it.role).put("content", it.content).toString(2)
        }

        val currentObj = JSONObject().put("role", "user").put("content", userContent)

        return RequestSnapshot(
            metaJson = metaObj.toString(2),
            historyCount = historyArr.length(),
            historyJson = historyArr.toString(2),
            freshSummaryJson = freshSummaryJson,
            currentJson = currentObj.toString(2)
        )
    }

    override suspend fun sendMessage(
        history: List<ChatMessage>,
        apiKey: String,
        model: String,
        temperature: Double?,
        maxTokens: Int?,
        systemPrompt: String?,
        connectTimeoutSec: Int?,
        readTimeoutSec: Int?,
        stop: List<String>?,
        responseFormat: String?,
        jsonSchema: String?,
        baseUrl: String?,
        tools: List<McpTool>?
    ): ChatResponse {
        val requestBody = buildRequestBody(
            history, model, temperature, maxTokens, systemPrompt,
            stop, responseFormat, jsonSchema, tools
        )
        val effectiveBaseUrl = baseUrl ?: this.baseUrl
        val result = withContext(ioDispatcher) {
            sendHttp(apiKey, requestBody, connectTimeoutSec, readTimeoutSec, effectiveBaseUrl)
        }
        // If sendHttp recovered a failed_generation tool call, return it directly
        if (result.recoveredToolCalls != null) {
            return ChatResponse(
                content = "",
                usage = null,
                toolCalls = result.recoveredToolCalls
            )
        }
        return ChatResponse(
            content = parseResponseContent(result.body),
            usage = parseUsage(result.body),
            toolCalls = parseToolCalls(result.body)
        )
    }

    private data class HttpResult(
        val body: String,
        val recoveredToolCalls: List<ToolCall>? = null
    )

    private fun sendHttp(
        apiKey: String,
        requestBody: String,
        connectTimeoutSec: Int?,
        readTimeoutSec: Int?,
        effectiveBaseUrl: String
    ): HttpResult {
        val effectiveClient = if (connectTimeoutSec != null || readTimeoutSec != null) {
            client.newBuilder().apply {
                if (connectTimeoutSec != null) connectTimeout(connectTimeoutSec.toLong(), TimeUnit.SECONDS)
                if (readTimeoutSec != null) readTimeout(readTimeoutSec.toLong(), TimeUnit.SECONDS)
            }.build()
        } else {
            client
        }

        val request = Request.Builder()
            .url("$effectiveBaseUrl/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = effectiveClient.newCall(request).execute()
        val body = response.body?.string() ?: throw RuntimeException("Empty response body")

        if (!response.isSuccessful) {
            // Try to recover tool calls from failed_generation
            // (Groq returns <function=name>{"args"}</function> format)
            val recovered = try {
                val errorJson = JSONObject(body)
                val error = errorJson.optJSONObject("error")
                val failedGen = error?.optString("failed_generation", "")
                    ?.takeIf { it.isNotBlank() }
                if (failedGen != null) parseFailedGeneration(failedGen) else null
            } catch (_: Exception) { null }

            if (recovered != null) {
                return HttpResult(body = "", recoveredToolCalls = recovered)
            }

            val errorMsg = try {
                val errorJson = JSONObject(body)
                errorJson.optJSONObject("error")?.optString("message") ?: body
            } catch (_: Exception) {
                body
            }
            throw RuntimeException("API error ${response.code}: $errorMsg")
        }

        return HttpResult(body = body)
    }
}

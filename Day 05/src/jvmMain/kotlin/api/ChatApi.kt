package api

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            jsonSchema: String? = null
        ): String {
            val messageArray = JSONArray()
            if (!systemPrompt.isNullOrBlank()) {
                messageArray.put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            messages.forEach { msg ->
                messageArray.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
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
            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
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
        jsonSchema: String?
    ): ChatResponse {
        val requestBody = buildRequestBody(history, model, temperature, maxTokens, systemPrompt, stop, responseFormat, jsonSchema)
        val responseBody = withContext(ioDispatcher) {
            sendHttp(apiKey, requestBody, connectTimeoutSec, readTimeoutSec)
        }
        return ChatResponse(
            content = parseResponseContent(responseBody),
            usage = parseUsage(responseBody)
        )
    }

    private fun sendHttp(
        apiKey: String,
        requestBody: String,
        connectTimeoutSec: Int?,
        readTimeoutSec: Int?
    ): String {
        val effectiveClient = if (connectTimeoutSec != null || readTimeoutSec != null) {
            client.newBuilder().apply {
                if (connectTimeoutSec != null) connectTimeout(connectTimeoutSec.toLong(), TimeUnit.SECONDS)
                if (readTimeoutSec != null) readTimeout(readTimeoutSec.toLong(), TimeUnit.SECONDS)
            }.build()
        } else {
            client
        }

        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = effectiveClient.newCall(request).execute()
        val body = response.body?.string() ?: throw RuntimeException("Empty response body")

        if (!response.isSuccessful) {
            val errorMsg = try {
                val errorJson = JSONObject(body)
                errorJson.optJSONObject("error")?.optString("message") ?: body
            } catch (_: Exception) {
                body
            }
            throw RuntimeException("API error ${response.code}: $errorMsg")
        }

        return body
    }
}

package api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ChatApi(
    private val baseUrl: String = "https://api.openai.com",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun addMessage(messages: JSONArray, role: String, content: String) {
            val msg = JSONObject()
            msg.put("role", role)
            msg.put("content", content)
            messages.put(msg)
        }

        fun buildRequestBody(
            messages: JSONArray,
            model: String = "gpt-4o",
            temperature: Double? = null,
            maxTokens: Int? = null,
            systemPrompt: String? = null
        ): String {
            val finalMessages = if (!systemPrompt.isNullOrBlank()) {
                val copy = JSONArray()
                val systemMsg = JSONObject()
                systemMsg.put("role", "system")
                systemMsg.put("content", systemPrompt)
                copy.put(systemMsg)
                for (i in 0 until messages.length()) {
                    copy.put(messages.getJSONObject(i))
                }
                copy
            } else {
                messages
            }

            val body = JSONObject()
            body.put("model", model)
            body.put("messages", finalMessages)
            if (temperature != null) body.put("temperature", temperature)
            if (maxTokens != null) body.put("max_tokens", maxTokens)
            return body.toString()
        }

        fun parseResponseContent(responseBody: String): String {
            val json = JSONObject(responseBody)
            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }

    fun sendMessage(
        apiKey: String,
        requestBody: String,
        connectTimeoutSec: Int? = null,
        readTimeoutSec: Int? = null
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

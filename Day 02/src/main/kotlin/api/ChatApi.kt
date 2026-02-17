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
            maxTokens: Int? = null
        ): String {
            val body = JSONObject()
            body.put("model", model)
            body.put("messages", messages)
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

    fun sendMessage(apiKey: String, requestBody: String): String {
        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = client.newCall(request).execute()
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

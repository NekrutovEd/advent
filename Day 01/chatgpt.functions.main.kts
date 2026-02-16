@file:DependsOn("org.json:json:20240303")

import org.json.JSONArray
import org.json.JSONObject

fun isExitCommand(input: String): Boolean =
    input.equals("exit", ignoreCase = true) || input.equals("quit", ignoreCase = true)

fun addMessage(messages: JSONArray, role: String, content: String) {
    messages.put(JSONObject().put("role", role).put("content", content))
}

fun buildRequestBody(messages: JSONArray, model: String = "gpt-4o"): String =
    JSONObject()
        .put("model", model)
        .put("messages", messages)
        .toString()

fun parseResponseContent(responseBody: String): String =
    JSONObject(responseBody)
        .getJSONArray("choices")
        .getJSONObject(0)
        .getJSONObject("message")
        .getString("content")

@file:DependsOn("com.squareup.okhttp3:okhttp:4.12.0")
@file:DependsOn("org.json:json:20240303")

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

val apiKey = System.getenv("OPENAI_API_KEY") ?: run {
    print("Enter OpenAI API key: ")
    readLine()!!.trim()
}

val client = OkHttpClient.Builder()
    .readTimeout(60, TimeUnit.SECONDS)
    .connectTimeout(15, TimeUnit.SECONDS)
    .build()

val messages = JSONArray()

println("ChatGPT Console (type 'exit' to quit)")
println("========================================")

while (true) {
    print("\nYou: ")
    val input = readLine() ?: break
    if (input.equals("exit", ignoreCase = true) || input.equals("quit", ignoreCase = true)) break
    if (input.isBlank()) continue

    messages.put(JSONObject().put("role", "user").put("content", input))

    val body = JSONObject()
        .put("model", "gpt-4o")
        .put("messages", messages)
        .toString()
        .toRequestBody("application/json".toMediaType())

    val request = Request.Builder()
        .url("https://api.openai.com/v1/chat/completions")
        .addHeader("Authorization", "Bearer $apiKey")
        .post(body)
        .build()

    val spinning = AtomicBoolean(true)
    val spinner = Executors.newSingleThreadScheduledExecutor { r -> Thread(r).apply { isDaemon = true } }
    val frames = charArrayOf('|', '/', '-', '\\')
    var frameIndex = 0
    spinner.scheduleAtFixedRate({
        if (spinning.get()) {
            print("\r${frames[frameIndex++ % frames.size]} Thinking...")
            System.out.flush()
        }
    }, 0, 150, TimeUnit.MILLISECONDS)

    try {
        val response = client.newCall(request).execute()
        val responseBody = response.body!!.string()

        spinning.set(false)
        spinner.shutdown()
        print("\r                \r")

        if (!response.isSuccessful) {
            println("Error ${response.code}: $responseBody")
            messages.remove(messages.length() - 1)
            continue
        }

        val answer = JSONObject(responseBody)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")

        messages.put(JSONObject().put("role", "assistant").put("content", answer))
        println("GPT: $answer")
    } catch (e: Exception) {
        spinning.set(false)
        spinner.shutdown()
        print("\r                \r")
        println("Error: ${e.message}")
        messages.remove(messages.length() - 1)
    }
}

println("Bye!")

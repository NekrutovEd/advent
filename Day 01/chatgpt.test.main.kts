@file:Import("chatgpt.functions.main.kts")
@file:DependsOn("com.squareup.okhttp3:okhttp:4.12.0")
@file:DependsOn("com.squareup.okhttp3:mockwebserver:4.12.0")

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ===== Mini test framework =====

var passed = 0
var failed = 0

fun assert(condition: Boolean, message: String) {
    if (condition) {
        passed++
        println("  + $message")
    } else {
        failed++
        println("  FAIL: $message")
    }
}

fun assertEquals(expected: Any?, actual: Any?, message: String) {
    if (expected == actual) {
        passed++
        println("  + $message")
    } else {
        failed++
        println("  FAIL: $message")
        println("    expected: $expected")
        println("    actual:   $actual")
    }
}

fun test(name: String, block: () -> Unit) {
    println("\n[$name]")
    try {
        block()
    } catch (e: Exception) {
        failed++
        println("  FAIL: exception — ${e.message}")
    }
}

// ===== Tests =====

println("Running chatgpt.main.kts tests...\n")

// --- Unit tests ---

test("isExitCommand") {
    assert(isExitCommand("exit"), "'exit' is exit")
    assert(isExitCommand("EXIT"), "'EXIT' is exit")
    assert(isExitCommand("Exit"), "'Exit' is exit")
    assert(isExitCommand("quit"), "'quit' is exit")
    assert(isExitCommand("QUIT"), "'QUIT' is exit")
    assert(!isExitCommand("hello"), "'hello' is not exit")
    assert(!isExitCommand(""), "empty is not exit")
    assert(!isExitCommand("exiting"), "'exiting' is not exit")
    assert(!isExitCommand(" exit"), "' exit' is not exit")
}

test("addMessage") {
    val messages = JSONArray()
    addMessage(messages, "user", "Hello")
    assertEquals(1, messages.length(), "one message added")
    assertEquals("user", messages.getJSONObject(0).getString("role"), "role is user")
    assertEquals("Hello", messages.getJSONObject(0).getString("content"), "content is Hello")

    addMessage(messages, "assistant", "Hi!")
    assertEquals(2, messages.length(), "two messages after second add")
    assertEquals("assistant", messages.getJSONObject(1).getString("role"), "second role is assistant")
}

test("buildRequestBody — default model") {
    val messages = JSONArray()
    addMessage(messages, "user", "test")
    val body = JSONObject(buildRequestBody(messages))

    assertEquals("gpt-4o", body.getString("model"), "default model is gpt-4o")
    assertEquals(1, body.getJSONArray("messages").length(), "one message in body")
    assertEquals("test", body.getJSONArray("messages").getJSONObject(0).getString("content"), "message content preserved")
}

test("buildRequestBody — custom model") {
    val messages = JSONArray()
    addMessage(messages, "user", "test")
    val body = JSONObject(buildRequestBody(messages, "gpt-3.5-turbo"))

    assertEquals("gpt-3.5-turbo", body.getString("model"), "custom model applied")
}

test("parseResponseContent — valid response") {
    val response = JSONObject()
        .put("choices", JSONArray().put(
            JSONObject().put("message", JSONObject()
                .put("role", "assistant")
                .put("content", "Hello!")
            )
        ))
        .toString()

    assertEquals("Hello!", parseResponseContent(response), "parses content correctly")
}

test("parseResponseContent — invalid JSON throws") {
    var threw = false
    try {
        parseResponseContent("{}")
    } catch (e: Exception) {
        threw = true
    }
    assert(threw, "throws on missing 'choices' key")
}

// --- Integration tests with MockWebServer ---

test("MockWebServer — successful request") {
    val server = MockWebServer()
    server.enqueue(MockResponse()
        .setBody(JSONObject()
            .put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject()
                    .put("role", "assistant")
                    .put("content", "Mock response")
                )
            ))
            .toString()
        )
        .addHeader("Content-Type", "application/json")
    )
    server.start()

    val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    val messages = JSONArray()
    addMessage(messages, "user", "Hello")

    val request = Request.Builder()
        .url(server.url("/v1/chat/completions"))
        .addHeader("Authorization", "Bearer test-key")
        .post(buildRequestBody(messages).toRequestBody("application/json".toMediaType()))
        .build()

    val response = client.newCall(request).execute()
    val responseBody = response.body!!.string()

    assert(response.isSuccessful, "response is 200")
    assertEquals("Mock response", parseResponseContent(responseBody), "parsed mock response")

    val recorded = server.takeRequest()
    assertEquals("POST", recorded.method, "method is POST")
    assertEquals("Bearer test-key", recorded.getHeader("Authorization"), "auth header sent")

    val sentBody = JSONObject(recorded.body.readUtf8())
    assertEquals("gpt-4o", sentBody.getString("model"), "model sent correctly")

    server.shutdown()
}

test("MockWebServer — error 401") {
    val server = MockWebServer()
    server.enqueue(MockResponse()
        .setResponseCode(401)
        .setBody("""{"error": "invalid api key"}""")
    )
    server.start()

    val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    val messages = JSONArray()
    addMessage(messages, "user", "Hello")

    val request = Request.Builder()
        .url(server.url("/v1/chat/completions"))
        .addHeader("Authorization", "Bearer bad-key")
        .post(buildRequestBody(messages).toRequestBody("application/json".toMediaType()))
        .build()

    val response = client.newCall(request).execute()
    assert(!response.isSuccessful, "response is not successful")
    assertEquals(401, response.code, "status code is 401")

    server.shutdown()
}

test("MockWebServer — conversation context preserved") {
    val server = MockWebServer()
    server.enqueue(MockResponse().setBody(JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("message",
            JSONObject().put("role", "assistant").put("content", "I'm an AI"))))
        .toString()))
    server.enqueue(MockResponse().setBody(JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("message",
            JSONObject().put("role", "assistant").put("content", "You asked who I am"))))
        .toString()))
    server.start()

    val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    val messages = JSONArray()
    val baseUrl = server.url("/v1/chat/completions")

    // Turn 1
    addMessage(messages, "user", "Who are you?")
    var response = client.newCall(Request.Builder()
        .url(baseUrl)
        .post(buildRequestBody(messages).toRequestBody("application/json".toMediaType()))
        .build()
    ).execute()
    addMessage(messages, "assistant", parseResponseContent(response.body!!.string()))

    // Turn 2
    addMessage(messages, "user", "What did I ask?")
    response = client.newCall(Request.Builder()
        .url(baseUrl)
        .post(buildRequestBody(messages).toRequestBody("application/json".toMediaType()))
        .build()
    ).execute()

    // Verify second request has full history
    server.takeRequest() // skip first
    val secondRequest = server.takeRequest()
    val sentMessages = JSONObject(secondRequest.body.readUtf8()).getJSONArray("messages")

    assertEquals(3, sentMessages.length(), "3 messages in context (user, assistant, user)")
    assertEquals("user", sentMessages.getJSONObject(0).getString("role"), "1st msg is user")
    assertEquals("assistant", sentMessages.getJSONObject(1).getString("role"), "2nd msg is assistant")
    assertEquals("user", sentMessages.getJSONObject(2).getString("role"), "3rd msg is user")

    server.shutdown()
}

// ===== Results =====

println("\n========================================")
println("Results: $passed passed, $failed failed")
if (failed > 0) {
    System.err.println("TESTS FAILED")
    kotlin.system.exitProcess(1)
} else {
    println("ALL TESTS PASSED")
}

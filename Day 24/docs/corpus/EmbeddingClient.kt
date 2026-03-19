package indexing

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for generating text embeddings via OpenAI-compatible API.
 * Supports batched requests for efficiency.
 */
class EmbeddingClient(
    private val baseUrl: String = "https://api.openai.com",
    private val apiKey: String,
    private val model: String = "text-embedding-3-small",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val BATCH_SIZE = 20 // max texts per API call
    }

    /**
     * Generate embeddings for a list of texts.
     * Automatically batches to avoid API limits.
     * Returns list of float arrays (one per input text).
     */
    fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()

        val allEmbeddings = mutableListOf<FloatArray>()

        for (batch in texts.chunked(BATCH_SIZE)) {
            val batchResult = embedBatch(batch)
            allEmbeddings.addAll(batchResult)
        }

        return allEmbeddings
    }

    /**
     * Generate embedding for a single text.
     */
    fun embedSingle(text: String): FloatArray {
        return embed(listOf(text)).first()
    }

    private fun embedBatch(texts: List<String>): List<FloatArray> {
        val body = JSONObject().apply {
            put("model", model)
            put("input", JSONArray(texts))
        }

        val request = Request.Builder()
            .url("$baseUrl/v1/embeddings")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw RuntimeException("Empty response from embeddings API")

        if (!response.isSuccessful) {
            val errorMsg = try {
                JSONObject(responseBody).optJSONObject("error")?.optString("message") ?: responseBody
            } catch (_: Exception) { responseBody }
            throw RuntimeException("Embeddings API error ${response.code}: $errorMsg")
        }

        val json = JSONObject(responseBody)
        val dataArray = json.getJSONArray("data")

        // Sort by index to maintain order
        val embeddings = (0 until dataArray.length()).map { i ->
            val item = dataArray.getJSONObject(i)
            val index = item.getInt("index")
            val embArray = item.getJSONArray("embedding")
            val floats = FloatArray(embArray.length()) { j -> embArray.getDouble(j).toFloat() }
            index to floats
        }.sortedBy { it.first }.map { it.second }

        return embeddings
    }
}

/**
 * Cosine similarity between two vectors.
 */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "Vectors must have same dimension: ${a.size} vs ${b.size}" }
    var dot = 0f
    var normA = 0f
    var normB = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    val denom = Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())
    return if (denom == 0.0) 0f else (dot / denom).toFloat()
}

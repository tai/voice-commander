package dev.voicecommander.openai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal Responses API client: interpretation only. No SDK — the wire is
 * simple and the prototype's rule is to own the protocol.
 */
class OpenAIClient(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val model: String,
    private val url: String = "https://api.openai.com/v1/responses",
) {
    suspend fun interpret(system: String, user: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("model", model)
                    .put("input", JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", system))
                        put(JSONObject().put("role", "user").put("content", user))
                    })
                    .put("reasoning", JSONObject().put("effort", "none"))
                    .put("max_output_tokens", 500)
                    .toString()

                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(req).execute().use { resp ->
                    val json = JSONObject(resp.body?.string() ?: "")
                    if (!resp.isSuccessful) {
                        val msg = json.optJSONObject("error")?.optString("message")
                            ?: "HTTP ${resp.code}"
                        Result.failure(RuntimeException(msg))
                    } else {
                        Result.success(extractText(json))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Join the text of the last assistant message in `output`. */
    private fun extractText(json: JSONObject): String {
        val output = json.optJSONArray("output") ?: return ""
        var text = ""
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") text += part.optString("text")
            }
        }
        return text.trim()
    }
}
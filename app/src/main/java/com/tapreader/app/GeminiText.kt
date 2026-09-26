package com.tapreader.app

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Gemini text calls character casting makes (JSON mode), using the same
 * Gemini key as the reading coach. Speech itself stays on fish.audio.
 * Plain JVM code so the casting core can be tested against the live API.
 */
class GeminiText(private val key: String) {
    companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        /** Text models for casting, best first; later ones cover retirements/capacity. */
        val TEXT_MODELS = listOf("gemini-3.8-flash", "gemini-flash-latest", "gemini-3.5-flash", "gemini-2.5-flash")
    }

    class ApiException(val code: Int, message: String) : IOException(message) {
        val transient get() = code == 429 || code >= 500 || code == 0
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * JSON-mode call with audio clips attached (label, WAV bytes) — used to
     * audition candidate voices by ear. Falls through [TEXT_MODELS] like [generateJson].
     */
    fun generateJsonWithAudio(prompt: String, clips: List<Pair<String, ByteArray>>): JSONObject {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        for ((label, wav) in clips) {
            parts.put(JSONObject().put("text", "Voice $label:"))
            parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav")
                .put("data", java.util.Base64.getEncoder().encodeToString(wav))))
        }
        val payload = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0).put("responseMimeType", "application/json")
                .put("thinkingConfig", JSONObject().put("thinkingLevel", "low")))
        var last: Exception? = null
        for (model in TEXT_MODELS.filter { it.startsWith("gemini-3") }) {
            try {
                val js = withRetry(attempts = 2) { post("/models/$model:generateContent", payload, generative = true) }
                val p = js.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                val text = buildString { if (p != null) for (i in 0 until p.length()) p.optJSONObject(i)?.takeIf { !it.optBoolean("thought") }?.let { append(it.optString("text")) } }
                    .trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                return JSONObject(text)
            } catch (e: Exception) { last = e }
        }
        throw last ?: ApiException(0, "audition failed")
    }

    /** One JSON-mode generateContent call, falling through [TEXT_MODELS] on capacity errors. */
    fun generateJson(prompt: String, temperature: Double = 0.2, maxTokens: Int = 8192, fast: Boolean = false): JSONObject {
        var last: Exception? = null
        for (model in TEXT_MODELS) {
            val config = JSONObject()
                .put("temperature", temperature)
                .put("maxOutputTokens", maxTokens)
                .put("responseMimeType", "application/json")
            // Low thinking: a 1,500-word casting window in ~9 s instead of ~26 s.
            if (fast && model.startsWith("gemini-3")) config.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
            val payload = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", config)
            try {
                val js = withRetry(attempts = 2) { post("/models/$model:generateContent", payload, generative = true) }
                val parts = js.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                val text = buildString {
                    if (parts != null) for (i in 0 until parts.length()) {
                        val p = parts.optJSONObject(i) ?: continue
                        if (!p.optBoolean("thought")) append(p.optString("text"))
                    }
                }.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                if (text.isBlank()) throw ApiException(0, "empty response from $model")
                return JSONObject(text)
            } catch (e: ApiException) {
                last = e
                if (e.code == 401 || e.code == 403 || e.code == 400 && "API key" in (e.message ?: "")) throw e
            } catch (e: Exception) {
                last = e        // unparseable JSON — let the next model try
            }
        }
        throw last ?: ApiException(0, "casting failed")
    }

    // ---- HTTP ---------------------------------------------------------------

    private fun post(path: String, body: JSONObject, generative: Boolean = false): JSONObject {
        val req = Request.Builder().url(BASE + path)
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return exec(req)
    }

    private fun get(url: String): JSONObject =
        exec(Request.Builder().url(url).header("x-goog-api-key", key).get().build())

    private fun exec(req: Request): JSONObject {
        try {
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }
                        .getOrDefault(raw.take(160))
                    throw ApiException(resp.code, "HTTP ${resp.code}: ${msg.take(240)}")
                }
                return JSONObject(raw)
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException(0, e.message ?: "network error")
        }
    }

    private fun <T> withRetry(attempts: Int = 4, block: () -> T): T {
        var delay = 1500L
        var last: ApiException? = null
        repeat(attempts) { n ->
            try { return block() } catch (e: ApiException) {
                last = e
                if (!e.transient || n == attempts - 1) throw e
                // "Please retry in 12.3s" — honour the server's own hint when given.
                val hinted = Regex("retry in ([0-9.]+)s").find(e.message ?: "")?.groupValues?.get(1)?.toDoubleOrNull()
                Thread.sleep(hinted?.let { (it * 1000).toLong().coerceIn(500, 30_000) } ?: delay)
                delay = (delay * 2).coerceAtMost(12_000)
            }
        }
        throw last ?: ApiException(0, "request failed")
    }
}

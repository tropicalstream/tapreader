package com.tapreader.app

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * fish.audio speech and voice-library search for multi-voice narration.
 *
 *  - POST /v1/tts, header `model: s2.1-pro-free` (the free S2.1 Pro tier), body
 *    {text, reference_id, format: wav, sample_rate: 24000}. WAV rather than MP3
 *    because the highlight is timed from the PCM itself ([SpeechAlign]).
 *    S2.1 takes free-form delivery directions inline — "[whispering] …" — which
 *    is how a line's style ("teasing", "anxious") reaches the voice.
 *  - GET /model?title=&tag=&language=&sort_by=task_count — the public voice
 *    library, searched with phrases the cast director writes for each character.
 *
 * Plain JVM code (OkHttp + org.json) so it runs in the live unit tests.
 */
class FishSpeech(private val key: String) {
    companion object {
        const val BASE = "https://api.fish.audio"
        /** Free S2.1 Pro — https://fish.audio/blog/s2-1-pro-free-api/ */
        const val MODEL = "s2.1-pro-free"
    }

    class ApiException(val code: Int, message: String) : IOException(message) {
        val transient get() = code == 429 || code >= 500 || code == 0
        /** Anything but account/limit codes on a TTS call is overwhelmingly a dead voice id. */
        val voiceProblem get() = code != 0 && code !in setOf(401, 402, 403, 429) && code < 500
    }

    data class Voice(
        val id: String, val title: String, val description: String, val tags: List<String>,
        val languages: List<String>, val likes: Int, val uses: Int
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /** WAV bytes (24 kHz mono 16-bit) of [text] in [voice], with an optional delivery [style]. */
    fun synthesize(text: String, voice: String, style: String? = null): ByteArray {
        val spoken = if (style.isNullOrBlank()) text else "[${style.trim().trim('[', ']')}] $text"
        val payload = JSONObject()
            .put("text", spoken)
            .put("reference_id", voice)
            .put("format", "wav")
            .put("sample_rate", 24000)
            .put("normalize", true)
            .put("latency", "normal")
        val req = Request.Builder().url("$BASE/v1/tts")
            .header("Authorization", "Bearer $key")
            .header("model", MODEL)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return withRetry {
            try {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        throw ApiException(resp.code, when (resp.code) {
                            401, 403 -> "Invalid fish.audio API key"
                            402 -> "fish.audio credit exhausted"
                            429 -> "fish.audio rate limit"
                            else -> "fish.audio HTTP ${resp.code}: ${body.take(120)}"
                        })
                    }
                    val bytes = resp.body?.bytes() ?: throw ApiException(0, "empty audio")
                    if (bytes.size < 64) throw ApiException(0, "fish.audio returned no audio")
                    bytes
                }
            } catch (e: ApiException) { throw e } catch (e: IOException) {
                throw ApiException(0, e.message ?: "network error")
            }
        }
    }

    /**
     * Public library voices. A [title] search should sort by "score" (relevance —
     * "british male narrator" finds British narrators); by "task_count" it just
     * returns the most used voices. [tags] filter exactly: every voice carries
     * gender (male/female), age (young/middle-aged/old) and style tags.
     */
    fun search(title: String? = null, tags: List<String> = emptyList(), language: String? = "en", pageSize: Int = 20,
               sortBy: String = if (title.isNullOrBlank()) "task_count" else "score"): List<Voice> {
        val url = StringBuilder("$BASE/model?page_size=$pageSize&sort_by=$sortBy")
        if (!title.isNullOrBlank()) url.append("&title=").append(java.net.URLEncoder.encode(title, "UTF-8"))
        for (t in tags) url.append("&tag=").append(java.net.URLEncoder.encode(t, "UTF-8"))
        if (!language.isNullOrBlank()) url.append("&language=").append(language)
        val req = Request.Builder().url(url.toString()).header("Authorization", "Bearer $key").get().build()
        val body = withRetry(attempts = 2) {
            try {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw ApiException(resp.code, "voice search HTTP ${resp.code}")
                    resp.body?.string().orEmpty()
                }
            } catch (e: ApiException) { throw e } catch (e: IOException) { throw ApiException(0, e.message ?: "network error") }
        }
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val m = items.optJSONObject(i) ?: return@mapNotNull null
            val id = m.optString("_id").ifBlank { return@mapNotNull null }
            if (m.optBoolean("dmca_taken_down")) return@mapNotNull null
            Voice(
                id = id,
                title = m.optString("title").ifBlank { "Untitled voice" },
                description = m.optString("description"),
                tags = m.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                languages = m.optJSONArray("languages")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                likes = m.optInt("like_count"), uses = m.optInt("task_count")
            )
        }
    }

    /** A voice's library entry (for display names), or null. */
    fun voice(id: String): Voice? {
        val req = Request.Builder().url("$BASE/model/$id").header("Authorization", "Bearer $key").get().build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val m = JSONObject(resp.body?.string().orEmpty())
                Voice(id, m.optString("title"), m.optString("description"),
                    m.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                    m.optJSONArray("languages")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                    m.optInt("like_count"), m.optInt("task_count"))
            }
        }.getOrNull()
    }

    private fun <T> withRetry(attempts: Int = 4, block: () -> T): T {
        var delay = 1500L
        var last: ApiException? = null
        repeat(attempts) { n ->
            try { return block() } catch (e: ApiException) {
                last = e
                if (!e.transient || n == attempts - 1) throw e
                Thread.sleep(delay)
                delay = (delay * 2).coerceAtMost(12_000)
            }
        }
        throw last ?: ApiException(0, "request failed")
    }
}

package com.tapreader.app

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Talks to fish.audio for the web companion: voice previews and library search. */
class VoicePreviewClient(private val library: LibraryStore) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    /** Pass keyOverride to test a candidate key before it is saved. */
    fun preview(voiceId: String, text: String, keyOverride: String? = null): ByteArray? {
        val key = (keyOverride ?: library.getString(LibraryStore.K_FISH_KEY, "")).trim()
        if (key.isBlank() || voiceId.isBlank()) return null
        val payload = JSONObject()
            .put("text", text.take(280).ifBlank { "This is how I sound in TapReader." })
            .put("format", "mp3").put("mp3_bitrate", 128).put("normalize", true)
            .put("latency", "normal").put("reference_id", voiceId)
        val request = Request.Builder().url("https://api.fish.audio/v1/tts")
            .header("Authorization", "Bearer $key").header("model", TtsReader.MODEL)
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        return runCatching { http.newCall(request).execute().use { if (it.isSuccessful) it.body?.bytes() else null } }.getOrNull()
    }

    /** Like preview, but reports WHY fish.audio failed so the key tester can be honest. */
    fun check(voiceId: String, keyOverride: String? = null): Result<Unit> {
        val key = (keyOverride ?: library.getString(LibraryStore.K_FISH_KEY, "")).trim()
        if (key.isBlank()) return Result.failure(IllegalStateException("No fish.audio key saved"))
        val payload = JSONObject().put("text", "TapReader connection check.")
            .put("format", "mp3").put("latency", "normal").put("reference_id", voiceId)
        val request = Request.Builder().url("https://api.fish.audio/v1/tts")
            .header("Authorization", "Bearer $key").header("model", TtsReader.MODEL)
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        return runCatching {
            http.newCall(request).execute().use { res ->
                if (res.isSuccessful) return@runCatching
                throw IllegalStateException(when (res.code) {
                    401, 403 -> "fish.audio rejected the key (HTTP ${res.code})"
                    402 -> "fish.audio says the account has no credit (HTTP 402)"
                    404 -> "Key is valid but the selected voice was not found (HTTP 404)"
                    429 -> "fish.audio is rate-limiting requests right now (HTTP 429) — wait a minute and try again"
                    else -> "fish.audio error (HTTP ${res.code})"
                })
            }
        }
    }

    /**
     * Searches the public fish.audio voice library by keyword (matched against
     * voice titles) and returns the most-used matches first. Returns null when no
     * fish.audio key is saved, an empty array when the request simply found nothing.
     */
    fun search(query: String): JSONArray? {
        val key = library.getString(LibraryStore.K_FISH_KEY, "").trim()
        if (key.isBlank()) return null
        val q = query.trim()
        val url = StringBuilder("https://api.fish.audio/model?page_size=16&sort_by=task_count")
        if (q.isNotBlank()) url.append("&title=").append(java.net.URLEncoder.encode(q, "UTF-8"))
        val request = Request.Builder().url(url.toString()).header("Authorization", "Bearer $key").get().build()
        val bodyStr = runCatching {
            http.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return null
        val items = runCatching { JSONObject(bodyStr).optJSONArray("items") }.getOrNull() ?: return JSONArray()
        val out = JSONArray()
        for (i in 0 until items.length()) {
            val m = items.optJSONObject(i) ?: continue
            val id = m.optString("_id")
            if (id.isBlank()) continue
            out.put(JSONObject()
                .put("id", id)
                .put("title", m.optString("title").ifBlank { "Untitled voice" })
                .put("author", m.optJSONObject("author")?.optString("nickname").orEmpty())
                .put("languages", m.optJSONArray("languages") ?: JSONArray())
                .put("tags", m.optJSONArray("tags") ?: JSONArray())
                .put("likes", m.optInt("like_count"))
                .put("uses", m.optInt("task_count")))
        }
        return out
    }
}

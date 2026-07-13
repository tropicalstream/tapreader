package com.tapreader.app

import android.util.Log
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Summarizes the passage a reader has just read and glosses its hard words, via
 * any OpenAI-compatible chat endpoint (defaults to Google Gemini's free tier).
 * The reader supplies their own key in Settings. Output is spoken aloud (fish
 * TTS) — never shown — so prompts ask for plain prose that reads well.
 */
class SummaryClient(private val library: LibraryStore) {
    companion object {
        private const val TAG = "TapReader"
        data class Provider(val id: String, val label: String, val baseUrl: String, val model: String, val keyHint: String)
        val PROVIDERS = listOf(
            Provider("gemini", "Google Gemini (free)", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-flash-lite-latest", "AIza…"),
            Provider("groq", "Groq", "https://api.groq.com/openai/v1", "openai/gpt-oss-120b", "gsk_…"),
            Provider("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "sk-…")
        )
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    fun provider(): Provider =
        PROVIDERS.firstOrNull { it.id == library.getString(LibraryStore.K_LLM_PROVIDER, "gemini") } ?: PROVIDERS[0]

    fun key(): String = library.getString("llm_key_${provider().id}", "")
    fun hasKey(): Boolean = key().isNotBlank()
    fun setProvider(id: String) { if (PROVIDERS.any { it.id == id }) library.putString(LibraryStore.K_LLM_PROVIDER, id) }
    fun setKey(k: String) = library.putString("llm_key_${provider().id}", k.trim())

    /**
     * Summarize [passage] (the text read so far in the current section) plus a
     * short glossary of advanced/archaic words. Spoiler-bounded to the passage.
     */
    fun summarize(title: String, author: String, passage: String, onResult: (Result<String>) -> Unit) {
        if (!hasKey()) { onResult(Result.failure(Exception("Add a summary AI key in Settings"))); return }
        val byline = if (author.isNotBlank()) " by $author" else ""
        val system = "You are a concise, spoken reading companion. You explain what a reader has just read without ever revealing anything beyond the passage they give you."
        val user = "From “$title”$byline, summarize the following passage in NO MORE THAN 3 short sentences of plain spoken prose. " +
            "Then, only if there are any, say “Words worth knowing:” and define up to 3 advanced, archaic, or antiquated words as “word — meaning”. " +
            "Do not mention anything that happens after this passage, and don't use headings, markdown, or bullet symbols — it will be read aloud.\n\nPassage:\n${passage.take(20000)}"
        Thread {
            val res = runCatching {
                val p = provider()
                val body = JSONObject()
                    .put("model", p.model)
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)))
                    .put("temperature", 0.5)
                    .put("max_tokens", 300)
                val req = Request.Builder()
                    .url("${p.baseUrl}/chat/completions")
                    .header("Authorization", "Bearer ${key()}")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val msg = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }
                            .getOrDefault("HTTP ${resp.code}")
                        throw Exception(msg.take(140))
                    }
                    val obj = if (raw.trimStart().startsWith("[")) JSONArray(raw).getJSONObject(0) else JSONObject(raw)
                    obj.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                }
            }.onFailure { Log.w(TAG, "summarize failed: ${it.message}") }
            onResult(res)
        }.start()
    }
}

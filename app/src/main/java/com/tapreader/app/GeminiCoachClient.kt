package com.tapreader.app

import android.util.Log
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Privacy-bounded reading coach. Only text at or before the saved reading focus
 * is ever sent to Gemini. The prompt asks for a recap and forward-looking
 * encouragement without plot, character, event, or outcome disclosures.
 */
class GeminiCoachClient(private val library: LibraryStore) {
    companion object {
        private const val TAG = "TapReaderCoach"
        // Flash-Lite is ample for a bounded reading recap and is low-latency. Use
        // the "-latest" aliases so Google can rotate the underlying version without
        // 404ing us (pinned versions like gemini-2.5-flash-lite get retired). The
        // fallbacks cover an individual model's temporary capacity spike.
        private val MODELS = listOf(
            "gemini-flash-lite-latest",
            "gemini-flash-latest",
            "gemini-2.5-flash"
        )
    }

    data class Note(val summary: String, val encouragement: String, val fresh: Boolean = false)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    fun hasKey(): Boolean = library.getString("llm_key_gemini", "").isNotBlank()

    /** A tiny live call used by the companion before a reader relies on coaching.
     *  Pass a candidate key to test it before it is saved; null tests the stored key. */
    fun validateKey(candidate: String? = null): Result<String> = runCatching {
        val key = (candidate ?: library.getString("llm_key_gemini", "")).trim()
        require(key.isNotBlank()) { "No Gemini key saved" }
        val text = generate(key, "Reply with exactly: TapReader Gemini connection confirmed.", json = false)
        require(text.isNotBlank()) { "Gemini returned an empty response" }
        "Gemini key is working"
    }.onFailure { Log.w(TAG, "Gemini key test failed: ${it.message}") }

    fun coach(book: Book, focusedWord: Int): Result<Note> = runCatching {
        val key = library.getString("llm_key_gemini", "").trim()
        require(key.isNotBlank()) { "Add a Gemini API key in Settings" }
        val focus = focusedWord.coerceIn(0, (book.wordCount - 1).coerceAtLeast(0))
        // Nothing meaningfully read yet: coach how to ENGAGE the book instead of
        // refusing (there is nothing to recap, but plenty to say about starting).
        if (focus < 30) return@runCatching freshNote(key, book)
        val chapter = book.chapterAt(focus)
        val chapterStart = book.chapterStarts.getOrElse(chapter) { 0 }
        // Keep the request useful for long chapters without transmitting the whole book.
        val start = maxOf(chapterStart, focus - 1_000)
        val readPassage = book.words.subList(start, focus + 1).joinToString(" ") { it.text }.takeLast(6_000)
        if (readPassage.length < 100) return@runCatching freshNote(key, book)
        val prompt = """
            You are TapReader's careful reading coach. The reader has reached chapter ${chapter + 1}, word ${focus + 1} of ${book.wordCount} in \"${book.title}\"${if (book.author.isNotBlank()) " by ${book.author}" else ""}.
            The only source text you may use is the excerpt below, which ends exactly where the reader stopped.

            Return valid JSON with exactly two strings:
            {"summary":"...","encouragement":"..."}

            summary: one coherent paragraph that recaps only what appears in the supplied excerpt. Do not infer or reveal anything beyond it.
            encouragement: 2-3 warm sentences about broad themes, questions, moods, or craft the reader may continue to explore. Never name a future event, outcome, revelation, new character, relationship change, location, or plot detail. Do not spoil anything, even vaguely.

            EXCERPT ENDS HERE:
            $readPassage
        """.trimIndent()
        val text = generate(key, prompt, json = true)
        parseCoachNote(text) ?: run {
            // Some Gemini deployments ignore responseMimeType or return separate
            // text parts. Recover with a compact labelled response, rather than
            // exposing a parser error from a partial "{" model reply.
            val retry = generate(key, """
                Based only on this already-read text from \"${book.title}\", write exactly two labelled paragraphs:
                RECAP: one paragraph about what is explicitly in the text.
                ENCOURAGEMENT: 2-3 spoiler-free sentences only about themes, moods, or questions ahead. Never reveal an event, outcome, new character, or plot detail.

                ${readPassage.takeLast(10_000)}
            """.trimIndent(), json = false)
            parseLabelledCoachNote(retry) ?: throw IllegalStateException("Gemini returned no usable reading note. Test the Gemini key in Settings.")
        }
    }.onFailure { Log.w(TAG, "coach failed: ${it.message}") }

    /** Pre-reading coaching: how to approach a book the reader hasn't begun. */
    private fun freshNote(key: String, book: Book): Note {
        val opening = book.words.take(400).joinToString(" ") { it.text }.take(2_000)
        val prompt = """
            You are TapReader's reading coach. The reader has \"${book.title}\"${if (book.author.isNotBlank()) " by ${book.author}" else ""} in their library and has not started it yet.

            Return valid JSON with exactly two strings:
            {"summary":"...","encouragement":"..."}

            summary: one paragraph on how to engage this book: what kind of work it is, its setting or subject, its tone and pacing, and what to pay attention to in the early pages (voices, names, structure, era-specific language). Use general knowledge of the book if you recognize it, plus the opening excerpt below. Reveal nothing beyond the premise.
            encouragement: 2-3 warm sentences inviting the reader to take the first step, perhaps suggesting a comfortable first sitting (e.g. the opening chapter). No spoilers.

            OPENING EXCERPT:
            $opening
        """.trimIndent()
        val text = generate(key, prompt, json = true)
        return (parseCoachNote(text)
            ?: throw IllegalStateException("Gemini returned no usable note. Test the Gemini key in Settings."))
            .copy(fresh = true)
    }

    private fun generate(key: String, prompt: String, json: Boolean): String {
        val config = JSONObject().put("temperature", 0.45).put("maxOutputTokens", if (json) 550 else 500)
        if (json) config.put("responseMimeType", "application/json")
        val payload = JSONObject()
            .put("contents", org.json.JSONArray().put(JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", config)
        var lastFailure: Exception? = null
        for ((index, model) in MODELS.withIndex()) {
            val result = runCatching { generateWithModel(model, key, payload) }
            result.getOrNull()?.let { return it }
            val failure = result.exceptionOrNull() as? Exception ?: Exception("Gemini request failed")
            lastFailure = failure
            // Invalid keys and permission errors cannot be fixed by switching
            // models; capacity/rate errors can, so only continue for those.
            if (!isTransientGeminiFailure(failure.message.orEmpty())) throw failure
            if (index < MODELS.lastIndex) Thread.sleep(250L * (index + 1))
        }
        throw IllegalStateException("Gemini is temporarily busy across the available Flash models. Please try again shortly.", lastFailure)
    }

    private fun generateWithModel(model: String, key: String, payload: JSONObject): String {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val request = Request.Builder().url(endpoint).header("x-goog-api-key", key)
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        return http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }.getOrDefault("Gemini HTTP ${response.code}")
                throw IllegalStateException("HTTP ${response.code}: ${message.take(180)}")
            }
            val parts = JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IllegalStateException("Gemini returned no response candidates")
            buildString {
                for (i in 0 until parts.length()) parts.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }?.let { append(it) }
            }.trim().takeIf { it.isNotBlank() } ?: throw IllegalStateException("Gemini returned an empty response")
        }
    }

    private fun isTransientGeminiFailure(message: String): Boolean {
        val lower = message.lowercase()
        return "http 429" in lower || "http 500" in lower || "http 503" in lower ||
            "high demand" in lower || "temporar" in lower || "unavailable" in lower || "overloaded" in lower ||
            // A retired/unavailable model should fall through to the next one, not
            // abort the whole request.
            "http 404" in lower || "not found" in lower || "not supported" in lower ||
            "does not exist" in lower || "is not available" in lower
    }

    private fun parseCoachNote(raw: String): Note? = runCatching {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val o = JSONObject(clean)
        Note(o.getString("summary").trim(), o.getString("encouragement").trim())
    }.getOrNull()?.takeIf { it.summary.isNotBlank() && it.encouragement.isNotBlank() }

    private fun parseLabelledCoachNote(raw: String): Note? {
        val match = Regex("(?is)RECAP:\\s*(.+?)\\s*ENCOURAGEMENT:\\s*(.+)").find(raw) ?: return null
        return Note(match.groupValues[1].trim(), match.groupValues[2].trim()).takeIf { it.summary.isNotBlank() && it.encouragement.isNotBlank() }
    }
}

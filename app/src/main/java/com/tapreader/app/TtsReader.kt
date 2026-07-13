package com.tapreader.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * fish.audio narration with word-accurate highlight.
 *
 * TTS engines don't hand back reliable per-word timestamps, so we get perfect
 * sync a different way: synthesize ONE sentence at a time (keeps natural
 * prosody, and the sentence's audio duration is then known exactly), then during
 * playback we map the live playback position to a word using per-word weights
 * (letters + punctuation pauses). Because sentences are short, proportional
 * mapping lands the highlight on the right word essentially every time. The next
 * sentence is prefetched while the current one plays, so narration is gapless.
 */
class TtsReader(private val context: Context) {
    companion object {
        private const val TAG = "TapReader"
        private const val BASE = "https://api.fish.audio/v1/tts"

        /** Curated starting voices (fish.audio needs a voice reference_id — there is
         *  no "default" voice). Users can paste any reference_id from the fish.audio
         *  voice library in Settings. */
        data class Voice(val label: String, val referenceId: String)
        const val DEFAULT_VOICE = "7f92f8afb8ec43bf81429cc1c9199cb1"
        val SUGGESTED = listOf(
            Voice("Default narrator", DEFAULT_VOICE),
            Voice("Voice B", "802e3bc2b27e49c2995d23ef70e6ac89"),
            Voice("Voice C", "933563129e564b19a115bedd57b7406a")
        )
        // fish.audio model header. Valid: s1 / s2-pro / s2.1-pro / s2.1-pro-free
        // (free-tier default). speech-1.5 was retired.
        const val MODEL = "s2.1-pro-free"

        /**
         * fish.audio chokes on typography it cannot voice — a stray ')' can send
         * the model into a loop of groaning noises. Reduce everything we speak to
         * characters with a known spoken value: letters, digits, and . , ; - " ' ? !
         * Unsafe characters are converted to their nearest audible equivalent
         * (parentheticals and colons become comma pauses, all dash styles become
         * "-", ellipses become periods); anything else is dropped.
         */
        fun sanitizeForSpeech(raw: String): String {
            val sb = StringBuilder(raw.length)
            for (ch in raw) when (ch) {
                '.', ',', ';', '"', '?', '!' -> sb.append(ch)
                '\'', '’', '‘' -> sb.append('\'')
                '“', '”', '«', '»' -> sb.append('"')
                '-', '–', '—', '‒', '−' -> sb.append('-')
                '(', ')', '[', ']', '{', '}', ':' -> sb.append(',')
                '…' -> sb.append('.')
                else -> if (ch.isLetterOrDigit()) sb.append(ch) else if (ch.isWhitespace()) sb.append(' ') else Unit
            }
            return sb.toString()
                .replace(Regex("\\.(?:\\s*\\.)+"), ".")      // "..." / ". . ." -> "."  (runs of dots read as long dead air)
                .replace(Regex("-(?:\\s*-)+"), "-")          // "--" -> "-"
                .replace(Regex("[,;]\\s*(?=[.,;?!])"), "")   // ", ." -> "." and ", ," -> ","
                .replace(Regex("\\s+"), " ")
                .trim().trim(',', ';', '-', ' ')
        }

        /**
         * Whether a token ends a sentence, for chunking. Ellipsis ("..."/"…") is a
         * soft trailing-off pause, not a hard stop, so a phrase that trails off keeps
         * flowing into the next fragment instead of being cut into its own utterance.
         */
        fun endsSentence(token: String): Boolean {
            if (token.endsWith("...") || token.endsWith("…")) return false
            return token.endsWith(".") || token.endsWith("!") || token.endsWith("?") ||
                token.endsWith(".\"") || token.endsWith(".”") || token.endsWith("!\"") ||
                token.endsWith("!”") || token.endsWith("?\"") || token.endsWith("?”")
        }
    }

    var onWord: ((globalWordIndex: Int) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStopped: (() -> Unit)? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var active = false
    @Volatile private var paused = false
    private var player: MediaPlayer? = null
    private var oneOff: MediaPlayer? = null
    private var book: Book? = null
    private var key = ""
    private var voiceId = ""

    private data class Sentence(val startWord: Int, val words: List<Int>, val text: String, val weights: FloatArray)
    private var sentences: List<Sentence> = emptyList()
    private var sentenceAt = 0
    private var prefetch: Pair<Int, File>? = null

    val isActive: Boolean get() = active
    val isPaused: Boolean get() = active && paused

    fun configure(key: String, voiceId: String) { this.key = key.trim(); this.voiceId = voiceId.trim() }

    fun start(b: Book, fromWord: Int) {
        if (key.isBlank()) { onError?.invoke("Add your fish.audio API key in Settings"); return }
        stopInternal()
        book = b
        sentences = buildSentences(b)
        sentenceAt = sentences.indexOfLast { it.startWord <= fromWord }.coerceAtLeast(0)
        active = true
        paused = false
        speakCurrent()
    }

    /** Pause narration without tearing it down (resumable). */
    fun pause() {
        if (!active || paused) return
        paused = true
        main.removeCallbacks(poll)
        runCatching { player?.pause() }
    }

    /** Resume paused narration from where it left off. */
    fun resume() {
        if (!active || !paused) return
        paused = false
        val p = player
        if (p != null) { runCatching { p.start() }; main.post(poll) }
        else speakCurrent()   // was paused between sentences — start the next one
    }

    fun stop() { stopInternal(); onStopped?.invoke() }

    /**
     * Speak a one-off utterance (e.g. an AI section summary) on its own player,
     * without disturbing the book-narration pipeline. Pause the book first if you
     * don't want them overlapping.
     */
    fun speakOnce(text: String, onStart: () -> Unit = {}, onDone: () -> Unit = {}) {
        if (key.isBlank()) { onError?.invoke("Add your fish.audio API key in Settings"); onDone(); return }
        stopOneOff()
        val speakable = sanitizeForSpeech(text).take(4000)
        if (speakable.isBlank()) { onDone(); return }
        Thread {
            val file = synth(speakable)
            main.post {
                if (file == null) { onDone(); return@post }
                runCatching {
                    val mp = MediaPlayer()
                    oneOff = mp
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                    )
                    mp.setDataSource(file.absolutePath)
                    mp.setOnCompletionListener { runCatching { mp.release() }; if (oneOff === mp) oneOff = null; file.delete(); onDone() }
                    mp.setOnErrorListener { _, _, _ -> runCatching { mp.release() }; if (oneOff === mp) oneOff = null; file.delete(); onDone(); true }
                    mp.prepare(); mp.start(); onStart()
                }.onFailure { file.delete(); onDone() }
            }
        }.start()
    }

    val isSpeakingOnce: Boolean get() = oneOff?.isPlaying == true

    fun stopOneOff() {
        runCatching { oneOff?.stop() }; runCatching { oneOff?.release() }; oneOff = null
    }

    private fun stopInternal() {
        active = false
        paused = false
        stopOneOff()
        main.removeCallbacks(poll)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        prefetch?.second?.delete()
        prefetch = null
    }

    // ---- Sentence pipeline -------------------------------------------------

    private fun speakCurrent() {
        if (!active) return
        val b = book ?: return
        if (sentenceAt >= sentences.size) { stop(); return }
        val s = sentences[sentenceAt]
        if (s.text.isBlank()) {
            // Nothing speakable survived sanitizing (e.g. a lone ')' paragraph) —
            // advance the highlight past it and move on without calling fish.
            onWord?.invoke((s.startWord + s.words.size).coerceAtMost(b.wordCount - 1))
            sentenceAt++
            speakCurrent()
            return
        }
        onWord?.invoke(s.startWord)

        val cached = prefetch?.takeIf { it.first == sentenceAt }?.second
        prefetch = null
        if (cached != null) { playFile(cached, s) ; prefetchNext(); return }

        Thread {
            val file = synth(s.text)
            main.post {
                if (!active) { file?.delete(); return@post }
                if (file == null) { /* error already surfaced */ stop(); return@post }
                playFile(file, s)
                prefetchNext()
            }
        }.start()
    }

    private fun prefetchNext() {
        val next = sentenceAt + 1
        if (next >= sentences.size) return
        val s = sentences[next]
        if (s.text.isBlank()) return   // skipped silently by speakCurrent
        Thread {
            val f = synth(s.text)
            main.post { if (active && f != null) prefetch = next to f else f?.delete() }
        }.start()
    }

    private fun playFile(file: File, s: Sentence) {
        runCatching {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                runCatching { mp.release() }; if (player === mp) player = null
                file.delete()
                if (active) { onWord?.invoke((s.startWord + s.words.size).coerceAtMost((book?.wordCount ?: 1) - 1)); sentenceAt++; speakCurrent() }
            }
            mp.setOnErrorListener { _, w, e -> Log.w(TAG, "tts play err $w/$e"); file.delete(); if (active) { sentenceAt++; speakCurrent() }; true }
            mp.prepare()
            mp.start()
            startPolling(s, mp.duration)
            // If the user paused while this sentence was still synthesizing, honor it.
            if (paused) { runCatching { mp.pause() }; main.removeCallbacks(poll) }
        }.onFailure {
            Log.w(TAG, "playFile failed: ${it.message}"); file.delete()
            if (active) { sentenceAt++; speakCurrent() }
        }
    }

    // ---- Position -> word mapping (the sync core) --------------------------

    private var pollSentence: Sentence? = null
    private var pollDuration = 1
    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            val mp = player ?: return
            val s = pollSentence ?: return
            val pos = runCatching { mp.currentPosition }.getOrDefault(0)
            val frac = (pos.toFloat() / pollDuration.coerceAtLeast(1)).coerceIn(0f, 1f)
            val total = s.weights.sum().coerceAtLeast(0.0001f)
            var acc = 0f
            var wi = 0
            for (i in s.weights.indices) {
                val next = acc + s.weights[i]
                if (frac * total < next) { wi = i; break }
                acc = next; wi = i
            }
            onWord?.invoke(s.startWord + wi)
            main.postDelayed(this, 40L)
        }
    }

    private fun startPolling(s: Sentence, duration: Int) {
        pollSentence = s
        pollDuration = duration.coerceAtLeast(1)
        main.removeCallbacks(poll)
        main.post(poll)
    }

    // ---- fish.audio synthesis ---------------------------------------------

    private fun synth(text: String): File? {
        return try {
            val payload = JSONObject()
                .put("text", text)
                .put("format", "mp3")
                .put("mp3_bitrate", 128)
                .put("normalize", true)
                .put("latency", "normal")
                // A voice is required; fall back to the default narrator.
                .put("reference_id", voiceId.ifBlank { DEFAULT_VOICE })
            val req = Request.Builder()
                .url(BASE)
                .header("Authorization", "Bearer $key")
                .header("model", MODEL)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val msg = when (resp.code) {
                        401, 403 -> "Invalid fish.audio API key"
                        402 -> "fish.audio credit exhausted"
                        429 -> "fish.audio rate limit — slowing down"
                        else -> "TTS error ${resp.code}: ${body.take(100)}"
                    }
                    main.post { onError?.invoke(msg) }
                    return null
                }
                val f = File.createTempFile("tts_", ".mp3", context.cacheDir)
                f.outputStream().use { out -> resp.body?.byteStream()?.copyTo(out) }
                f
            }
        } catch (e: Exception) {
            Log.w(TAG, "synth failed: ${e.message}")
            val net = e is java.net.UnknownHostException || (e.message?.contains("host") == true)
            main.post { onError?.invoke(if (net) "No internet connection" else "TTS request failed") }
            null
        }
    }

    // ---- Build sentences from the word stream ------------------------------

    private fun buildSentences(b: Book): List<Sentence> {
        val out = ArrayList<Sentence>()
        var i = 0
        val n = b.wordCount
        while (i < n) {
            val startWord = i
            val idxs = ArrayList<Int>()
            val sb = StringBuilder()
            while (i < n) {
                // A paragraph break ends the chunk only when the previous token
                // actually finished a sentence. A break mid-sentence is a spurious
                // line wrap from a poorly-made EPUB/PDF (every display line becomes
                // its own paragraph) — coalesce across it so fish speaks one flowing
                // utterance instead of many choppy fragments, each with its own
                // trailing-off prosody and a player hand-off gap.
                if (idxs.isNotEmpty() && b.words[i].paragraphBreak && endsSentence(b.words[idxs.last()].text)) break
                val t = b.words[i].text
                idxs.add(i)
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(t)
                i++
                if (endsSentence(t) && idxs.size >= 3) break
                if (idxs.size >= 40) break
            }
            val weights = FloatArray(idxs.size) { k ->
                val t = b.words[idxs[k]].text
                val letters = t.count { it.isLetterOrDigit() }.coerceAtLeast(1)
                var w = 1.2f + letters * 0.9f
                if (t.endsWith(",") || t.endsWith(";") || t.endsWith(":")) w += 2.5f
                if (t.endsWith(".") || t.endsWith("!") || t.endsWith("?") || t.endsWith("…")) w += 4.5f
                w
            }
            out.add(Sentence(startWord, idxs, sanitizeForSpeech(sb.toString()), weights))
        }
        return out
    }
}

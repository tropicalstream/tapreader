package com.tapreader.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Multi-voice fish.audio narration with a word-accurate highlight.
 *
 * The book is split into utterances ([SpeechPlan]): narration and each spoken
 * line separately, sentence-sized. A [CastDirector] reads just ahead of the
 * reader (using the Gemini key, as the reading coach does) and decides who
 * says every line and which fish.audio library voice suits each character.
 * Without a Gemini key, or with character voices off, everything is read in
 * the voice chosen in Settings — as before, but with the new highlight.
 *
 * Each utterance is synthesized on its own (in its speaker's voice, with the
 * line's delivery direction as an S2.1 "[bracket]" cue), several ahead of
 * playback so narration is gapless. fish.audio returns WAV, so word timing
 * comes from the audio itself
 * ([SpeechAlign]): pauses in the waveform are pinned to punctuation, and the
 * highlight follows the actual speech rather than an even spread.
 */
class TtsReader(private val context: Context) {
    companion object {
        private const val TAG = "TapReader"
        /** Utterances synthesized ahead. Runs of one-word lines ("Bah!" / "said
         *  Scrooge," / "Humbug!") play faster than a synth round trip, so keep a
         *  deep queue; four requests in flight at once. */
        private const val PREFETCH = 5
        private const val SYNTH_THREADS = 4
        private const val CAST_AHEAD_WORDS = 2500
        private const val CACHE_LIMIT_BYTES = 400L * 1024 * 1024

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
        // Auto-found stand-in voice (persisted so the next session skips the search).
        private const val K_VOICE_SUB = "fish_voice_substitute"

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
                token.endsWith("!”") || token.endsWith("?\"") || token.endsWith("?”") ||
                token.endsWith(".’") || token.endsWith("!’") || token.endsWith("?’")
        }

        /**
         * The text sent for one utterance. A spoken line loses its own outer
         * quotation marks (the voice change already says "this is speech", and
         * a stray mark can colour the delivery); nothing else changes, so the
         * words the voice says are exactly the words being highlighted.
         */
        fun speakable(words: List<String>, isQuote: Boolean): String {
            if (words.isEmpty()) return ""
            if (!isQuote) return words.joinToString(" ")
            val list = words.toMutableList()
            list[0] = list[0].replaceFirst(Regex("^([(\\[—–-]*)[“\"‘'«„]"), "$1")
            val last = list.size - 1
            list[last] = list[last].replace(Regex("[”\"’'»]([.,;:!?—–-]*)$"), "$1")
            return list.joinToString(" ")
        }
    }

    var onWord: ((globalWordIndex: Int) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    /** Non-error information the reader should see ("Casting voices…"). */
    var onNotice: ((String) -> Unit)? = null
    var onStopped: (() -> Unit)? = null
    /** Who is speaking now (character name, or the narrator). */
    var onSpeaker: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val synthPool = Executors.newFixedThreadPool(SYNTH_THREADS) { r -> Thread(r, "tts-synth").apply { isDaemon = true } }

    @Volatile private var active = false
    @Volatile private var paused = false
    private var player: MediaPlayer? = null
    private var oneOff: MediaPlayer? = null

    private var key = ""
    private var geminiKey = ""
    private var voiceId = ""
    private var multiVoice = true
    private var narratorIsMine = false
    private var fish: FishSpeech? = null
    private var director: CastDirector? = null

    private var book: Book? = null
    private var dialogue: Dialogue.Map? = null
    private var dialogueFor = ""
    @Volatile private var cast: Cast? = null

    private var plan: List<SpeechPlan.Utterance> = emptyList()
    private var at = 0
    private val pending = ConcurrentHashMap<Int, Future<Clip?>>()

    private class Clip(val file: File, val timing: SpeechAlign.Timing, val speaker: String)

    val isActive: Boolean get() = active
    val isPaused: Boolean get() = active && paused
    val currentCast: Cast? get() = cast

    /**
     * [key] fish.audio; [voiceId] the voice chosen in Settings; [geminiKey] enables
     * character casting. [narratorIsMine] keeps the chosen voice as every book's
     * narrator instead of casting one to suit the book.
     */
    fun configure(key: String, voiceId: String, geminiKey: String = "", multiVoice: Boolean = true, narratorIsMine: Boolean = false) {
        val k = key.trim(); val g = geminiKey.trim()
        this.voiceId = voiceId.trim().ifBlank { DEFAULT_VOICE }
        if (k != this.key || g != this.geminiKey || fish == null) {
            this.key = k; this.geminiKey = g
            casts.clear()
            fish = if (k.isBlank()) null else FishSpeech(k)
            director = if (k.isBlank() || g.isBlank()) null else
                CastDirector(GeminiText(g), fish!!, File(context.filesDir, "casts"), this.voiceId, narratorIsMine) { Log.i(TAG, "cast: $it") }
        }
        this.multiVoice = multiVoice && director != null
        this.narratorIsMine = narratorIsMine
        director?.selectedVoice = this.voiceId
        director?.narratorIsMine = narratorIsMine
    }

    /** The cast director, for the companion's cast view and book deletion. */
    fun director(): CastDirector? = director

    /** Drops the in-memory cast so the next play casts the book afresh (after "Re-cast"). */
    fun forgetCast(bookId: String) { casts.remove(bookId); if (cast?.bookId == bookId) cast = null }

    // One Cast object per book for the whole session: voice designs finish on
    // background threads and must land on the same object narration reads.
    private val casts = ConcurrentHashMap<String, Cast>()
    private fun castFor(b: Book): Cast? = director?.let { d -> casts.getOrPut(b.id) { d.load(b) } }

    private fun dialogueFor(b: Book): Dialogue.Map = synchronized(this) {
        if (dialogueFor != b.id || dialogue == null) { dialogue = Dialogue.analyze(b); dialogueFor = b.id }
        dialogue!!
    }

    /**
     * Called when a book is opened: casts the narrator and the first window in
     * the background (and starts designing the narrator's voice), so pressing
     * play a moment later speaks almost at once instead of waiting ~20 s for a
     * voice to be designed. Does nothing for a book already cast.
     */
    fun prepare(b: Book, fromWord: Int) {
        val d = director ?: return
        val c = castFor(b) ?: return
        if (!multiVoice || (c.ready && c.isCast(fromWord))) return
        Thread({
            runCatching {
                d.ensureNarrator(b, c)
                if (multiVoice) d.castThrough(b, c, dialogueFor(b), fromWord, fromWord)
            }.onFailure { Log.w(TAG, "prepare: ${it.message}") }
        }, "tts-prepare").apply { isDaemon = true }.start()
    }

    fun start(b: Book, fromWord: Int) {
        if (fish == null) { onError?.invoke("Add your fish.audio API key in Settings"); return }
        stopInternal()
        book = b
        dialogueFor(b)
        cast = if (multiVoice) castFor(b) else null
        val from = fromWord.coerceIn(0, (b.wordCount - 1).coerceAtLeast(0))
        plan = SpeechPlan.build(b, dialogue!!, from, b.wordCount)
        at = 0
        active = true
        paused = false
        onWord?.invoke(from)
        startCaster(session, from)
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
        else speakCurrent()   // was paused between utterances — start the next one
    }

    fun stop() { stopInternal(); onStopped?.invoke() }

    // ---- Casting thread -----------------------------------------------------

    private val castLock = Object()
    @Volatile private var readingAt = 0

    private fun startCaster(mySession: Int, from: Int) {
        val d = director ?: return
        val b = book ?: return
        val c = cast ?: return
        val dm = dialogue ?: return
        readingAt = from
        Thread({
            try {
                if (!c.ready) main.post { if (mySession == session) onNotice?.invoke("Casting voices for this book — first time only, ~15 s") }
                else if (!c.isCast(from)) main.post { if (mySession == session) onNotice?.invoke("Casting voices…") }
                if (!c.ready) d.ensureNarrator(b, c)
                while (active && mySession == session) {
                    val frontier = synchronized(c) { c.castFrontier(readingAt) }
                    if (frontier >= b.wordCount || frontier > readingAt + CAST_AHEAD_WORDS) {
                        synchronized(castLock) { castLock.notifyAll(); castLock.wait(1500) }
                        continue
                    }
                    d.castThrough(b, c, dm, frontier, frontier)   // one window
                    synchronized(castLock) { castLock.notifyAll() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "caster: ${e.message}")
            } finally {
                synchronized(castLock) { castLock.notifyAll() }
            }
        }, "tts-cast").apply { isDaemon = true }.start()
    }

    /**
     * Blocks a synth thread until the words it needs have been cast. Casting
     * failures never stall narration: the cast director marks a failed window
     * as covered, and single-voice mode needs no windows at all.
     */
    private fun awaitCast(u: SpeechPlan.Utterance, mySession: Int) {
        val c = cast ?: return
        val until = System.currentTimeMillis() + 90_000
        synchronized(castLock) {
            while (active && mySession == session && System.currentTimeMillis() < until) {
                val ok = synchronized(c) { c.ready && c.isCast(u.startWord) }
                if (ok) return
                castLock.wait(300)
            }
        }
    }

    // ---- Utterance pipeline -------------------------------------------------

    // Restart token: bumped on every stop/start. In-flight synthesis from a
    // superseded run carries the old value, so its audio is discarded instead
    // of playing on top of the new run.
    @Volatile private var session = 0

    private fun speakCurrent() {
        if (!active) return
        val b = book ?: return
        if (at >= plan.size) { stop(); return }
        val index = at
        val u = plan[index]
        readingAt = u.startWord
        synchronized(castLock) { castLock.notifyAll() }
        onWord?.invoke(u.startWord)
        val mySession = session
        val future = request(index, mySession)
        prefetch(index, mySession)
        Thread({
            val clip = runCatching { future.get() }.getOrNull()
            main.post {
                if (!active || mySession != session || at != index) return@post
                pending.remove(index)
                if (clip == null) {
                    // Nothing speakable (a lone ")" paragraph) or a failed request that
                    // already surfaced its error: skip on unless the failure was fatal.
                    if (lastFatal) { stop(); return@post }
                    onWord?.invoke(u.endWord.coerceAtMost(b.wordCount - 1))
                    at++; speakCurrent(); return@post
                }
                play(clip, u, mySession)
            }
        }, "tts-wait").apply { isDaemon = true }.start()
    }

    private fun prefetch(from: Int, mySession: Int) {
        for (k in from + 1..(from + PREFETCH).coerceAtMost(plan.size - 1)) request(k, mySession)
    }

    private fun request(index: Int, mySession: Int): Future<Clip?> = pending.getOrPut(index) {
        val u = plan[index]
        synthPool.submit<Clip?> { synthesize(u, mySession) }
    }

    @Volatile private var lastFatal = false

    private fun synthesize(u: SpeechPlan.Utterance, mySession: Int): Clip? {
        val b = book ?: return null
        val s = fish ?: return null
        val words = b.words.subList(u.startWord, u.endWord).map { it.text }
        val raw = speakable(words, u.quoteId >= 0)
        val notes = raw.filter { it in "♩♪♫♬" }
        val text = sanitizeForSpeech(raw)
        if (text.isBlank() && notes.isEmpty()) return null
        awaitCast(u, mySession)
        if (mySession != session) return null
        val c = cast
        val d = director
        val pick = if (c != null && d != null) d.pick(c, u.quoteId, u.startWord) else CastDirector.Pick(voiceId, "", Cast.NARRATOR)
        if (notes.isNotEmpty() && text.none { it.isLetterOrDigit() }) {
            // Speech written as musical notes (Rocky in Project Hail Mary) plays as notes.
            val pcm = SpeechAlign.Pcm(SpeechAlign.tones(notes), 24000)
            val f = cacheFile(notes, "tones", null)
            if (!f.isFile) f.writeBytes(SpeechAlign.wav(pcm.samples, pcm.rate))
            return Clip(f, SpeechAlign.align(words, pcm), pick.speaker)
        }
        val style = pick.style.ifBlank { null }?.takeIf { u.quoteId >= 0 || it.contains("accent") }
        var voice = pick.voice.ifBlank { voiceId }
        var file = cacheFile(text, voice, style)
        return try {
            if (!(file.isFile && file.length() > 44)) {
                val wav = try { s.synthesize(text, voice, style) } catch (e: FishSpeech.ApiException) {
                    // A character's library voice has gone (deleted/private): read
                    // their lines in the narrator's voice from now on.
                    if (!e.voiceProblem || c == null || pick.role == null || voice == narratorVoice()) throw e
                    Log.w(TAG, "voice $voice failed (${e.code}) for ${pick.speaker}; using the narrator's")
                    synchronized(c) { pick.role.voice = narratorVoice(); pick.role.voiceSource = "narrator-fallback" }
                    voice = narratorVoice(); file = cacheFile(text, voice, style)
                    s.synthesize(text, voice, style)
                }
                val pcm = SpeechAlign.parseWav(wav) ?: return null
                // fish pads each clip with silence; keep only a natural pause
                // (longer after a sentence than mid-line) so utterances flow.
                val last = words.last()
                val tail = when {
                    endsSentence(last) -> 300
                    SpeechAlign.pauseAfter(last) > 0f -> 160
                    else -> 60
                }
                val tmp = File(file.parentFile, file.name + ".part")
                tmp.writeBytes(SpeechAlign.trimmedWav(pcm, leadMs = 40, tailMs = tail))
                tmp.renameTo(file)
                trimCache()
            } else file.setLastModified(System.currentTimeMillis())
            val pcm = SpeechAlign.parseWav(file.readBytes()) ?: return null
            Clip(file, SpeechAlign.align(words, pcm), pick.speaker)
        } catch (e: FishSpeech.ApiException) {
            Log.w(TAG, "synth failed: ${e.message} — \"${text.take(120)}\"")
            val msg = when (e.code) {
                401, 403 -> { lastFatal = true; "Invalid fish.audio API key" }
                402 -> { lastFatal = true; "fish.audio credit exhausted" }
                429 -> { lastFatal = true; "fish.audio rate limit — try again shortly" }
                0 -> if ("host" in (e.message ?: "") || "resolve" in (e.message ?: "")) { lastFatal = true; "No internet connection" } else "fish.audio: ${e.message?.take(80)}"
                else -> "fish.audio: ${e.message?.take(80)}"
            }
            if (mySession == session) main.post { onError?.invoke(msg) }
            null
        } catch (e: Exception) {
            Log.w(TAG, "synth failed: ${e.message}")
            null
        }
    }

    // ---- Playback: each clip's player is prepared while the previous one is
    // ---- still speaking and chained with setNextMediaPlayer, so the handoff
    // ---- between utterances is gapless (a fresh prepare cost ~0.5 s on the X3).

    private var next: Pair<Int, MediaPlayer>? = null
    private var nextClip: Clip? = null

    private fun newPlayer(clip: Clip): MediaPlayer = MediaPlayer().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        setDataSource(clip.file.absolutePath)
    }

    private fun play(clip: Clip, u: SpeechPlan.Utterance, mySession: Int, prepared: MediaPlayer? = null) {
        if (!active || mySession != session) { prepared?.let { runCatching { it.release() } }; return }
        val index = at
        runCatching {
            val mp = prepared ?: newPlayer(clip).also { it.prepare() }
            player = mp
            mp.setOnCompletionListener { clipDone(mp, index, u, mySession) }
            mp.setOnErrorListener { _, w, e ->
                Log.w(TAG, "tts play err $w/$e")
                if (player === mp) player = null
                if (active && mySession == session && at == index) { dropNext(); at++; speakCurrent() }
                true
            }
            // A chained player was already started by the framework.
            if (!mp.isPlaying && !paused) mp.start()
            val now = System.currentTimeMillis()
            Log.i(TAG, "clip $index words ${u.startWord}-${u.endWord} q=${u.quoteId} speaker=${clip.speaker} dur=${mp.duration}ms " +
                "handoff=${if (lastClipEnd > 0) now - lastClipEnd else -1}ms chained=${prepared != null} " +
                "text=\"${book?.words?.subList(u.startWord, u.endWord)?.joinToString(" ") { it.text }?.take(80)}\"")
            onSpeaker?.invoke(clip.speaker)
            pollClip = clip; pollUtt = u
            main.removeCallbacks(poll)
            // If the user paused while this utterance was still synthesizing, honor it.
            if (paused) runCatching { mp.pause() } else main.post(poll)
            armNext(index, mySession)
        }.onFailure {
            Log.w(TAG, "play failed: ${it.message}")
            clip.file.delete()
            if (active) { at++; speakCurrent() }
        }
    }

    /** Prepares the next clip's player and chains it behind the one now playing. */
    private fun armNext(index: Int, mySession: Int) {
        val k = index + 1
        if (k >= plan.size) return
        val future = request(k, mySession)
        prefetch(index, mySession)
        Thread({
            val clip = runCatching { future.get() }.getOrNull()
            main.post {
                val cur = player
                if (clip == null || cur == null || !active || mySession != session || at != index) return@post
                runCatching {
                    val nx = newPlayer(clip)
                    nx.setOnPreparedListener {
                        if (active && mySession == session && at == index && player === cur && next == null) {
                            runCatching { cur.setNextMediaPlayer(nx) }
                                .onSuccess { next = k to nx; nextClip = clip }
                                .onFailure { runCatching { nx.release() } }
                        } else runCatching { nx.release() }
                    }
                    nx.prepareAsync()
                }
            }
        }, "tts-arm").apply { isDaemon = true }.start()
    }

    private fun clipDone(mp: MediaPlayer, index: Int, u: SpeechPlan.Utterance, mySession: Int) {
        lastClipEnd = System.currentTimeMillis()
        main.removeCallbacks(poll)
        runCatching { mp.release() }; if (player === mp) player = null
        if (!active || mySession != session || at != index) return
        onWord?.invoke(u.endWord.coerceAtMost((book?.wordCount ?: 1) - 1))
        val chained = next?.takeIf { it.first == index + 1 }
        val clip = nextClip
        next = null; nextClip = null
        at = index + 1
        if (chained != null && clip != null && at < plan.size) {
            pending.remove(at)
            val nu = plan[at]
            readingAt = nu.startWord
            synchronized(castLock) { castLock.notifyAll() }
            play(clip, nu, mySession, prepared = chained.second)
        } else {
            chained?.second?.let { runCatching { it.release() } }
            speakCurrent()
        }
    }

    private fun dropNext() {
        next?.second?.let { runCatching { it.release() } }
        next = null; nextClip = null
    }

    // ---- Position -> word (the highlight) -----------------------------------

    private var lastClipEnd = 0L
    private var pollClip: Clip? = null
    private var pollUtt: SpeechPlan.Utterance? = null
    private var lastWord = -1
    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            val mp = player ?: return
            val clip = pollClip ?: return
            val u = pollUtt ?: return
            val pos = runCatching { mp.currentPosition }.getOrDefault(0)
            val w = u.startWord + clip.timing.wordAt(pos)
            if (w != lastWord) { lastWord = w; onWord?.invoke(w) }
            main.postDelayed(this, 30L)
        }
    }

    // ---- One-off speech (summaries, cues) ------------------------------------

    private fun narratorVoice(): String = cast?.narrator?.voice?.ifBlank { null } ?: voiceId

    /**
     * Speak a one-off utterance (e.g. an AI section summary) in the narrator's
     * voice on its own player, without disturbing book narration.
     */
    fun speakOnce(text: String, onStart: () -> Unit = {}, onDone: () -> Unit = {}) {
        val s = fish ?: run { onError?.invoke("Add your fish.audio API key in Settings"); onDone(); return }
        stopOneOff()
        val speakable = sanitizeForSpeech(text).take(4000)
        if (speakable.isBlank()) { onDone(); return }
        val voice = narratorVoice()
        Thread {
            val file = runCatching {
                File.createTempFile("once_", ".wav", context.cacheDir).also {
                    it.writeBytes(s.synthesize(speakable, voice))
                }
            }.onFailure { e -> main.post { onError?.invoke("fish.audio: ${e.message?.take(80)}") } }.getOrNull()
            main.post {
                if (file == null) { onDone(); return@post }
                playOneOff(file, deleteAfter = true, onStart = onStart, onDone = onDone)
            }
        }.start()
    }

    /**
     * Speak a short UI cue ("Starting narration") with minimum latency: cached,
     * so after the first use it plays instantly. Skipped if book narration has
     * already begun — by then the cue would talk over the text.
     */
    fun speakCue(text: String) {
        val s = fish ?: return
        val voice = narratorVoice()
        val dir = File(context.filesDir, "cues").apply { mkdirs() }
        val cache = File(dir, "${voice}_${hash(text).take(16)}.wav")
        Thread {
            if (!(cache.isFile && cache.length() > 44)) {
                val ok = runCatching { cache.writeBytes(s.synthesize(sanitizeForSpeech(text), voice)) }.isSuccess
                if (!ok) { cache.delete(); return@Thread }
            }
            main.post {
                if (player?.isPlaying == true) return@post
                stopOneOff()
                playOneOff(cache, deleteAfter = false)
            }
        }.start()
    }

    private fun playOneOff(file: File, deleteAfter: Boolean, onStart: () -> Unit = {}, onDone: () -> Unit = {}) {
        runCatching {
            val mp = MediaPlayer()
            oneOff = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            mp.setDataSource(file.absolutePath)
            val finish = { runCatching { mp.release() }; if (oneOff === mp) oneOff = null; if (deleteAfter) file.delete(); onDone() }
            mp.setOnCompletionListener { finish() }
            mp.setOnErrorListener { _, _, _ -> finish(); true }
            mp.prepare(); mp.start(); onStart()
        }.onFailure { if (deleteAfter) file.delete(); onDone() }
    }

    val isSpeakingOnce: Boolean get() = oneOff?.isPlaying == true

    fun stopOneOff() {
        runCatching { oneOff?.stop() }; runCatching { oneOff?.release() }; oneOff = null
    }

    private fun stopInternal() {
        session++          // invalidate every in-flight synthesis/playback callback
        active = false
        paused = false
        lastFatal = false
        stopOneOff()
        main.removeCallbacks(poll)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        dropNext()
        pending.values.forEach { it.cancel(false) }
        pending.clear()
        lastWord = -1
        lastClipEnd = 0L
        synchronized(castLock) { castLock.notifyAll() }
    }

    // ---- Audio cache ----------------------------------------------------------
    // Clips are keyed by everything that shapes the audio, so re-reading a
    // passage, scrubbing back, or resuming tomorrow costs nothing.

    private val cacheDir by lazy {
        File(context.cacheDir, "fish_clips").apply { mkdirs() }
    }

    private fun cacheFile(text: String, voice: String, style: String?): File =
        File(cacheDir, hash("${FishSpeech.MODEL}|$voice|${style.orEmpty()}|$text") + ".wav")

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun trimCache() {
        val files = cacheDir.listFiles()?.filter { it.name.endsWith(".wav") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= CACHE_LIMIT_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= CACHE_LIMIT_BYTES * 3 / 4) break
            total -= f.length(); f.delete()
        }
    }
}

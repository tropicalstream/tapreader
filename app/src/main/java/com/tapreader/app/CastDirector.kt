package com.tapreader.app

import java.io.File
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** One voice in a book's cast. [voice] is a fish.audio reference id. */
class Role(
    val name: String,
    var gender: String,
    var age: String,
    var accent: String,
    var sketch: String,
    var major: Boolean,
    var voice: String = "",
    /** "library" (chosen from fish.audio) | "narrator" (shares the narrator's voice) | "user" | "selected" (your own narrator voice) */
    var voiceSource: String = "library",
    var lines: Int = 0,
    var designTried: Boolean = false,
    val aliases: MutableSet<String> = linkedSetOf(),
    /** Phrases to find this voice in the fish.audio library ("old british man"). */
    var searches: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("gender", gender).put("age", age).put("accent", accent)
        .put("sketch", sketch).put("major", major).put("voice", voice).put("voiceSource", voiceSource)
        .put("lines", lines).put("designTried", designTried).put("aliases", JSONArray(aliases.toList()))
        .put("searches", JSONArray(searches))

    companion object {
        fun fromJson(o: JSONObject) = Role(
            o.optString("name"), o.optString("gender"), o.optString("age"), o.optString("accent"),
            o.optString("sketch"), o.optBoolean("major"), o.optString("voice"),
            o.optString("voiceSource", "library"), o.optInt("lines"), o.optBoolean("designTried"),
            o.optJSONArray("aliases")?.let { a -> (0 until a.length()).map { a.optString(it) }.toMutableSet() } ?: linkedSetOf(),
            o.optJSONArray("searches")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        )
    }
}

/**
 * A book's cast and its line-by-line attributions, persisted per book so a
 * book is only ever cast once and keeps the same voices across sessions.
 */
class Cast(val bookId: String) {
    var title = ""
    /** Fingerprint of the parsed text; attributions are keyed by word index. */
    var textSig = ""
    var language = "en-US"
    var person = "third"
    var narratorStyle = "warm, measured audiobook narration"
    var narrator = Role(NARRATOR, "unknown", "adult", "", "", true)
    val roles = LinkedHashMap<String, Role>()
    val quoteSpeaker = HashMap<Int, String>()
    val quoteStyle = HashMap<Int, String>()
    /** Paragraph-start word index → who narrates from there (first-person / epistolary books). */
    val narrationBy = TreeMap<Int, String>()
    /** Words [0, analyzedTo) inside the cast windows have been attributed. */
    val windows = TreeMap<Int, Int>()      // start → end (exclusive)
    var ready = false                        // narrator profile done

    fun isCast(word: Int): Boolean = windows.floorEntry(word)?.let { word < it.value } == true

    /** First word at or after [from] not covered by a cast window. */
    fun castFrontier(from: Int): Int {
        var w = from
        while (true) { val e = windows.floorEntry(w) ?: return w; if (w < e.value) w = e.value else return w }
    }

    fun role(name: String): Role? {
        if (name.equals(NARRATOR, true)) return narrator
        roles[name]?.let { return it }
        val lower = name.lowercase().trim()
        return roles.values.firstOrNull { r -> r.name.lowercase() == lower || r.aliases.any { it.lowercase() == lower } }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("bookId", bookId).put("title", title).put("textSig", textSig).put("language", language).put("person", person)
        .put("narratorStyle", narratorStyle).put("narrator", narrator.toJson()).put("ready", ready)
        .put("roles", JSONArray().also { a -> roles.values.forEach { a.put(it.toJson()) } })
        .put("quotes", JSONObject().also { o -> quoteSpeaker.forEach { (k, v) -> o.put(k.toString(), JSONArray().put(v).put(quoteStyle[k] ?: "")) } })
        .put("narrationBy", JSONObject().also { o -> narrationBy.forEach { (k, v) -> o.put(k.toString(), v) } })
        .put("windows", JSONObject().also { o -> windows.forEach { (k, v) -> o.put(k.toString(), v) } })

    companion object {
        const val NARRATOR = "NARRATOR"
        const val UNKNOWN = "UNKNOWN"

        fun fromJson(o: JSONObject): Cast = Cast(o.optString("bookId")).apply {
            title = o.optString("title"); textSig = o.optString("textSig"); language = o.optString("language", "en-US")
            person = o.optString("person", "third"); narratorStyle = o.optString("narratorStyle", narratorStyle)
            ready = o.optBoolean("ready")
            o.optJSONObject("narrator")?.let { narrator = Role.fromJson(it) }
            o.optJSONArray("roles")?.let { a -> for (i in 0 until a.length()) Role.fromJson(a.getJSONObject(i)).let { roles[it.name] = it } }
            o.optJSONObject("quotes")?.let { q -> q.keys().forEach { k -> q.optJSONArray(k)?.let { quoteSpeaker[k.toInt()] = it.optString(0); quoteStyle[k.toInt()] = it.optString(1) } } }
            o.optJSONObject("narrationBy")?.let { q -> q.keys().forEach { k -> narrationBy[k.toInt()] = q.optString(k) } }
            o.optJSONObject("windows")?.let { q -> q.keys().forEach { k -> windows[k.toInt()] = q.optInt(k) } }
        }
    }
}

/**
 * Casts a book for multi-voice fish.audio narration:
 *
 *  1. Narrator profile — Gemini reads the title, author and opening pages: the
 *     book's language/accent, first- or third-person narration, and what the
 *     narrator should sound like.
 *  2. Windows of ~1,500 words, cast just ahead of the reader: every quoted line
 *     is tagged, and Gemini names its speaker (dialogue tags, turn-taking,
 *     forms of address), adds a short delivery direction, describes any new
 *     character's voice, and reports narrator changes (diaries, letters).
 *  3. Voices — each character's description becomes a few searches of the
 *     fish.audio voice library; Gemini then picks, for everyone at once, the
 *     best-fitting distinct voice from what came back.
 *
 * Only text the reader is about to hear is ever sent — never the whole book.
 */
class CastDirector(
    private val llm: GeminiText,
    private val fish: FishSpeech,
    private val dir: File,
    /** The reader's own chosen voice: the narrator when [narratorIsMine], and the last resort. */
    var selectedVoice: String,
    var narratorIsMine: Boolean = false,
    private val log: (String) -> Unit = {}
) {
    companion object {
        const val WINDOW_WORDS = 1500
        /** The first window is small so the first voice is heard quickly. */
        const val FIRST_WINDOW_WORDS = 450

        /** Deletes a purged book's cast. */
        fun forgetInBackground(dir: File, bookId: String) {
            File(dir, "cast_${bookId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json").delete()
        }
    }

    init { dir.mkdirs() }

    private fun file(bookId: String) = File(dir, "cast_${bookId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json")

    fun load(book: Book): Cast {
        val f = file(book.id)
        val cast = if (f.isFile) runCatching { Cast.fromJson(JSONObject(f.readText())) }.getOrNull() else null
        val sig = signature(book)
        if (cast != null && cast.textSig != sig) {
            // The book parses differently now (a parser fix, a re-sent edition):
            // line attributions point at the wrong words, but the voices chosen
            // for its people are still right — keep those, re-attribute lines.
            val firstNarrator = cast.narrationBy[0]      // a first-person narrator, set by the profile
            cast.quoteSpeaker.clear(); cast.quoteStyle.clear(); cast.narrationBy.clear(); cast.windows.clear()
            firstNarrator?.let { cast.narrationBy[0] = it }
            cast.roles.values.forEach { it.lines = 0 }
            if (cast.textSig.isNotEmpty()) log("text changed for ${book.title}: re-attributing lines")
        }
        return (cast ?: Cast(book.id)).also { it.title = book.title; it.textSig = sig }
    }

    private fun signature(book: Book): String {
        var h = book.wordCount.toLong()
        for (w in book.words.take(4000)) h = h * 31 + w.text.hashCode()
        return java.lang.Long.toHexString(h)
    }

    fun save(cast: Cast) = synchronized(cast) {
        val f = file(cast.bookId)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(cast.toJson().toString())
        tmp.renameTo(f)
    }

    fun forget(bookId: String) = file(bookId).delete()

    // ---- Narrator ------------------------------------------------------------

    /** One caster at a time per book (pre-casting on open vs. narration's own caster). */
    private val locks = ConcurrentHashMap<String, Any>()
    private fun lockFor(cast: Cast) = locks.getOrPut(cast.bookId) { Any() }

    fun ensureNarrator(book: Book, cast: Cast): Unit = synchronized(lockFor(cast)) {
        if (cast.ready) return
        val opening = book.words.take(1400).joinToString(" ") { it.text }
        val prompt = """
            You are the casting director for an audiobook of "${book.title}"${if (book.author.isNotBlank()) " by ${book.author}" else ""}.
            Use your knowledge of the book if you recognise it, and the opening below.

            Return JSON:
            {"language_code": "BCP-47 code of the text's language, with the region that best fits the book's setting/voice, e.g. en-GB, en-US",
             "person": "first" | "third" | "epistolary",
             "narrator_name": "the first-person narrator's name if the opening establishes one, else null",
             "gender": "male" | "female",
             "age": "young adult" | "adult" | "middle-aged" | "elderly",
             "accent": "the narrator's accent, e.g. 'refined southern English', 'Missouri rural American'",
             "voice_prompt": "1-2 sentences describing the ideal narrator's VOICE for this book: age, gender, timbre, accent, pace and manner. Describe sound only; keep it neutral and never mention violence, fear, menace or other dark traits.",
             "style": "a delivery direction for the narration, under 10 words",
             "voice_search": ["3-4 short phrases (1-3 words each) likely to appear in the NAMES of suitable voices in a community voice library, e.g. 'narrator', 'british male', 'audiobook', 'deep storyteller'"]}

            For first-person or epistolary books, the narrator voice IS the (first) narrating character's voice.

            OPENING:
            $opening
        """.trimIndent()
        val js = runCatching { llm.generateJson(prompt, fast = true) }.onFailure { log("narrator profile failed: ${it.message}") }.getOrNull()
        synchronized(cast) {
            if (js != null) {
                cast.language = js.optString("language_code").ifBlank { "en-US" }
                cast.person = js.optString("person", "third")
                cast.narratorStyle = js.optString("style").ifBlank { cast.narratorStyle }
                cast.narrator.gender = js.optString("gender", "unknown")
                cast.narrator.age = js.optString("age", "adult")
                cast.narrator.accent = js.optString("accent")
                cast.narrator.sketch = js.optString("voice_prompt")
                cast.narrator.searches = strings(js, "voice_search")
                val who = js.optString("narrator_name").takeIf { it.isNotBlank() && it != "null" }
                if (who != null && cast.person != "third") {
                    val r = cast.role(who) ?: Role(who, cast.narrator.gender, cast.narrator.age, cast.narrator.accent, cast.narrator.sketch, true)
                        .also { cast.roles[it.name] = it }
                    r.voiceSource = "narrator"
                    cast.narrationBy[0] = r.name
                }
            }
            cast.ready = true
        }
        assignVoices(cast)
        save(cast)
    }

    private fun strings(o: JSONObject, name: String): List<String> =
        o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotBlank() } } ?: emptyList()

    // ---- Windows ------------------------------------------------------------

    /** Casts forward from [from] until at least [through] is covered. Blocking. */
    fun castThrough(book: Book, cast: Cast, dialogue: Dialogue.Map, from: Int, through: Int) = synchronized(lockFor(cast)) {
        var start = synchronized(cast) { cast.castFrontier(from) }
        while (start < book.wordCount && start <= through) {
            val first = synchronized(cast) { cast.windows.isEmpty() || !cast.isCast((start - 1).coerceAtLeast(0)) }
            val end = windowEnd(book, start, if (first) FIRST_WINDOW_WORDS else WINDOW_WORDS)
            castWindow(book, cast, dialogue, start, end, fast = first)
            start = synchronized(cast) { cast.castFrontier(end) }
        }
    }

    private fun windowEnd(book: Book, start: Int, size: Int): Int {
        var e = (start + size).coerceAtMost(book.wordCount)
        val cap = (start + size * 2).coerceAtMost(book.wordCount)
        while (e < cap && !book.words[e].paragraphBreak) e++
        return e
    }

    /** [fast] trades a little care for ~3× speed (low thinking) — used where the reader is waiting. */
    fun castWindow(book: Book, cast: Cast, dialogue: Dialogue.Map, start: Int, end: Int, fast: Boolean = false) {
        val spansHere = dialogue.spans.filter { it.startWord in start until end }
        val text = render(book, dialogue, start, end)
        val known = synchronized(cast) {
            cast.roles.values.sortedByDescending { it.lines }.take(60).joinToString("\n") { r ->
                "- ${r.name} (${r.gender}${if (r.aliases.isNotEmpty()) "; also called " + r.aliases.joinToString(", ") else ""})"
            }.ifBlank { "(none yet)" }
        }
        val context = book.words.subList((start - 160).coerceAtLeast(0), start).joinToString(" ") { it.text }
        val firstPerson = cast.person != "third"
        val prompt = """
            You are casting a multi-voice audiobook of "${book.title}"${if (book.author.isNotBlank()) " by ${book.author}" else ""}.
            In the PASSAGE, each paragraph starts with a tag like [P1234]. Each quoted span has a tag like ⟨Q57⟩ right before its opening quotation mark.

            KNOWN CHARACTERS:
            $known

            Tasks:
            1. For EVERY ⟨Q⟩ tag (${spansHere.size} of them), give the speaker: a known character's exact name, or a new character you add below. Use dialogue tags ("said Elizabeth"), turn-taking in conversations, forms of address, and who is present. If the quoted text is a letter, diary or document, give its writer. If it is not a character's words at all (a title, a sign, a single quoted word or phrase), use "NARRATOR". Use "UNKNOWN" only if it truly cannot be determined.
            2. For each line add "style": a short delivery direction drawn from the text (e.g. "whispering, anxious", "teasing", "booming"), under 8 words, or "" if neutral.
            3. "new_characters": every speaker not in KNOWN CHARACTERS, with name (the most common form), aliases (other names/titles used for them), gender, age (child/teen/young adult/adult/middle-aged/elderly), accent (region and class implied by the book), voice_prompt (1-2 sentences describing only the SOUND of their voice for a voice actor: age, gender, timbre, accent, pace; neutral wording, never violence, menace or other dark traits), major (true if they seem to be a principal character), and voice_search (3-4 short phrases of 1-3 words likely to appear in the NAMES of fitting voices in a community voice library, e.g. "old man", "british woman", "robot", "southern drawl").
            4. "alias_updates": known characters who appear under a new name or title here.
            5. "narration": ${if (firstPerson) "who narrates each part — list {\"paragraph\": N, \"narrator\": name} whenever the narrating character changes (a new diary, journal or letter writer); use \"NARRATOR\" for a neutral editor/third-person voice." else "[] (third-person book)."}

            Return JSON only:
            {"quotes":[{"q":57,"speaker":"...","style":"..."}],
             "new_characters":[{"name":"","aliases":[],"gender":"male|female","age":"","accent":"","voice_prompt":"","major":false,"voice_search":[]}],
             "alias_updates":[{"name":"","aliases":[]}],
             "narration":[{"paragraph":1234,"narrator":""}]}

            CONTEXT (just before the passage, already cast):
            …$context

            PASSAGE:
            $text
        """.trimIndent()

        val js = try { llm.generateJson(prompt, fast = fast) } catch (e: Exception) {
            log("cast window $start-$end failed: ${e.message}")
            // Leave the window uncast-but-covered so narration proceeds with the
            // narrator voice rather than stalling on a broken window.
            synchronized(cast) { cast.windows[start] = end }
            return
        }
        merge(book, cast, js, spansHere)
        assignVoices(cast)      // voices are chosen before the window counts as cast
        synchronized(cast) { cast.windows[start] = end }
        save(cast)
    }

    private fun render(book: Book, dialogue: Dialogue.Map, start: Int, end: Int): String {
        val sb = StringBuilder()
        for (i in start until end) {
            val w = book.words[i]
            if (i == start || w.paragraphBreak) { if (sb.isNotEmpty()) sb.append("\n\n"); sb.append("[P").append(i).append("] ") }
            else sb.append(' ')
            dialogue.spans.getOrNull(dialogue.quoteOf[i])?.takeIf { it.startWord == i }?.let { sb.append("⟨Q").append(it.id).append("⟩") }
            sb.append(w.text)
        }
        return sb.toString()
    }

    private fun merge(book: Book, cast: Cast, js: JSONObject, spans: List<Dialogue.Span>) = synchronized(cast) {
        js.optJSONArray("new_characters")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                if (name.isBlank() || name.equals(Cast.NARRATOR, true) || name.equals(Cast.UNKNOWN, true)) continue
                val existing = cast.role(name)
                val aliases = o.optJSONArray("aliases")?.let { x -> (0 until x.length()).map { x.optString(it).trim() }.filter { it.isNotBlank() } } ?: emptyList()
                if (existing != null) { existing.aliases += aliases; continue }
                cast.roles[name] = Role(name, o.optString("gender", "unknown").lowercase(), o.optString("age", "adult"),
                    o.optString("accent"), o.optString("voice_prompt"), o.optBoolean("major"), aliases = aliases.toMutableSet(),
                    searches = strings(o, "voice_search"))
            }
        }
        js.optJSONArray("alias_updates")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val r = cast.role(o.optString("name")) ?: continue
                o.optJSONArray("aliases")?.let { x -> for (k in 0 until x.length()) x.optString(k).trim().takeIf { it.isNotBlank() && it != r.name }?.let { r.aliases += it } }
            }
        }
        val ids = spans.map { it.id }.toSet()
        js.optJSONArray("quotes")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val q = o.optInt("q", -1).takeIf { it in ids } ?: continue
                val said = o.optString("speaker").trim()
                val speaker = when {
                    said.isBlank() || said.equals(Cast.UNKNOWN, true) -> Cast.UNKNOWN
                    said.equals(Cast.NARRATOR, true) -> Cast.NARRATOR
                    else -> cast.role(said)?.name ?: Role(said, "unknown", "adult", "", "", false).also { cast.roles[said] = it }.name
                }
                cast.quoteSpeaker[q] = speaker
                cast.quoteStyle[q] = o.optString("style").take(80)
                if (speaker != Cast.NARRATOR && speaker != Cast.UNKNOWN) cast.role(speaker)?.let { it.lines++ }
            }
        }
        // Lines the model skipped: borrow the speaker of a sibling span in the
        // same paragraph ("…,” said he, “…”), else leave them to the narrator.
        for (s in spans) {
            if (cast.quoteSpeaker[s.id].let { it != null && it != Cast.UNKNOWN }) continue
            val sib = spans.firstOrNull { o -> o.id != s.id && sameParagraph(book, o, s) && cast.quoteSpeaker[o.id].let { it != null && it != Cast.UNKNOWN && it != Cast.NARRATOR } }
            cast.quoteSpeaker[s.id] = sib?.let { cast.quoteSpeaker[it.id] } ?: Cast.UNKNOWN
        }
        // One speech, one voice: a speech carried on into the next paragraph
        // keeps its speaker (an earlier window may have cast its start).
        for (sp in spans) {
            if (sp.continues < 0) continue
            val prev = cast.quoteSpeaker[sp.continues] ?: continue
            if (prev != Cast.UNKNOWN && prev != Cast.NARRATOR) cast.quoteSpeaker[sp.id] = prev
        }
        js.optJSONArray("narration")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val p = o.optInt("paragraph", -1).takeIf { it >= 0 } ?: continue
                val who = o.optString("narrator").trim()
                cast.narrationBy[p] = if (who.equals(Cast.NARRATOR, true) || who.isBlank()) Cast.NARRATOR
                    else cast.role(who)?.name ?: Role(who, "unknown", "adult", "", "", true).also { cast.roles[who] = it }.name
            }
        }
    }

    /** Spans never cross paragraphs, so two share one iff no break lies between their starts. */
    private fun sameParagraph(book: Book, a: Dialogue.Span, b: Dialogue.Span): Boolean {
        val lo = minOf(a.startWord, b.startWord); val hi = maxOf(a.startWord, b.startWord)
        return (lo + 1..hi).none { book.words[it].paragraphBreak }
    }

    // ---- Voices (fish.audio library) --------------------------------------------

    private val searchPool = Executors.newFixedThreadPool(4) { r -> Thread(r, "fish-search").apply { isDaemon = true } }

    /** Chooses fish.audio voices for the narrator and every character still without one. */
    fun assignVoices(cast: Cast) {
        val need = synchronized(cast) {
            val list = ArrayList<Role>()
            if (cast.narrator.voice.isBlank()) {
                if (narratorIsMine && selectedVoice.isNotBlank()) { cast.narrator.voice = selectedVoice; cast.narrator.voiceSource = "selected" }
                else list += cast.narrator
            }
            for (r in cast.roles.values) {
                if (r.voiceSource == "narrator") continue
                if (r.voice.isBlank()) list += r
            }
            list
        }
        if (need.isNotEmpty()) runCatching { castFromLibrary(cast, need) }.onFailure { log("voice casting failed: ${it.message}") }
        synchronized(cast) {
            // Anyone still without a voice (search or model failed) reads in the narrator's
            // voice — never silence — and first-person narrators share it.
            if (cast.narrator.voice.isBlank()) { cast.narrator.voice = selectedVoice; cast.narrator.voiceSource = "selected" }
            for (r in cast.roles.values) {
                if (r.voiceSource == "narrator") r.voice = cast.narrator.voice
                else if (r.voice.isBlank()) r.voice = cast.narrator.voice
            }
        }
    }

    private fun genderTag(r: Role) = when (r.gender) { "male" -> "male"; "female" -> "female"; else -> null }

    /** fish.audio age tags: young / middle-aged / old. */
    private fun ageTag(r: Role): String? {
        val a = r.age.lowercase()
        return when {
            "child" in a || "teen" in a || "young" in a -> "young"
            "middle" in a -> "middle-aged"
            "elder" in a || "old" in a || "senior" in a -> "old"
            "adult" in a -> "middle-aged"
            else -> null
        }
    }

    /** Relevance searches: Gemini's own phrases, plus accent + gender (+ "narrator"). */
    /** Each candidate speaks a line; Gemini listens and names the best fit for [r]. */
    private fun audition(cast: Cast, r: Role, voices: List<FishSpeech.Voice>): FishSpeech.Voice? {
        val line = if (r === cast.narrator) "It was later than anyone had expected, and the house was very quiet. Nobody knew yet what the morning would bring."
            else "Well, I suppose that settles it, then. Shall we go and see for ourselves?"
        val clips = voices.mapIndexed { i, v -> "ABC"[i].toString() to searchPool.submit<ByteArray?> { runCatching { fish.synthesize(line, v.id) }.getOrNull() } }
            .mapNotNull { (label, f) -> runCatching { f.get(60, TimeUnit.SECONDS) }.getOrNull()?.let { label to it } }
        if (clips.size < 2) return null
        val want = listOf(r.gender, r.age, r.accent).filter { it.isNotBlank() }.joinToString(", ")
        val js = llm.generateJsonWithAudio(
            """You are casting the ${if (r === cast.narrator) "narrator" else "character ${r.name}"} for an audiobook of "${cast.title}".
               Wanted: $want. ${r.sketch}
               Listen to each voice below. Judge gender, apparent age and accent from the sound, then pick the best fit (a clearly wrong gender or accent rules a voice out).
               Return JSON: {"best":"A|B|C","why":"one short sentence"}""".trimIndent(), clips)
        val best = js.optString("best").trim().take(1)
        val idx = "ABC".indexOf(best)
        return voices.getOrNull(idx)?.takeIf { clips.any { it.first == best } }?.also { log("audition ${r.name}: ${it.title} — ${js.optString("why").take(120)}") }
    }

    private fun queries(r: Role, isNarrator: Boolean): List<String> {
        val out = LinkedHashSet<String>()
        r.searches.take(4).forEach { out += it.lowercase() }
        val g = genderTag(r).orEmpty()
        val accentWord = r.accent.lowercase().split(Regex("[^a-z]+"))
            .firstOrNull { it in setOf("british", "english", "american", "scottish", "irish", "australian", "southern", "russian",
                "german", "french", "italian", "spanish", "indian", "texan", "cockney", "welsh", "canadian", "new york") }
        if (isNarrator) out += listOfNotNull(accentWord, g, "narrator").joinToString(" ")
        else if (accentWord != null) out += "$accentWord $g".trim()
        return out.take(5)
    }

    /**
     * Searches the library for each role's phrases, then one Gemini call assigns
     * everyone a distinct voice from their own candidates (voices already in the
     * cast are excluded, so characters stay distinguishable).
     */
    private fun castFromLibrary(cast: Cast, roles: List<Role>) {
        val taken = synchronized(cast) { (cast.roles.values.map { it.voice } + cast.narrator.voice).filter { it.isNotBlank() }.toSet() }
        val lang = cast.language.substringBefore('-').lowercase().ifBlank { "en" }
        val candidates = roles.associateWith { r ->
            val isNarrator = r === cast.narrator
            val searches = queries(r, isNarrator).map { q ->
                searchPool.submit<List<FishSpeech.Voice>> { runCatching { fish.search(title = q, language = lang) }.getOrElse { emptyList() } }
            }.toMutableList()
            // Plus the most-used voices of the right gender and age (reliable quality) —
            // only when the gender is known; otherwise that pool is just "popular".
            val g = genderTag(r)
            if (g != null) searches += searchPool.submit<List<FishSpeech.Voice>> {
                runCatching { fish.search(tags = listOfNotNull(g, ageTag(r), if (isNarrator) "narration" else null), language = lang, pageSize = 8) }.getOrElse { emptyList() }
            }
            searches
        }.mapValues { (r, fs) ->
            val g = genderTag(r)
            val lists = fs.map { f ->
                runCatching { f.get(40, TimeUnit.SECONDS) }.getOrElse { emptyList() }
                    .filter { it.id !in taken && (g == null || g in it.tags || it.tags.none { t -> t == "male" || t == "female" }) }
            }
            // Round-robin in each search's own relevance order: a search for "robot"
            // should contribute its robots, not be outranked by popular narrators.
            val merged = LinkedHashMap<String, FishSpeech.Voice>()
            for (rank in 0 until 5) for (l in lists) l.getOrNull(rank)?.let { merged.putIfAbsent(it.id, it) }
            merged.values.take(14)
        }
        if (candidates.values.all { it.isEmpty() }) return
        val ids = HashMap<String, FishSpeech.Voice>()
        val owner = HashMap<String, Role>()
        val listing = StringBuilder()
        for ((r, cs) in candidates) {
            listing.append("\n## ").append(if (r === cast.narrator) "NARRATOR" else r.name)
                .append(" — ").append(listOf(r.gender, r.age, r.accent).filter { it.isNotBlank() }.joinToString(", "))
                .append(". ").append(r.sketch.take(220)).append('\n')
            for (v in cs) {
                val key = "v" + (ids.size + 1)
                ids[key] = v; owner[key] = r
                listing.append("- ").append(key).append(": \"").append(v.title.take(60)).append("\"")
                if (v.tags.isNotEmpty()) listing.append(" [").append(v.tags.take(6).joinToString(", ")).append("]")
                if (v.description.isNotBlank()) listing.append(" — ").append(v.description.replace('\n', ' ').take(120))
                listing.append(" (").append(v.uses).append(" uses)\n")
            }
        }
        val prompt = """
            You are casting voices for an audiobook of "${cast.title}" from a community voice library. For each role below, choose the ONE candidate voice (by its v-number) whose name, tags and description best fit the role's gender, age, accent and manner. Prefer voices that are plainly the right gender and age, then accent and manner; among equals prefer heavily used ones (more reliable). For the narrator prefer voices tagged narration.
            Never choose a voice that imitates a real person (celebrities, streamers, politicians, named people) or a famous fictional character or franchise (games, anime, films) — pick generic voices only.
            Every role must get a different voice. If no candidate fits a role at all, give an empty list.
            Return JSON, your best three per role in order: {"choices":[{"role":"<role name exactly as written after ##>","voices":["v12","v3","v7"]}]}
            $listing
        """.trimIndent()
        val js = llm.generateJson(prompt, fast = true)
        val used = HashSet(taken)
        // Ranked shortlists, each voice only from its own role's candidates.
        val shortlist = LinkedHashMap<Role, List<FishSpeech.Voice>>()
        js.optJSONArray("choices")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val name = o.optString("role")
                val r = synchronized(cast) { if (name.equals("NARRATOR", true)) cast.narrator.takeIf { it in roles } else cast.role(name)?.takeIf { it in roles } } ?: continue
                val keys = o.optJSONArray("voices")?.let { v -> (0 until v.length()).map { v.optString(it) } } ?: listOfNotNull(o.optString("voice").ifBlank { null })
                shortlist[r] = keys.filter { owner[it] === r }.mapNotNull { ids[it] }.distinctBy { it.id }
            }
        }
        // The narrator and leading characters are auditioned by ear: library titles
        // can mislead ("british" that sounds American), so each shortlisted voice
        // speaks a line and Gemini listens before choosing.
        val auditioned = HashMap<Role, FishSpeech.Voice>()
        val lead = shortlist.filter { (r, l) -> (r === cast.narrator || r.major) && l.size >= 2 }
        lead.map { (r, l) -> r to searchPool.submit<FishSpeech.Voice?> { runCatching { audition(cast, r, l.take(3)) }.onFailure { log("audition ${r.name} failed: ${it.message}") }.getOrNull() } }
            .forEach { (r, f) -> runCatching { f.get(90, TimeUnit.SECONDS) }.getOrNull()?.let { auditioned[r] = it } }
        synchronized(cast) {
            // Leads first (their ears-on choice), then everyone else, keeping voices distinct.
            val order = shortlist.keys.sortedByDescending { it === cast.narrator || it.major }
            for (r in order) {
                val pickList = listOfNotNull(auditioned[r]) + shortlist[r].orEmpty()
                val v = pickList.firstOrNull { it.id !in used } ?: continue
                r.voice = v.id; r.voiceSource = "library"; used += v.id
                log("voice for ${r.name}: ${v.title} (${v.id})${if (auditioned[r]?.id == v.id) " — auditioned" else ""}")
            }
            // Roles the model skipped: their most-used unused candidate.
            for ((r, cs) in candidates) if (r.voice.isBlank()) cs.firstOrNull { it.id !in used }?.let { r.voice = it.id; r.voiceSource = "library"; used += it.id }
            cast.roles.values.filter { it.voiceSource == "narrator" }.forEach { it.voice = cast.narrator.voice }
        }
    }

    // ---- Lookup ---------------------------------------------------------------

    data class Pick(val voice: String, val style: String, val speaker: String, val role: Role? = null)

    fun pick(cast: Cast, quoteId: Int, word: Int): Pick = synchronized(cast) {
        val narratorAt = cast.narrationBy.floorEntry(word)?.value?.let { cast.role(it) } ?: cast.narrator
        // A first-person narrator shares the narrator voice.
        val narratorRole = if (narratorAt.voiceSource == "narrator") cast.narrator else narratorAt
        if (quoteId < 0) return Pick(narratorAt.voice.ifBlank { cast.narrator.voice }.ifBlank { selectedVoice }, cast.narratorStyle, narratorAt.name, narratorRole)
        val who = cast.quoteSpeaker[quoteId]
        val role = when (who) { null, Cast.UNKNOWN, Cast.NARRATOR -> narratorAt; else -> cast.role(who) ?: narratorAt }
        var style = cast.quoteStyle[quoteId].orEmpty().ifBlank { if (role === narratorAt) cast.narratorStyle else "" }
        // Library voices rarely have the character's foreign accent; S2.1 can add
        // one when the direction names it ("[courteous, Russian accent]").
        if (role !== narratorAt && isForeignAccent(role.accent, cast.language)) {
            style = listOf(style, "${role.accent} accent").filter { it.isNotBlank() }.joinToString(", ")
        }
        Pick(role.voice.ifBlank { cast.narrator.voice }.ifBlank { selectedVoice }, style, role.name,
            if (role.voiceSource == "narrator") cast.narrator else role)
    }
}

private fun isForeignAccent(accent: String, bookLanguage: String): Boolean {
    val a = accent.lowercase()
    val foreign = listOf("german", "austrian", "french", "italian", "spanish", "mexican", "portuguese", "brazilian", "dutch",
        "polish", "czech", "hungarian", "romanian", "transylvanian", "russian", "slavic", "eastern european", "ukrainian",
        "greek", "arab", "egyptian", "japanese", "chinese", "korean", "vietnamese", "indian", "swedish", "norwegian", "danish")
    return bookLanguage.lowercase().startsWith("en") && foreign.any { it in a }
}

/**
 * Splits the word stream into utterances: one voice each, sentence-shaped, and
 * short enough that the first audio arrives quickly and the highlight stays
 * tight. A spoken line and its narration ("said Elizabeth") are always
 * separate utterances, since they are different voices.
 */
object SpeechPlan {
    data class Utterance(val startWord: Int, val endWord: Int, val quoteId: Int) {
        val size get() = endWord - startWord
    }

    const val MIN_WORDS = 10
    const val MAX_WORDS = 48
    /** Long speeches split only at sentence ends, in chunks of 30–60 words: a
     *  2-minute take stalled playback while it synthesized, and a voice stays
     *  the same person across chunks (checked by ear). */
    const val MIN_QUOTE_WORDS = 30
    const val MAX_QUOTE_WORDS = 60

    fun build(book: Book, d: Dialogue.Map, from: Int, to: Int, narratorChanges: Set<Int> = emptySet()): List<Utterance> {
        val out = ArrayList<Utterance>()
        var i = from.coerceAtLeast(0)
        val end = to.coerceAtMost(book.wordCount)
        while (i < end) {
            val q = d.quoteOf[i]
            val s = i
            i++
            while (i < end) {
                if (d.quoteOf[i] != q) break
                if (narratorChanges.contains(i)) break
                val prev = book.words[i - 1].text
                val len = i - s
                val sentenceEnd = TtsReader.endsSentence(prev)
                if (q >= 0) {
                    // Inside one spoken line: whole sentences only, never mid-clause.
                    if (sentenceEnd && len >= MIN_QUOTE_WORDS) break
                    if (len >= MAX_QUOTE_WORDS && SpeechAlign.pauseAfter(prev) > 0f) break
                    if (len >= MAX_QUOTE_WORDS + 30) break
                    i++; continue
                }
                if (book.words[i].paragraphBreak && sentenceEnd) break
                if (sentenceEnd && len >= MIN_WORDS) break
                if (len >= MAX_WORDS && (sentenceEnd || SpeechAlign.pauseAfter(prev) > 0f)) break
                if (len >= MAX_WORDS + 20) break
                i++
            }
            out.add(Utterance(s, i, q))
        }
        return out
    }
}

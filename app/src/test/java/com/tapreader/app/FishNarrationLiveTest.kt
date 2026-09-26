package com.tapreader.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end against live fish.audio (s2.1-pro-free) and Gemini (casting), on
 * every book in testbooks/: narrator profile → casting window → fish.audio
 * library voices → synthesis → audio-derived word timing. Writes
 * app/build/livetest/<book>/ (clips, listen.wav, report.json).
 *
 *   ./gradlew testDebugUnitTest --tests '*FishNarrationLiveTest*' -Plive=1 [-Pbooks=hail_mary]
 *
 * Keys: .fish_key here, and the Gemini key from .gemini_key (here or in ../tapreader-gemini).
 */
class FishNarrationLiveTest {
    private val out = File(TestBooks.root, "app/build/livetest")

    private fun key(name: String): File? =
        listOf(File(TestBooks.root, name), File(TestBooks.root, "../tapreader-gemini/$name")).firstOrNull { it.isFile }

    @Test fun narrateBooks() {
        assumeTrue("live tests off (-Plive=1)", System.getProperty("tapreader.live") == "1")
        val fishKey = key(".fish_key"); val gemKey = key(".gemini_key")
        assumeTrue("need .fish_key and .gemini_key", fishKey != null && gemKey != null)
        val fish = FishSpeech(fishKey!!.readText().trim())
        val llm = GeminiText(gemKey!!.readText().trim())
        out.mkdirs()
        val books = TestBooks.files()
        val pool = Executors.newFixedThreadPool(books.size.coerceAtMost(2))
        val results = books.map { f -> pool.submit<String> { runCatching { narrate(f, fish, llm) }.getOrElse { "FAILED ${f.name}: $it\n" + it.stackTraceToString().take(1500) } } }
        val summary = results.joinToString("\n") { it.get(40, TimeUnit.MINUTES) }
        pool.shutdown()
        println(summary)
        File(out, "summary.txt").writeText(summary)
        assertTrue(summary, "FAILED" !in summary)
    }

    private fun narrate(f: File, fish: FishSpeech, llm: GeminiText): String {
        val book = TestBooks.load(f)
        val d = Dialogue.analyze(book)
        val dir = File(out, f.nameWithoutExtension).apply { deleteRecursively(); mkdirs() }
        val log = StringBuilder()
        val director = CastDirector(llm, fish, File(dir, "cast"), TtsReader.DEFAULT_VOICE) { synchronized(log) { log.append(it).append('\n') } }
        val start = dialogueStart(book, d)
        val t0 = System.currentTimeMillis()
        val cast = director.load(book)
        director.ensureNarrator(book, cast)
        val tNarrator = System.currentTimeMillis() - t0
        director.castThrough(book, cast, d, start, start + 1)
        val tReady = System.currentTimeMillis() - t0
        director.castThrough(book, cast, d, start, start + 700)

        val plan = SpeechPlan.build(book, d, start, start + 700).take(20)
        val synthPool = Executors.newFixedThreadPool(3)
        val titles = HashMap<String, String>()
        val clips = plan.mapIndexed { idx, u ->
            synthPool.submit<JSONObject?> {
                val pick = director.pick(cast, u.quoteId, u.startWord)
                val words = book.words.subList(u.startWord, u.endWord).map { it.text }
                val raw = TtsReader.speakable(words, u.quoteId >= 0)
                val text = TtsReader.sanitizeForSpeech(raw)
                if (text.isBlank()) return@submit null
                val style = pick.style.ifBlank { null }?.takeIf { u.quoteId >= 0 || it.contains("accent") }
                val ts = System.currentTimeMillis()
                val wav = fish.synthesize(text, pick.voice, style)
                val ms = System.currentTimeMillis() - ts
                val pcm = SpeechAlign.parseWav(wav) ?: error("not a WAV: ${wav.size} bytes, starts ${String(wav.take(4).toByteArray())}")
                val timing = SpeechAlign.align(words, pcm)
                val name = "u%03d.wav".format(idx)
                File(dir, name).writeBytes(wav)
                JSONObject().put("wav", name).put("startWord", u.startWord).put("endWord", u.endWord)
                    .put("quoteId", u.quoteId).put("speaker", pick.speaker).put("voice", pick.voice)
                    .put("style", style ?: "").put("text", text).put("words", JSONArray(words))
                    .put("startsMs", JSONArray(timing.startsMs.toList())).put("durMs", pcm.durationMs)
                    .put("rate", pcm.rate).put("synthMs", ms)
            }
        }.mapNotNull { it.get(10, TimeUnit.MINUTES) }
        synthPool.shutdown()
        stitch(dir, clips)
        fun title(id: String) = titles.getOrPut(id) { fish.voice(id)?.title ?: id }

        val report = JSONObject().put("file", f.name).put("title", book.title).put("start", start)
            .put("narratorMs", tNarrator).put("readyMs", tReady)
            .put("cast", synchronized(cast) { cast.toJson() }).put("utterances", JSONArray(clips)).put("log", log.toString())
        File(dir, "report.json").writeText(report.toString(1))

        val sb = StringBuilder("== ${f.name}: ${book.title} (from word $start; narrator profile ${tNarrator}ms, first window + voices ${tReady}ms)\n")
        synchronized(cast) {
            sb.append("  narrator: \"${title(cast.narrator.voice)}\" ${cast.person}, ${cast.language}; wanted: ${cast.narrator.sketch.take(110)}\n")
            for (r in cast.roles.values.sortedByDescending { it.lines }) sb.append("  ${r.name} (${r.gender}, ${r.age}, ${r.accent.take(30)}) lines=${r.lines} → \"${title(r.voice)}\" [${r.voiceSource}]\n")
        }
        for (c in clips) sb.append("  [${c.getString("speaker")}|${c.optString("style").take(24)}] ${c.getLong("synthMs")}ms ${c.getInt("durMs")}ms: ${c.getString("text").take(60)}\n")
        return sb.toString()
    }

    /** First paragraph past the front matter where a real conversation starts. */
    private fun dialogueStart(b: Book, d: Dialogue.Map): Int {
        val verbs = Regex("^(said|says|cried|replied|asked|answered|exclaimed|whispered|continued|returned|added)[,.;]?$", RegexOption.IGNORE_CASE)
        for (i in maxOf(300, b.wordCount / 50) until b.wordCount - 600) {
            if (!b.words[i].paragraphBreak) continue
            if (d.spans.count { it.startWord in i until i + 600 } < 8) continue
            if ((i until i + 600).count { verbs.matches(b.words[it].text) } >= 4) return i
        }
        return 0
    }

    private fun stitch(dir: File, clips: List<JSONObject>) {
        val pcm = ByteArrayOutputStream()
        var rate = 24000
        for (c in clips) {
            val p = SpeechAlign.parseWav(File(dir, c.getString("wav")).readBytes()) ?: continue
            rate = p.rate
            for (s in p.samples) { pcm.write(s.toInt() and 0xFF); pcm.write((s.toInt() shr 8) and 0xFF) }
            repeat(rate * 120 / 1000 * 2) { pcm.write(0) }
        }
        File(dir, "listen.wav").writeBytes(SpeechAlign.wav(ShortArray(pcm.size() / 2) { i ->
            val b = pcm.toByteArray(); ((b[2 * i].toInt() and 0xFF) or (b[2 * i + 1].toInt() shl 8)).toShort() }, rate))
    }
}

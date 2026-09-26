package com.tapreader.app

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Scores (and optionally fits) [SpeechAlign] against forced-alignment ground
 * truth on the clips NarrationLiveTest recorded. Needs
 * app/build/livetest/<book>/truth.json from tools/forced_truth.py; skipped otherwise.
 *
 * on-word % = share of each spoken word's duration during which the highlight
 * sits on that word. -Ptune=1 runs a grid search over the parameters.
 */
class SpeechAlignTuneTest {
    private class Clip(val words: List<String>, val pcm: SpeechAlign.Pcm, val truth: List<IntArray?>)

    private fun clips(): List<Pair<String, Clip>> {
        val out = File(TestBooks.root, "app/build/livetest")
        val list = ArrayList<Pair<String, Clip>>()
        for (dir in out.listFiles()?.sortedBy { it.name } ?: emptyList()) {
            val truthFile = File(dir, "truth.json"); val rep = File(dir, "report.json")
            if (!truthFile.isFile || !rep.isFile) continue
            val truth = JSONObject(truthFile.readText())
            val utts = JSONObject(rep.readText()).getJSONArray("utterances")
            for (i in 0 until utts.length()) {
                val u = utts.getJSONObject(i)
                val wav = u.getString("wav")
                val row = truth.optJSONArray(wav) ?: continue
                val words = u.getJSONArray("words").let { a -> (0 until a.length()).map { a.getString(it) } }
                val t = (0 until row.length()).map { k -> (row.opt(k) as? JSONArray)?.let { intArrayOf(it.getInt(0), it.getInt(1)) } }
                val pcm = SpeechAlign.parseWav(File(dir, wav).readBytes()) ?: continue
                list += dir.name to Clip(words, pcm, t)
            }
        }
        return list
    }

    /** (hits, samples, sum |start error|, matched words, near hits) */
    private fun score(c: Clip, starts: IntArray): LongArray {
        var hit = 0L; var tot = 0L; var err = 0L; var n = 0L; var near = 0L
        fun at(t: Int): Int { var k = 0; for (i in starts.indices) if (starts[i] <= t) k = i else break; return k }
        for ((i, tr) in c.truth.withIndex()) {
            tr ?: continue
            err += kotlin.math.abs(starts[i] - tr[0]); n++
            var t = tr[0]
            while (t < tr[1]) { tot++; val k = at(t); if (k == i) hit++; if (kotlin.math.abs(k - i) <= 1) near++; t += 10 }
        }
        return longArrayOf(hit, tot, err, n, near)
    }

    private fun oldStarts(words: List<String>, durMs: Int): IntArray {
        val w = words.map { t ->
            var x = 1.2f + t.count { it.isLetterOrDigit() }.coerceAtLeast(1) * 0.9f
            if (t.endsWith(",") || t.endsWith(";") || t.endsWith(":")) x += 2.5f
            if (t.endsWith(".") || t.endsWith("!") || t.endsWith("?") || t.endsWith("…")) x += 4.5f
            x
        }
        val total = w.sum(); var acc = 0f
        return IntArray(words.size) { i -> ((acc / total) * durMs).toInt().also { acc += w[i] } }
    }

    private fun report(label: String, all: List<Pair<String, Clip>>, starts: (Clip) -> IntArray): Double {
        val byBook = all.groupBy({ it.first }, { it.second })
        val sb = StringBuilder("$label\n")
        var H = 0L; var T = 0L; var N = 0L
        for ((book, cs) in byBook) {
            val s = cs.map { score(it, starts(it)) }.reduce { a, b -> LongArray(5) { a[it] + b[it] } }
            H += s[0]; T += s[1]; N += s[4]
            sb.append("   %-14s on-word %5.1f%%   ±1 word %5.1f%%   mean start err %4d ms\n".format(book,
                100.0 * s[0] / s[1].coerceAtLeast(1), 100.0 * s[4] / s[1].coerceAtLeast(1), s[2] / s[3].coerceAtLeast(1)))
        }
        sb.append("   %-14s on-word %5.1f%%   ±1 word %5.1f%%\n".format("ALL", 100.0 * H / T.coerceAtLeast(1), 100.0 * N / T.coerceAtLeast(1)))
        println(sb)
        return 100.0 * H / T.coerceAtLeast(1)
    }

    @Test fun scoreAgainstGroundTruth() {
        val everything = clips()
        assumeTrue("no truth.json (run tools/forced_truth.py)", everything.isNotEmpty())
        report("fish-era proportional baseline", everything) { oldStarts(it.words, it.pcm.durationMs) }
        report("SpeechAlign gap-only", everything) { SpeechAlign.align(it.words, it.pcm, SpeechAlign.DEFAULT.copy(fine = false)).startsMs }
        report("SpeechAlign defaults", everything) { SpeechAlign.align(it.words, it.pcm).startsMs }
        if (System.getProperty("tapreader.tune") != "1") return

        // Fit on half the books, report on the held-out half too.
        // Every other book (by name) trains; the rest are held out.
        val names = everything.map { it.first }.distinct().sorted()
        val trainBooks = names.filterIndexed { i, _ -> i % 2 == 0 }.toSet()
        println("train on $trainBooks, hold out ${names - trainBooks}")
        val all = everything.filter { it.first in trainBooks }
        var best = SpeechAlign.DEFAULT
        var bestScore = -1.0
        val envs = all.map { SpeechAlign.envelope(it.second.pcm) }
        val detectCache = HashMap<Triple<Float, Float, Int>, List<Triple<Int, Int, List<SpeechAlign.Gap>>>>()
        fun eval(p: SpeechAlign.Params): Double {
            val det = detectCache.getOrPut(Triple(p.silenceBelowLoudDb, p.silenceCeilingDb, p.minGapMs)) { all.mapIndexed { i, it -> SpeechAlign.detect(envs[i], it.second.pcm.durationMs, p) } }
            var h = 0L; var t = 0L
            for ((i, c) in all.withIndex()) {
                val (s0, s1, g) = det[i]
                val st = if (p.fine) SpeechAlign.alignFine(c.second.words, envs[i], s0, s1, g, p) else SpeechAlign.align(c.second.words, s0, s1, g, p)
                val s = score(c.second, st.startsMs)
                h += s[0]; t += s[1]
            }
            return 100.0 * h / t.coerceAtLeast(1)
        }
        // Coordinate descent: sweep one knob at a time, keep improvements, repeat.
        val sweeps: List<Pair<String, (SpeechAlign.Params, Float) -> SpeechAlign.Params>> = listOf(
            "silenceBelowLoudDb" to { p, v -> p.copy(silenceBelowLoudDb = v) },
            "silenceCeilingDb" to { p, v -> p.copy(silenceCeilingDb = v) },
            "minGapMs" to { p, v -> p.copy(minGapMs = v.toInt()) },
            "deviationCost" to { p, v -> p.copy(deviationCost = v) },
            "punctReward" to { p, v -> p.copy(punctReward = v) },
            "dropBase" to { p, v -> p.copy(dropBase = v) },
            "syllableWeight" to { p, v -> p.copy(syllableWeight = v) },
            "letterWeight" to { p, v -> p.copy(letterWeight = v) },
            "baseWeight" to { p, v -> p.copy(baseWeight = v) },
            "leadMs" to { p, v -> p.copy(leadMs = v.toInt()) },
            "dipMinDb" to { p, v -> p.copy(dipMinDb = v) },
            "dipScaleDb" to { p, v -> p.copy(dipScaleDb = v) },
            "gapReward" to { p, v -> p.copy(gapReward = v) },
            "punctBoost" to { p, v -> p.copy(punctBoost = v) },
            "durCost" to { p, v -> p.copy(durCost = v) },
            "groupPenalty" to { p, v -> p.copy(groupPenalty = v) },
            "gapSkipCost" to { p, v -> p.copy(gapSkipCost = v) }
        )
        val values = mapOf(
            "silenceBelowLoudDb" to listOf(18f, 22f, 26f, 29f, 32f, 36f),
            "silenceCeilingDb" to listOf(-26f, -30f, -34f, -38f, -42f),
            "minGapMs" to listOf(50f, 70f, 90f, 120f, 160f),
            "deviationCost" to listOf(1.5f, 3f, 5f, 7f, 9f, 13f),
            "punctReward" to listOf(0.3f, 0.6f, 1f, 1.5f, 2.2f),
            "dropBase" to listOf(0f, 0.25f, 0.5f, 1f),
            "syllableWeight" to listOf(0.6f, 0.8f, 1f, 1.2f),
            "letterWeight" to listOf(0f, 0.07f, 0.15f, 0.25f),
            "baseWeight" to listOf(0f, 0.2f, 0.35f, 0.6f, 1f),
            "leadMs" to listOf(-30f, 0f, 30f, 60f, 90f),
            "dipMinDb" to listOf(0.75f, 1f, 1.5f, 2f, 3f),
            "dipScaleDb" to listOf(3f, 6f, 10f),
            "gapReward" to listOf(1f, 2f, 3f, 5f),
            "punctBoost" to listOf(0f, 0.5f, 1f, 2f),
            "durCost" to listOf(1f, 2f, 3.5f, 5f, 7f, 10f),
            "groupPenalty" to listOf(0f, 0.15f, 0.3f, 0.6f, 1f),
            "gapSkipCost" to listOf(0.5f, 1f, 2f, 4f)
        )
        bestScore = eval(best)
        repeat(4) { round ->
            for ((name, set) in sweeps) for (v in values.getValue(name)) {
                val p = set(best, v); val s = eval(p)
                if (s > bestScore + 0.05) { bestScore = s; best = p }
            }
            println("round ${round + 1}: %.2f%%  $best".format(bestScore))
        }
        report("tuned — train books", all) { SpeechAlign.align(it.words, it.pcm, best).startsMs }
        report("tuned — HELD-OUT books", everything.filter { it.first !in trainBooks }) { SpeechAlign.align(it.words, it.pcm, best).startsMs }
        report("defaults — HELD-OUT books", everything.filter { it.first !in trainBooks }) { SpeechAlign.align(it.words, it.pcm).startsMs }
    }
}

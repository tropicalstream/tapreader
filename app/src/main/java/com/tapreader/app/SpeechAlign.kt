package com.tapreader.app

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Word timing for a synthesized utterance, derived from the audio itself.
 *
 * Gemini TTS returns raw 24 kHz PCM (in a WAV wrapper) but no word timestamps.
 * Pure proportional mapping (the fish.audio approach) drifts whenever the voice
 * takes a real pause, and character voices pause a lot. Instead we read the
 * PCM: find where speech starts and ends, find every silent gap inside it, and
 * match those gaps to the word boundaries where a pause belongs (punctuation
 * first). The matching is a small monotonic DP, so a breath at a comma lands on
 * that comma rather than stretching the whole line. Between two anchored gaps,
 * words share the voiced time by estimated syllable weight.
 */
object SpeechAlign {
    class Pcm(val samples: ShortArray, val rate: Int) {
        val durationMs: Int get() = (samples.size * 1000L / rate.coerceAtLeast(1)).toInt()
    }

    data class Gap(val startMs: Int, val endMs: Int) { val len get() = endMs - startMs }

    /**
     * Tuning knobs. Defaults were fitted (SpeechAlignTuneTest -Ptune=1) against
     * wav2vec2 forced alignment of fish.audio S2.1 clips (tools/forced_truth.py):
     * fit on two books, checked on two held out — the highlight is on the exact
     * word 82% of the speaking time and on it or a neighbour 99%, versus 65% /
     * 95% for the old even-spread method.
     */
    data class Params(
        /** Silence = more than this many dB below the loud (95th percentile) level… */
        val silenceBelowLoudDb: Float = 18f,
        /** …and never louder than this absolute level (dBFS). */
        val silenceCeilingDb: Float = -30f,
        val minGapMs: Int = 160,
        /** Cost per unit of deviation (fraction of the voiced time) when pinning a gap to a boundary. */
        val deviationCost: Float = 9f,
        /** Reward for pinning a gap to punctuation, scaled by pause strength. */
        val punctReward: Float = 0.6f,
        val dropBase: Float = 0.25f,
        val dropPerMs: Float = 1f / 400f,
        val syllableWeight: Float = 0.6f,
        val letterWeight: Float = 0.25f,
        val baseWeight: Float = 0f,
        /** Highlight this much ahead of the computed start (perceived sync). */
        val leadMs: Int = 30,
        // ---- fine alignment (energy dips as well as silences) ----
        val fine: Boolean = true,
        /** A dip counts as a possible word boundary when this many dB below both neighbouring peaks. */
        val dipMinDb: Float = 3f,
        val dipScaleDb: Float = 10f,
        val gapReward: Float = 5f,
        val punctBoost: Float = 2f,
        /** Cost of a word (group) lasting e× its expected time: durCost·ln(e)². */
        val durCost: Float = 7f,
        /** Cost per word boundary left without an anchor. */
        val groupPenalty: Float = 0.15f,
        /** Cost of leaving a silent gap inside a word. */
        val gapSkipCost: Float = 2f,
        val maxGroup: Int = 4
    )

    val DEFAULT = Params()

    class Timing(val startsMs: IntArray, val speechStartMs: Int, val speechEndMs: Int, val gaps: List<Gap>) {
        /** Index (0-based within the utterance) of the word being spoken at [posMs]. */
        fun wordAt(posMs: Int): Int {
            var lo = 0; var hi = startsMs.size - 1; var ans = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (startsMs[mid] <= posMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
            }
            return ans
        }
    }

    // ---- WAV ----------------------------------------------------------------

    /** Parses a RIFF/WAVE 16-bit PCM file (mono, or the first channel of many). */
    fun parseWav(bytes: ByteArray): Pcm? {
        if (bytes.size < 44 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var p = 12
        var rate = 24000; var channels = 1; var bits = 16
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4, Charsets.US_ASCII)
            var len = le32(bytes, p + 4)
            val body = p + 8
            if (id == "fmt ") {
                channels = le16(bytes, body + 2).coerceAtLeast(1)
                rate = le32(bytes, body + 4)
                bits = le16(bytes, body + 14)
            } else if (id == "data") {
                if (bits != 16) return null
                // Streaming writers leave the size as 0 / 0xFFFFFFFF — take the rest.
                if (len <= 0 || body + len > bytes.size) len = bytes.size - body
                val frames = len / (2 * channels)
                val out = ShortArray(frames)
                for (i in 0 until frames) {
                    val o = body + i * 2 * channels
                    out[i] = ((bytes[o].toInt() and 0xFF) or (bytes[o + 1].toInt() shl 8)).toShort()
                }
                return Pcm(out, rate)
            }
            p = body + len + (len and 1)
        }
        return null
    }

    /** 16-bit mono WAV bytes for [samples]. */
    fun wav(samples: ShortArray, rate: Int): ByteArray {
        val data = samples.size * 2
        val out = ByteArray(44 + data)
        fun put32(o: Int, v: Int) { for (i in 0 until 4) out[o + i] = (v shr (8 * i)).toByte() }
        fun put16(o: Int, v: Int) { for (i in 0 until 2) out[o + i] = (v shr (8 * i)).toByte() }
        "RIFF".toByteArray().copyInto(out, 0); put32(4, 36 + data); "WAVE".toByteArray().copyInto(out, 8)
        "fmt ".toByteArray().copyInto(out, 12); put32(16, 16); put16(20, 1); put16(22, 1)
        put32(24, rate); put32(28, rate * 2); put16(32, 2); put16(34, 16)
        "data".toByteArray().copyInto(out, 36); put32(40, data)
        for ((i, v) in samples.withIndex()) put16(44 + i * 2, v.toInt())
        return out
    }

    /**
     * Speech written as musical notes (Rocky's Eridian in Project Hail Mary:
     * “♩♫♪♪♫,” says Rocky) played as notes: one soft chord-like tone per
     * symbol, each symbol its own pitch, with a small glide so it sounds voiced.
     */
    fun tones(symbols: String, rate: Int = 24000): ShortArray {
        val pitch = mapOf('♩' to 293.7, '♪' to 392.0, '♫' to 493.9, '♬' to 587.3)
        val out = ArrayList<Short>()
        repeat(rate * 60 / 1000) { out += 0 }
        for ((n, c) in symbols.withIndex()) {
            val f0 = pitch[c] ?: continue
            val len = rate * 190 / 1000
            var phase = 0.0
            for (k in 0 until len) {
                val t = k.toDouble() / len
                val f = f0 * (1.0 + 0.03 * kotlin.math.sin(Math.PI * t) + if (n % 2 == 1) 0.01 else 0.0)
                phase += 2 * Math.PI * f / rate
                val env = kotlin.math.min(1.0, k / (rate * 0.012)) * kotlin.math.exp(-3.2 * t)
                val v = 0.34 * env * (kotlin.math.sin(phase) + 0.35 * kotlin.math.sin(2 * phase) + 0.15 * kotlin.math.sin(3 * phase))
                out += (v * 32767 * 0.8).toInt().coerceIn(-32767, 32767).toShort()
            }
            repeat(rate * 35 / 1000) { out += 0 }
        }
        repeat(rate * 120 / 1000) { out += 0 }
        return out.toShortArray()
    }

    /**
     * How much the voice's pitch moves: the spread (standard deviation, in
     * semitones) of the fundamental frequency over voiced frames, found by
     * autocorrelation. Lively reading moves 3-5 semitones; a flat, mechanical
     * delivery stays under ~2. Returns (median pitch Hz, spread) or null when
     * too little is voiced to tell.
     */
    fun pitchSpread(pcm: Pcm): Pair<Float, Float>? {
        val sr = pcm.rate
        val win = sr * 40 / 1000; val hop = sr / 100
        val x = FloatArray(pcm.samples.size) { pcm.samples[it] / 32768f }
        var total = 0.0; for (v in x) total += v * v
        val rmsAll = sqrt(total / x.size.coerceAtLeast(1)).toFloat() + 1e-9f
        val lo = sr / 400; val hi = sr / 70
        val f0 = ArrayList<Float>()
        var s = 0
        while (s + win < x.size) {
            var mean = 0f; for (k in 0 until win) mean += x[s + k]; mean /= win
            var e = 0.0; for (k in 0 until win) { val v = x[s + k] - mean; e += v * v }
            if (sqrt(e / win) >= rmsAll * 0.5) {
                var best = 0.0; var bestLag = -1
                for (lag in lo..hi) {
                    var c = 0.0
                    for (k in 0 until win - lag) c += (x[s + k] - mean).toDouble() * (x[s + k + lag] - mean)
                    if (c > best) { best = c; bestLag = lag }
                }
                if (bestLag > 0 && best > 0.35 * e) f0 += sr.toFloat() / bestLag
            }
            s += hop
        }
        if (f0.size < 10) return null
        val sorted = f0.sorted(); val median = sorted[sorted.size / 2]
        val st = f0.map { 12.0 * log10(it / median.toDouble()) / log10(2.0) }
        val m = st.average()
        return median to sqrt(st.sumOf { (it - m) * (it - m) } / st.size).toFloat()
    }

    /** The clip cut to [leadMs] before speech starts and [tailMs] after it ends. */
    fun trimmedWav(pcm: Pcm, leadMs: Int, tailMs: Int): ByteArray {
        // A far gentler threshold than alignment uses: soft onsets (h, s, f)
        // and trailing breaths sit 35-45 dB down and must not be clipped.
        val (s0, s1, _) = detect(pcm, DEFAULT.copy(silenceBelowLoudDb = 45f, silenceCeilingDb = -50f))
        val from = ((s0 - leadMs).coerceAtLeast(0).toLong() * pcm.rate / 1000).toInt()
        val to = ((s1 + tailMs).coerceAtMost(pcm.durationMs).toLong() * pcm.rate / 1000).toInt().coerceIn(from, pcm.samples.size)
        return wav(pcm.samples.copyOfRange(from, to), pcm.rate)
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)

    // ---- Silence detection --------------------------------------------------

    private const val FRAME_MS = 10

    /** Frame energy in dBFS, one value per 10 ms. */
    fun envelope(pcm: Pcm): FloatArray {
        val frameLen = (pcm.rate * FRAME_MS / 1000).coerceAtLeast(1)
        val n = pcm.samples.size / frameLen
        val db = FloatArray(n)
        for (f in 0 until n) {
            var sum = 0.0
            val o = f * frameLen
            for (k in 0 until frameLen) { val s = pcm.samples[o + k].toDouble(); sum += s * s }
            val rms = sqrt(sum / frameLen) / 32768.0
            db[f] = (20 * log10(max(rms, 1e-7))).toFloat()
        }
        return db
    }

    /** Returns (speechStart, speechEnd, inner gaps) in ms. */
    fun detect(pcm: Pcm, p: Params = DEFAULT): Triple<Int, Int, List<Gap>> = detect(envelope(pcm), pcm.durationMs, p)

    fun detect(db: FloatArray, durationMs: Int, p: Params = DEFAULT): Triple<Int, Int, List<Gap>> {
        val n = db.size
        if (n == 0) return Triple(0, durationMs, emptyList())
        val sorted = db.sortedArray()
        val loud = sorted[(n * 0.95).toInt().coerceAtMost(n - 1)]
        // Silence = well below the loud speech level, but never above -38 dBFS.
        val thresh = min(loud - p.silenceBelowLoudDb, p.silenceCeilingDb)
        val voiced = BooleanArray(n) { db[it] > thresh }
        val first = voiced.indexOfFirst { it }
        val last = voiced.indexOfLast { it }
        if (first < 0) return Triple(0, durationMs, emptyList())
        val gaps = ArrayList<Gap>()
        var f = first
        while (f <= last) {
            if (!voiced[f]) {
                val s = f
                while (f <= last && !voiced[f]) f++
                val lenMs = (f - s) * FRAME_MS
                if (lenMs >= p.minGapMs) gaps.add(Gap(s * FRAME_MS, f * FRAME_MS))
            } else f++
        }
        return Triple(first * FRAME_MS, (last + 1) * FRAME_MS, gaps)
    }

    // ---- Word weights -------------------------------------------------------

    /** Relative speaking time of a word: syllables dominate, letters refine. */
    fun weight(word: String, p: Params = DEFAULT): Float {
        // "XXXVII" is said "thirty-seven": weigh roman numerals by their spoken form.
        val core = word.trim { !it.isLetterOrDigit() }
        if (core.length >= 2 && core.all { it in "IVXLCDM" }) romanValue(core)?.let { return p.baseWeight + numberSyllables(it) * p.syllableWeight + 4 * p.letterWeight }
        val w = word.lowercase()
        val digits = w.count { it.isDigit() }
        val letters = w.count { it.isLetter() }
        if (letters == 0 && digits == 0) return 0.3f
        var syl = 0
        var inVowel = false
        for (c in w) {
            val v = c in "aeiouy"
            if (v && !inVowel) syl++
            inVowel = v
        }
        if (w.endsWith("e") && !w.endsWith("le") && syl > 1) syl--   // silent e
        syl = syl.coerceAtLeast(if (letters > 0) 1 else 0)
        return p.baseWeight + syl * p.syllableWeight + letters * p.letterWeight + digits * 1.1f
    }

    private fun romanValue(r: String): Int? {
        val v = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        for (i in r.indices) {
            val cur = v[r[i]] ?: return null
            val next = if (i + 1 < r.length) v[r[i + 1]] ?: return null else 0
            total += if (cur < next) -cur else cur
        }
        return total.takeIf { it in 1..3999 }
    }

    /** Rough syllable count of a number said aloud ("thirty-seven" = 4). */
    private fun numberSyllables(n: Int): Int = when {
        n >= 1000 -> numberSyllables(n / 1000) + 2 + if (n % 1000 > 0) numberSyllables(n % 1000) else 0
        n >= 100 -> numberSyllables(n / 100) + 2 + if (n % 100 > 0) 1 + numberSyllables(n % 100) else 0
        n >= 20 -> 2 + if (n % 10 > 0) numberSyllables(n % 10) else 0
        n >= 13 -> 2
        n == 11 || n == 7 -> if (n == 11) 3 else 2
        else -> 1
    }

    /** Pause strength of the boundary AFTER [word]: 1 sentence, 0.7 clause, 0 none. */
    fun pauseAfter(word: String): Float {
        val t = word.trimEnd('”', '"', '’', '\'', ')', ']', '»', '_', '*')
        if (t.trimStart('“', '"', '‘', '(').lowercase() in TtsReader.ABBREVIATIONS) return 0f   // "Mr." runs straight on
        return when {
            t.endsWith(".") || t.endsWith("!") || t.endsWith("?") || t.endsWith("…") -> 1f
            t.endsWith(",") || t.endsWith(";") || t.endsWith(":") || t.endsWith("—") || t.endsWith("–") || t.endsWith("-") -> 0.7f
            word.length != t.length -> 0.5f      // closing quote with no punctuation
            else -> 0f
        }
    }

    // ---- Alignment ----------------------------------------------------------

    fun align(words: List<String>, pcm: Pcm, p: Params = DEFAULT): Timing {
        val db = envelope(pcm)
        val (s0, s1, gaps) = detect(db, pcm.durationMs, p)
        return if (p.fine) alignFine(words, db, s0, s1, gaps, p) else align(words, s0, s1, gaps, p)
    }

    private class Cand(val a: Int, val b: Int, val reward: Float, val gapLen: Int)

    /**
     * Word boundaries from the audio's own shape. Candidates are every silent
     * gap plus every energy dip (a trough a few dB below the speech on both
     * sides — where most word joins sit even in connected speech). A DP then
     * picks which candidates are word boundaries: it rewards deep dips and
     * gaps (more so after punctuation), and charges for words whose length
     * strays from what their syllables predict at this clip's speaking rate.
     * Words that share a stretch with no chosen boundary split it by weight.
     */
    fun alignFine(words: List<String>, db: FloatArray, speechStart: Int, speechEnd: Int, gaps: List<Gap>, p: Params = DEFAULT): Timing {
        val n = words.size
        if (n <= 1 || speechEnd - speechStart < 100) return align(words, speechStart, speechEnd, gaps, p)
        val w = FloatArray(n) { weight(words[it], p) }
        val cumW = FloatArray(n + 1).also { for (i in 0 until n) it[i + 1] = it[i] + w[i] }
        val gapTotal = gaps.sumOf { it.len }
        val rate = ((speechEnd - speechStart - gapTotal).coerceAtLeast(50)).toFloat() / cumW[n].coerceAtLeast(1e-3f)

        // Candidates in time order: start sentinel, gaps and dips, end sentinel.
        val sm = FloatArray(db.size) { f -> var t = 0f; var c = 0; for (k in f - 1..f + 1) if (k in db.indices) { t += db[k]; c++ }; t / c }
        val inGap = BooleanArray(db.size)
        for (g in gaps) for (f in g.startMs / FRAME_MS until (g.endMs / FRAME_MS).coerceAtMost(db.size)) inGap[f] = true
        val cands = ArrayList<Cand>()
        cands += Cand(speechStart, speechStart, 0f, 0)
        val f0 = speechStart / FRAME_MS + 3
        val f1 = (speechEnd / FRAME_MS - 3).coerceAtMost(db.size - 1)
        var gi = 0
        var f = f0
        while (f < f1) {
            while (gi < gaps.size && gaps[gi].endMs <= f * FRAME_MS) gi++
            if (gi < gaps.size && gaps[gi].startMs <= f * FRAME_MS) {
                val g = gaps[gi]
                cands += Cand(g.startMs, g.endMs, p.gapReward + g.len / 100f, g.len)
                f = g.endMs / FRAME_MS + 1; gi++; continue
            }
            if (!inGap[f]) {
                var isMin = true
                for (k in f - 2..f + 2) if (k != f && k in sm.indices && sm[k] < sm[f]) { isMin = false; break }
                if (isMin) {
                    var lp = sm[f]; var rp = sm[f]
                    for (k in (f - 10).coerceAtLeast(0)..f) lp = max(lp, sm[k])
                    for (k in f..(f + 10).coerceAtMost(sm.size - 1)) rp = max(rp, sm[k])
                    val depth = min(lp, rp) - sm[f]
                    if (depth >= p.dipMinDb) cands += Cand(f * FRAME_MS + FRAME_MS / 2, f * FRAME_MS + FRAME_MS / 2, min(depth / p.dipScaleDb, 2f), 0)
                }
            }
            f++
        }
        cands += Cand(speechEnd, speechEnd, 0f, 0)
        val C = cands.size
        // Prefix sums of gap time and gap-skip cost, by candidate index.
        val gapMs = IntArray(C + 1); val skipCost = FloatArray(C + 1)
        for (c in 0 until C) {
            gapMs[c + 1] = gapMs[c] + cands[c].gapLen
            skipCost[c + 1] = skipCost[c] + if (cands[c].gapLen > 0) p.gapSkipCost * (1f + cands[c].gapLen / 100f) else 0f
        }

        val NEG = -1e9f
        val best = Array(n + 1) { FloatArray(C) { NEG } }
        val backJ = Array(n + 1) { IntArray(C) { -1 } }
        val backC = Array(n + 1) { IntArray(C) { -1 } }
        best[0][0] = 0f
        for (j in 1..n) {
            val cRange = if (j == n) (C - 1)..(C - 1) else 1 until C - 1
            val punct = if (j < n) pauseAfter(words[j - 1]) else 0f
            for (c in cRange) {
                val cand = cands[c]
                val gain = if (j == n) 0f else cand.reward * (1f + p.punctBoost * punct)
                var bestHere = NEG; var bj = -1; var bc = -1
                for (k in 1..p.maxGroup) {
                    val jp = j - k
                    if (jp < 0) break
                    val expected = rate * (cumW[j] - cumW[jp])
                    val row = best[jp]
                    var cp = c - 1
                    while (cp >= 0) {
                        val prev = cands[cp]
                        // Voiced time between the two boundaries (skipped gaps don't count).
                        val actual = cand.a - prev.b - (gapMs[c] - gapMs[cp + 1])
                        if (actual > expected * 4f + 400) break          // further back only gets longer
                        if (row[cp] > NEG / 2 && actual >= 30) {
                            val r = ln(actual / expected.coerceAtLeast(1f))
                            val sc = row[cp] + gain - p.durCost * r * r - p.groupPenalty * (k - 1) - (skipCost[c] - skipCost[cp + 1])
                            if (sc > bestHere) { bestHere = sc; bj = jp; bc = cp }
                        }
                        cp--
                    }
                }
                best[j][c] = bestHere; backJ[j][c] = bj; backC[j][c] = bc
            }
        }
        if (best[n][C - 1] <= NEG / 2) return align(words, speechStart, speechEnd, gaps, p)

        // Walk back: each hop is one group of words between two chosen boundaries.
        val starts = IntArray(n)
        var j = n; var c = C - 1
        while (j > 0) {
            val jp = backJ[j][c]; val cp = backC[j][c]
            val t0 = cands[cp].b; val t1 = cands[c].a
            val span = cumW[j] - cumW[jp]
            for (k in jp until j) {
                val frac = if (span <= 0f) 0f else (cumW[k] - cumW[jp]) / span
                starts[k] = (t0 + frac * (t1 - t0)).toInt() - p.leadMs
            }
            j = jp; c = cp
        }
        return Timing(starts, speechStart, speechEnd, gaps)
    }

    fun align(words: List<String>, speechStart: Int, speechEnd: Int, gaps: List<Gap>, p: Params = DEFAULT): Timing {
        val n = words.size
        if (n == 0) return Timing(IntArray(0), speechStart, speechEnd, gaps)
        val w = FloatArray(n) { weight(words[it], p) }
        val cum = FloatArray(n + 1).also { for (i in 0 until n) it[i + 1] = it[i] + w[i] }
        val total = cum[n].coerceAtLeast(1e-3f)
        val voicedTotal = (speechEnd - speechStart - gaps.sumOf { it.len }).coerceAtLeast(1)

        // DP over gaps (in time order) × boundaries 1..n-1 (boundary j = before word j).
        val m = gaps.size
        val matched = IntArray(m) { -1 }
        if (m > 0 && n > 1) {
            val vFrac = FloatArray(m)
            var before = 0
            for ((i, g) in gaps.withIndex()) {
                vFrac[i] = ((g.startMs - speechStart - before).toFloat() / voicedTotal).coerceIn(0f, 1f)
                before += g.len
            }
            // F[i][j]: best score after deciding gaps 0..i-1 with every matched
            // boundary <= j (boundary j sits before word j; j = 0 means none yet).
            val nb = n - 1
            val F = Array(m + 1) { FloatArray(nb + 1) }
            val back = Array(m + 1) { IntArray(nb + 1) }     // 0 drop, 1 match, 2 carry
            for (i in 0 until m) {
                val g = gaps[i]
                val drop = -(p.dropBase + g.len * p.dropPerMs)
                val lenBoost = min(g.len / 250f, 1.5f)
                for (j in 0..nb) {
                    var sc = F[i][j] + drop; var bk = 0
                    if (j >= 1) {
                        val dev = abs(vFrac[i] - cum[j] / total)
                        val punct = pauseAfter(words[j - 1])
                        val s = F[i][j - 1] + punct * p.punctReward * (1f + lenBoost) - p.deviationCost * dev
                        if (s > sc) { sc = s; bk = 1 }
                        if (F[i + 1][j - 1] > sc) { sc = F[i + 1][j - 1]; bk = 2 }
                    }
                    F[i + 1][j] = sc; back[i + 1][j] = bk
                }
            }
            var i = m; var j = nb
            while (i > 0) {
                when (back[i][j]) {
                    2 -> j--
                    1 -> { matched[i - 1] = j; i--; j-- }
                    else -> { matched[i - 1] = -1; i-- }
                }
            }
        }

        // Anchors: word index -> start time, plus the end of the previous word.
        val starts = IntArray(n)
        data class Anchor(val word: Int, val startMs: Int, val prevEndMs: Int)
        val anchors = ArrayList<Anchor>()
        anchors.add(Anchor(0, speechStart, speechStart))
        for (i in 0 until m) if (matched[i] >= 1) anchors.add(Anchor(matched[i], gaps[i].endMs, gaps[i].startMs))
        anchors.add(Anchor(n, speechEnd, speechEnd))
        for (a in 0 until anchors.size - 1) {
            val from = anchors[a]; val to = anchors[a + 1]
            val t0 = from.startMs; val t1 = max(to.prevEndMs, t0 + 1)
            val span = cum[to.word] - cum[from.word]
            for (k in from.word until to.word) {
                val frac = if (span <= 0f) 0f else (cum[k] - cum[from.word]) / span
                starts[k] = (t0 + frac * (t1 - t0)).toInt() - p.leadMs
            }
        }
        return Timing(starts, speechStart, speechEnd, gaps)
    }
}

package com.tapreader.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechAlignTest {
    /** Synthetic "speech": a tone per word (length ∝ weight) and silences at chosen boundaries. */
    private fun synth(words: List<String>, pauses: Map<Int, Int>, msPerWeight: Int = 110): Pair<SpeechAlign.Pcm, IntArray> {
        val rate = 24000
        val samples = ArrayList<Short>()
        fun silence(ms: Int) = repeat(rate * ms / 1000) { samples += 0 }
        val starts = IntArray(words.size)
        silence(250)
        for ((i, w) in words.withIndex()) {
            pauses[i]?.let { silence(it) }
            starts[i] = samples.size * 1000 / rate
            val ms = (SpeechAlign.weight(w) * msPerWeight).toInt()
            val n = rate * ms / 1000
            for (k in 0 until n) samples += (8000 * kotlin.math.sin(k * 2 * Math.PI * 180 / rate)).toInt().toShort()
            silence(20)       // inter-word micro-gap, below the gap threshold
        }
        silence(300)
        return SpeechAlign.Pcm(samples.toShortArray(), rate) to starts
    }

    @Test fun anchorsPausesToPunctuation() {
        val text = "It was the best of times, it was the worst of times, it was the age of wisdom, it was the age of foolishness."
        val words = text.split(' ')
        // Pauses before words 6, 12 and 18 (after the commas) of very different lengths.
        val (pcm, truth) = synth(words, mapOf(6 to 450, 12 to 150, 18 to 700))
        val t = SpeechAlign.align(words, pcm)
        val err = words.indices.map { kotlin.math.abs(t.startsMs[it] - truth[it]) }
        println("max err ${err.max()} ms, mean ${err.average().toInt()} ms, gaps=${t.gaps}")
        assertTrue("max start error ${err.max()}ms", err.max() < 120)
        // The word under the cursor at each true midpoint is the right word.
        for (i in words.indices) {
            val mid = truth[i] + (SpeechAlign.weight(words[i]) * 110 / 2).toInt()
            assertEquals("word at $mid", i, t.wordAt(mid))
        }
    }

    @Test fun unpunctuatedPauseStillMatches() {
        val words = "and then quite suddenly the whole room fell silent before anyone spoke".split(' ')
        val (pcm, truth) = synth(words, mapOf(4 to 400))
        val t = SpeechAlign.align(words, pcm)
        assertTrue("word 4 start ${t.startsMs[4]} vs ${truth[4]}", kotlin.math.abs(t.startsMs[4] - truth[4]) < 60)
    }

    @Test fun wavRoundTrip() {
        val pcm = SpeechAlign.Pcm(ShortArray(2400) { (it % 100 - 50).toShort() }, 24000)
        val bytes = java.io.ByteArrayOutputStream().apply {
            fun le(v: Int, n: Int) { for (i in 0 until n) write((v shr (8 * i)) and 0xFF) }
            write("RIFF".toByteArray()); le(36 + 4800, 4); write("WAVE".toByteArray())
            write("fmt ".toByteArray()); le(16, 4); le(1, 2); le(1, 2); le(24000, 4); le(48000, 4); le(2, 2); le(16, 2)
            write("data".toByteArray()); le(4800, 4)
            for (s in pcm.samples) le(s.toInt(), 2)
        }.toByteArray()
        val back = SpeechAlign.parseWav(bytes)!!
        assertEquals(24000, back.rate)
        assertEquals(pcm.samples.toList(), back.samples.toList())
        assertEquals(100, back.durationMs)
    }
}

package com.tapreader.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogueTest {
    private fun words(vararg paragraphs: String): List<Word> {
        val out = ArrayList<Word>()
        for (p in paragraphs) p.split(' ').filter { it.isNotBlank() }.forEachIndexed { i, t -> out += Word(t, i == 0, 0) }
        return out
    }

    private fun spans(ws: List<Word>) = Dialogue.analyze(ws).spans.map { s -> ws.subList(s.startWord, s.endWord).joinToString(" ") { it.text } }

    @Test fun doubleQuotesWithInterruption() {
        val ws = words("“My dear Mr. Bennet,” said his lady to him one day, “have you heard that Netherfield Park is let at last?”",
            "Mr. Bennet replied that he had not.")
        assertEquals(listOf("“My dear Mr. Bennet,”", "“have you heard that Netherfield Park is let at last?”"), spans(ws))
    }

    @Test fun straightQuotesAndOneWordLines() {
        val ws = words("\"Yes,\" he said. \"I", "know.\" Then silence.")
        // Paragraph break closes the open span; "know.\" then closes nothing.
        assertEquals(listOf("\"Yes,\"", "\"I"), spans(ws))
    }

    @Test fun singleQuotesIgnoreApostrophes() {
        val ws = words(
            "‘I don’t know what you mean,’ said Alice, ‘and I can’t explain myself.’",
            "The Hatter’s remark seemed to have no sort of meaning in it.",
            "‘Take some more tea,’ the March Hare said. " + "‘It’s always six o’clock now.’ " + "‘x’ ".repeat(22))
        val s = spans(ws)
        assertEquals("‘I don’t know what you mean,’", s[0])
        assertEquals("‘and I can’t explain myself.’", s[1])
        assertEquals("‘Take some more tea,’", s[2])
        assertEquals("‘It’s always six o’clock now.’", s[3])
    }

    @Test fun multiParagraphSpeechReopens() {
        val ws = words("“It is a truth,", "“universally acknowledged.”")
        assertEquals(listOf("“It is a truth,", "“universally acknowledged.”"), spans(ws))
    }

    /** Structural sanity on every real test book: spans exist, stay inside paragraphs, and are plausible. */
    @Test fun realBooks() {
        for (f in TestBooks.files()) {
            val b = TestBooks.load(f)
            val d = Dialogue.analyze(b)
            val quoted = d.quoteOf.count { it >= 0 }
            val longest = d.spans.maxOfOrNull { it.endWord - it.startWord } ?: 0
            val single = Dialogue.usesSingleQuotes(b.words)
            println("[${f.name}] \"${b.title}\" words=${b.wordCount} chapters=${b.chapterStarts.size} spans=${d.spans.size} " +
                "quoted=${quoted * 100 / b.wordCount.coerceAtLeast(1)}% longest=$longest singleQuotes=$single")
            d.spans.filter { it.id % (d.spans.size / 4 + 1) == 3 }.take(4).forEach { s ->
                println("    Q${s.id}: ${TestBooks.text(b, s.startWord, s.endWord).take(110)}")
            }
            for (s in d.spans) for (k in s.startWord + 1 until s.endWord) assertTrue("span crosses paragraph in ${f.name}", !b.words[k].paragraphBreak)
            assertTrue("${f.name}: no dialogue found", d.spans.size > 50)
            // Long spans are real (letters, frame-story narration like The Time
            // Machine's); they are bounded by paragraphs, which is asserted above.
        }
    }

    @Test fun planKeepsVoicesApart() {
        val ws = words("“My dear Mr. Bennet,” said his lady to him one day, “have you heard that Netherfield Park is let at last?”")
        val b = Book("t", "t", "", ws, listOf(0), listOf("c"), "txt")
        val d = Dialogue.analyze(b)
        val plan = SpeechPlan.build(b, d, 0, b.wordCount)
        val parts = plan.map { u -> TestBooks.text(b, u.startWord, u.endWord) to u.quoteId }
        assertEquals(listOf(
            "“My dear Mr. Bennet,”" to 0,
            "said his lady to him one day," to -1,
            "“have you heard that Netherfield Park is let at last?”" to 1), parts)
    }
}

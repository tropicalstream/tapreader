package com.tapreader.app

/**
 * Finds the spoken lines in a book so each one can be voiced by its speaker.
 *
 * A quote span is a run of words between an opening and a closing quotation
 * mark. "Said he" interruptions split a line into two spans (both given the
 * same speaker by the cast director).
 *
 * Paragraph breaks need care. A real multi-paragraph speech leaves its mark
 * open and reopens with a fresh one on the next paragraph: that closes the
 * span, and the next span is recorded as its continuation (same speaker). But
 * some EPUBs break paragraphs mid-sentence (every printed line a paragraph);
 * closing the quote there handed the rest of a character's sentence to the
 * narrator — a voice change mid-quote. So an open quote survives a break
 * unless the next paragraph opens a new quote, or reading ahead finds an
 * opening mark before any closing one. A stray unmatched mark is still
 * contained: a span is capped at a few paragraphs.
 *
 * Books use either double quotes (“ ” or ") or single quotes (‘ ’) for speech;
 * the style is detected per book. With single quotes the closing ’ is also the
 * apostrophe, so a span is only closed by ’ at the END of a token (after any
 * trailing punctuation) — "don’t" inside a line never ends it.
 */
object Dialogue {
    /** [continues]: id of the span this one carries on (a speech over several paragraphs), or -1. */
    data class Span(val id: Int, val startWord: Int, val endWord: Int, val continues: Int = -1)   // endWord exclusive

    class Map(val quoteOf: IntArray, val spans: List<Span>) {
        fun spanAt(word: Int): Span? = quoteOf.getOrNull(word)?.takeIf { it >= 0 }?.let { spans[it] }
    }

    private const val OPEN_DOUBLE = "“\"«„"

    fun analyze(book: Book): Map = analyze(book.words)

    fun analyze(words: List<Word>): Map {
        val single = usesSingleQuotes(words)
        val opens = { t: String -> if (single) opensSingle(t) else opensDouble(t) }
        val quoteOf = IntArray(words.size) { -1 }
        val spans = ArrayList<Span>()
        var open = -1          // start word of the open span, or -1
        var openContinues = -1
        var breaksCrossed = 0
        var pendingContinuation = -1   // span id a speech-reopening next paragraph continues
        fun close(end: Int) {
            if (open >= 0 && end > open) {
                val id = spans.size
                spans.add(Span(id, open, end, openContinues))
                for (k in open until end) quoteOf[k] = id
            }
            open = -1; openContinues = -1; breaksCrossed = 0
        }
        /** At a paragraph break inside a quote: is the new paragraph still that quote? */
        fun stillInside(i: Int): Boolean {
            if (opens(words[i].text)) return false
            if (breaksCrossed >= 3 && i - open > 400) return false
            val prev = words[i - 1].text
            if (!TtsReader.endsSentence(prev) && !prev.endsWith(":") && !prev.endsWith("—")) return true   // broken mid-sentence
            for (k in i until minOf(i + 150, words.size)) {
                val t = words[k].text
                if (opens(t)) return false
                if (if (single) closesSingle(t, false) else closesDouble(t, false)) return true
            }
            return false
        }
        for ((i, w) in words.withIndex()) {
            if (w.paragraphBreak && open >= 0) {
                if (stillInside(i)) breaksCrossed++
                else { val reopening = opens(w.text); close(i); if (reopening) pendingContinuation = spans.size - 1 }
            }
            val t = w.text
            if (open < 0 && opens(t)) {
                open = i
                openContinues = if (w.paragraphBreak) pendingContinuation else -1
            }
            if (w.paragraphBreak) pendingContinuation = -1
            // A token can both open and close: “Yes,” / ‘No!’
            val closes = if (single) closesSingle(t, opened = open == i) else closesDouble(t, opened = open == i)
            if (open >= 0 && closes) close(i + 1)
        }
        if (open >= 0) close(words.size)
        return Map(quoteOf, spans)
    }

    /** Single-quote books have many ‘ at word starts and few “. */
    fun usesSingleQuotes(words: List<Word>): Boolean {
        var singles = 0; var doubles = 0
        for (w in words) {
            val t = w.text.trimStart('(', '—', '-', '[')
            if (t.startsWith("‘")) singles++
            if (t.startsWith("“") || t.startsWith("\"")) doubles++
        }
        return singles > 20 && singles > doubles * 3
    }

    private fun lead(t: String) = t.trimStart('(', '—', '–', '-', '[', '_', '*')
    private fun opensDouble(t: String): Boolean = lead(t).firstOrNull()?.let { it in OPEN_DOUBLE } == true
    private fun opensSingle(t: String): Boolean {
        val s = lead(t)
        // 'tis / 'em / ‘Tis-style elisions look like openers; accept them anyway —
        // they are far rarer than real openings and at worst narrate one word in
        // a character voice until the next closing mark or paragraph end.
        return s.startsWith("‘") || (s.startsWith("'") && s.length > 1 && s[1].isLetter())
    }

    private fun trail(t: String) = t.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '—', '–', '-', '_', '*')

    private fun closesDouble(t: String, opened: Boolean): Boolean {
        // On the opening token the first mark is the opener; "Yes," closes too.
        val body = if (opened) lead(t).drop(1) else t
        return body.any { it == '”' || it == '"' || it == '»' }
    }

    private fun closesSingle(t: String, opened: Boolean): Boolean {
        val body = if (opened) lead(t).drop(1) else t
        val s = trail(body)
        if (!(s.endsWith("’") || s.endsWith("'"))) return false
        // Plural possessives ("the boys’") are rare in speech next to closing
        // marks; only obvious dialect elisions (o’, an’, th’) are kept open.
        val core = s.dropLast(1).lowercase()
        return core !in setOf("o", "an", "th", "e", "nothin", "somethin", "goin", "doin")
    }
}

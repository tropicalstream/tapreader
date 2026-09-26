package com.tapreader.app

/**
 * Finds the spoken lines in a book so each one can be voiced by its speaker.
 *
 * A quote span is a run of words inside one paragraph that sits between an
 * opening and a closing quotation mark. "Said he" interruptions split a line
 * into two spans (both normally attributed to the same speaker by the cast
 * director). A paragraph break always closes an open quote: multi-paragraph
 * speeches reopen with a fresh mark on the next paragraph, and a stray
 * unmatched mark can then never swallow the rest of the book.
 *
 * Books use either double quotes (“ ” or ") or single quotes (‘ ’) for speech;
 * the style is detected per book. With single quotes the closing ’ is also the
 * apostrophe, so a span is only closed by ’ at the END of a token (after any
 * trailing punctuation) — "don’t" inside a line never ends it.
 */
object Dialogue {
    data class Span(val id: Int, val startWord: Int, val endWord: Int)   // endWord exclusive

    class Map(val quoteOf: IntArray, val spans: List<Span>) {
        fun spanAt(word: Int): Span? = quoteOf.getOrNull(word)?.takeIf { it >= 0 }?.let { spans[it] }
    }

    private const val OPEN_DOUBLE = "“\"«„"

    fun analyze(book: Book): Map = analyze(book.words)

    fun analyze(words: List<Word>): Map {
        val single = usesSingleQuotes(words)
        val quoteOf = IntArray(words.size) { -1 }
        val spans = ArrayList<Span>()
        var open = -1          // start word of the open span, or -1
        fun close(end: Int) {
            if (open >= 0 && end > open) {
                val id = spans.size
                spans.add(Span(id, open, end))
                for (k in open until end) quoteOf[k] = id
            }
            open = -1
        }
        for ((i, w) in words.withIndex()) {
            if (w.paragraphBreak && open >= 0) close(i)
            val t = w.text
            val opens = if (single) opensSingle(t) else opensDouble(t)
            if (open < 0 && opens) open = i
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

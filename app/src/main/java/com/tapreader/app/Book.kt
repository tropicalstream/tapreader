package com.tapreader.app

/**
 * A parsed document reduced to what the reader engine needs: a flat list of
 * words plus paragraph/chapter boundaries expressed as word indices, so the
 * three view modes (paged, autoscroll, single-word RSVP) can all address the
 * same stream and stay in sync with TTS.
 */
data class Book(
    val id: String,
    val title: String,
    val author: String,
    val words: List<Word>,
    /** Start word-index of each chapter, ascending; first is 0. */
    val chapterStarts: List<Int>,
    val chapterTitles: List<String>,
    val format: String
) {
    val wordCount: Int get() = words.size

    fun chapterAt(wordIndex: Int): Int {
        var lo = 0; var hi = chapterStarts.size - 1; var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (chapterStarts[mid] <= wordIndex) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

/**
 * One token. [text] is what's shown/spoken. [paragraphBreak] marks the first
 * word of a new paragraph (used for layout and TTS phrasing). [charStart] is the
 * offset in the reconstructed plain text, so TTS timing can be mapped back to
 * word highlight precisely.
 */
data class Word(
    val text: String,
    val paragraphBreak: Boolean,
    val charStart: Int
)

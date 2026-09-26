package com.tapreader.app

import android.content.Context
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Turns a document file into a [Book]. Supported directly: .txt, .md, .html/.htm,
 * .xml, .epub, .pdf, .fb2, .rtf, .docx, and anything we can read as UTF-8 text.
 * EPUB, FB2 and DOCX are unzipped/de-tagged natively (no heavy deps); PDF text is
 * extracted via the PdfBox-Android port. (.doc — the pre-2007 binary format — is
 * not supported.)
 */
object DocumentParser {
    private const val TAG = "TapReader"
    private val WHITESPACE = Regex("\\s+")
    private val NUMERIC_ENTITY = Regex("&#(\\d+);")

    val SUPPORTED = setOf(
        "txt", "text", "md", "markdown", "log", "csv",
        "html", "htm", "xhtml", "xml",
        "epub", "fb2", "pdf", "rtf", "docx"
    )

    /** The [Book.id] a file parses to (casts are stored under it). */
    fun bookIdFor(fileName: String): String =
        fileName.substringBeforeLast('.').replace('_', ' ').trim().hashCode().toString()

    fun isSupported(name: String): Boolean =
        SUPPORTED.contains(name.substringAfterLast('.', "").lowercase())

    fun parse(context: Context?, file: File): Book {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        val fallbackTitle = file.nameWithoutExtension.replace('_', ' ').trim()
        return when (ext) {
            "pdf" -> parsePdf(requireNotNull(context) { "PDF parsing needs a Context" }, file, fallbackTitle)
            "epub" -> parseEpub(file, fallbackTitle)
            "docx" -> parseDocx(file, fallbackTitle)
            "fb2" -> fromText(stripTags(file.readText(Charsets.UTF_8)), fallbackTitle, "", "fb2")
            "html", "htm", "xhtml", "xml" -> fromText(stripTags(file.readText(Charsets.UTF_8)), fallbackTitle, "", ext)
            "rtf" -> fromText(stripRtf(file.readText(Charsets.ISO_8859_1)), fallbackTitle, "", "rtf")
            else -> fromText(file.readText(Charsets.UTF_8), fallbackTitle, "", ext.ifEmpty { "txt" })
        }
    }

    // ---- Plain text -> Book ------------------------------------------------

    /**
     * Tokenize plain text into words with paragraph boundaries and chapter
     * detection. Chapters are inferred from blank-line-separated headings that
     * look like "Chapter N", roman numerals, or all-caps short lines.
     */
    /**
     * [numberSequence] carries the expected next bare chapter number across calls,
     * so an EPUB split over several files keeps counting (1 … 35) instead of
     * restarting at 1 in each file.
     */
    fun fromText(raw: String, title: String, author: String, format: String, numberSequence: IntArray = intArrayOf(1)): Book {
        // Non-breaking and typographic spaces are word gaps too (Java's \s misses
        // them, which glued words together in some EPUBs and broke quote pairing).
        val text = stripGutenbergBoilerplate(raw).replace("\r\n", "\n").replace('\r', '\n')
            .replace(Regex("[\u00A0\u2000-\u200A\u202F\u205F\u3000]"), " ")
        val words = ArrayList<Word>(text.length / 5)
        val chapterStarts = ArrayList<Int>()
        val chapterTitles = ArrayList<String>()
        chapterStarts.add(0); chapterTitles.add("Beginning")

        val lines = text.split('\n')
        var atParagraphStart = true
        var pendingBlank = true
        var i = 0
        // Bare chapter numbers ("1" / "IV." alone between blank lines) count only
        // while they run in sequence, so a stray number in the text is never a chapter.
        for ((lineIdx, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) { atParagraphStart = true; pendingBlank = true; continue }

            val number = if (pendingBlank && lines.getOrNull(lineIdx + 1)?.isBlank() != false) bareNumber(trimmed) else null
            if (number != null && number == numberSequence[0]) {
                numberSequence[0]++
                if (words.isEmpty()) chapterTitles[0] = "Chapter $number"
                else { chapterStarts.add(words.size); chapterTitles.add("Chapter $number") }
            } else if (pendingBlank && looksLikeHeading(trimmed) && words.isNotEmpty()) {
                chapterStarts.add(words.size)
                chapterTitles.add(trimmed.take(60))
            }
            pendingBlank = false

            // Some EPUBs break paragraphs mid-sentence (every printed line its own
            // paragraph). A "paragraph" that starts in lowercase right after one
            // that did not finish a sentence is the same paragraph continuing.
            if (atParagraphStart && words.isNotEmpty() && trimmed[0].isLowerCase()) {
                val prev = words.last().text
                if (!TtsReader.endsSentence(prev) && !prev.endsWith(":") && !prev.endsWith("”") && !prev.endsWith("\"")) atParagraphStart = false
            }
            var first = true
            for (tok in trimmed.split(Regex("\\s+"))) {
                if (tok.isEmpty()) continue
                val cs = if (words.isEmpty()) 0 else words.last().charStart + words.last().text.length + 1
                words.add(Word(tok, paragraphBreak = atParagraphStart && first, charStart = cs))
                first = false
                atParagraphStart = false
            }
            i++
        }
        return Book(
            id = title.hashCode().toString(),
            title = title.ifBlank { "Untitled" },
            author = author,
            words = words,
            chapterStarts = if (chapterStarts.size > 1) chapterStarts else listOf(0),
            chapterTitles = if (chapterStarts.size > 1) chapterTitles else listOf("Full text"),
            format = format
        )
    }

    /**
     * Project Gutenberg plain-text files wrap the actual book between
     * "*** START OF … ***" and "*** END OF … ***" markers, with license
     * boilerplate outside. Keep only what's between them. No-op for other text.
     */
    private fun stripGutenbergBoilerplate(text: String): String {
        var t = text
        val start = Regex("\\*\\*\\*\\s*START OF TH(?:E|IS)[^*]*\\*\\*\\*",
            setOf(RegexOption.IGNORE_CASE)).find(t)
        if (start != null) t = t.substring(start.range.last + 1)
        val end = Regex("\\*\\*\\*\\s*END OF TH(?:E|IS)[^*]*\\*\\*\\*",
            setOf(RegexOption.IGNORE_CASE)).find(t)
        if (end != null) t = t.substring(0, end.range.first)
        return t.trim()
    }

    private val HEADING = Regex(
        "^(chapter|book|part|section|canto|volume)\\s+[0-9ivxlcdm]+.*$",
        RegexOption.IGNORE_CASE
    )

    /** "12", "12.", "XII", "XII." → its value; anything else null. */
    private fun bareNumber(line: String): Int? {
        val t = line.trimEnd('.')
        if (t.length in 1..3 && t.all { it.isDigit() }) return t.toInt()
        if (t.length in 1..7 && ROMAN.matches(t)) {
            val v = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
            var total = 0
            for (k in t.indices) { val c = v.getValue(t[k]); val n = if (k + 1 < t.length) v.getValue(t[k + 1]) else 0; total += if (c < n) -c else c }
            return total
        }
        return null
    }

    private fun looksLikeHeading(line: String): Boolean {
        if (line.length > 64) return false
        if (HEADING.matches(line)) return true
        // Short all-caps line (e.g. "PROLOGUE", "THE END").
        if (line.length in 3..40 && line == line.uppercase() && line.any { it.isLetter() }) return true
        return false
    }

    // ---- PDF ---------------------------------------------------------------

    private fun parsePdf(context: Context, file: File, title: String): Book {
        return try {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(file).use { doc ->
                val meta = doc.documentInformation
                val stripper = PDFTextStripper().apply { paragraphStart = "\n\n" }
                val text = stripper.getText(doc)
                fromText(
                    text,
                    (meta?.title?.takeIf { it.isNotBlank() }) ?: title,
                    meta?.author.orEmpty(),
                    "pdf"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "pdf parse failed: ${e.message}")
            fromText("Could not read this PDF.\n\n${e.message}", title, "", "pdf")
        }
    }

    // ---- EPUB --------------------------------------------------------------

    /**
     * EPUB is a ZIP of (X)HTML documents. We concatenate the spine documents in
     * archive order (good enough for the vast majority of books), strip tags,
     * and treat each document as a chapter boundary.
     */
    /** One entry of an EPUB's own table of contents. */
    private class TocEntry(val index: Int, val label: String, val file: String, val frag: String?, val depth: Int) {
        var position = -1
    }

    private const val MARK = '\u0001'

    private fun parseEpub(file: File, fallbackTitle: String): Book {
        val docs = ArrayList<Pair<String, String>>() // name -> raw html
        var metaTitle = ""
        var metaAuthor = ""
        var opfXml: String? = null
        var ncxXml: String? = null
        try {
            ZipInputStream(file.inputStream().buffered()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val name = entry.name.lowercase()
                    if (!entry.isDirectory) {
                        if (name.endsWith(".opf")) {
                            val opf = zin.readBytes().toString(Charsets.UTF_8)
                            opfXml = opf
                            metaTitle = extractTag(opf, "dc:title") ?: extractTag(opf, "title").orEmpty()
                            metaAuthor = extractTag(opf, "dc:creator") ?: extractTag(opf, "creator").orEmpty()
                        } else if (name.endsWith(".ncx")) {
                            ncxXml = zin.readBytes().toString(Charsets.UTF_8)
                        } else if (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")) {
                            docs.add(entry.name to zin.readBytes().toString(Charsets.UTF_8))
                        }
                    }
                    entry = zin.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "epub parse failed: ${e.message}")
        }
        // Reading order comes from the OPF spine — the book's own sequence.
        // Plain alphabetical sorting scrambles it ("chapter-10" before
        // "chapter-2"). Natural numeric comparison is the fallback for EPUBs with
        // no usable spine. Documents outside a spine (the nav file) are not read.
        val spine = opfXml?.let(::spineOrder) ?: emptyMap()
        val navDoc = docs.firstOrNull { (_, html) -> Regex("<nav[^>]*epub:type=\"toc\"").containsMatchIn(html) }
        val reading = docs.filter { spine.isEmpty() || spine.containsKey(baseName(it.first)) }
            .ifEmpty { docs }
            .sortedWith(compareBy<Pair<String, String>> { spine[baseName(it.first)] ?: Int.MAX_VALUE }
                .thenComparator { x, y -> naturalCompare(x.first, y.first) })
        if (reading.isEmpty()) return fromText("Could not read this EPUB.", fallbackTitle, "", "epub")

        // The book's own table of contents: EPUB 3 nav (its "toc" list only — not
        // the page list or landmarks), else the EPUB 2 NCX.
        val toc = navDoc?.second?.let(::navEntries)?.takeIf { it.size >= 2 } ?: ncxXml?.let(::ncxEntries) ?: emptyList()
        val byFile = toc.groupBy { it.file }

        // Build the words. Each TOC target gets a marker token placed just before
        // the element it points at; the marker's position becomes the chapter
        // start and the marker itself is dropped, so the words are unchanged.
        val words = ArrayList<Word>()
        val fileStarts = ArrayList<Int>(); val fileTitles = ArrayList<String>()
        val headStarts = ArrayList<Int>(); val headTitles = ArrayList<String>()
        val pending = ArrayList<TocEntry>()
        var carryBreak = false
        val numberSequence = intArrayOf(1)
        for ((idx, doc) in reading.withIndex()) {
            val plain = stripTags(withMarkers(doc.second, byFile[baseName(doc.first)].orEmpty()))
            val chBook = fromText(plain, "c", "", "epub", numberSequence)
            if (chBook.words.none { it.text[0] != MARK }) { pending += markerEntries(chBook, toc); continue }
            fileStarts.add(words.size)
            fileTitles.add(guessChapterTitle(plain.replace(Regex("${MARK}T\\d+$MARK"), ""), idx))
            val headingAt = chBook.chapterStarts.drop(1).zip(chBook.chapterTitles.drop(1)).toMap()
            val base = if (words.isEmpty()) 0 else words.last().charStart + words.last().text.length + 2
            for ((k, w) in chBook.words.withIndex()) {
                if (w.text.length > 2 && w.text[0] == MARK) {
                    w.text.trim(MARK).removePrefix("T").toIntOrNull()?.let { n -> toc.getOrNull(n)?.let { pending += it } }
                    carryBreak = carryBreak || w.paragraphBreak
                    continue
                }
                headingAt[k]?.let { t -> headStarts.add(words.size); headTitles.add(t) }
                for (e in pending) e.position = words.size
                pending.clear()
                words.add(w.copy(charStart = base + w.charStart, paragraphBreak = w.paragraphBreak || carryBreak))
                carryBreak = false
            }
        }
        val (starts, titles) = tocChapters(toc)
            ?: headingChapters(headStarts, headTitles)
            ?: (fileStarts.ifEmpty { listOf(0) } to fileTitles.ifEmpty { listOf("Full text") })
        return Book(
            id = fallbackTitle.hashCode().toString(),
            title = metaTitle.ifBlank { fallbackTitle },
            author = metaAuthor,
            words = words,
            chapterStarts = starts,
            chapterTitles = titles,
            format = "epub"
        )
    }

    private fun markerEntries(chBook: Book, toc: List<TocEntry>): List<TocEntry> =
        chBook.words.mapNotNull { w -> w.text.trim(MARK).removePrefix("T").toIntOrNull()?.let { toc.getOrNull(it) } }

    /** Inserts " ␁T<n>␁ " before the element each entry targets (file start when it has no fragment). */
    private fun withMarkers(html: String, entries: List<TocEntry>): String {
        if (entries.isEmpty()) return html
        val bodyStart = Regex("(?i)<body[^>]*>").find(html)?.range?.last?.plus(1) ?: 0
        val inserts = entries.map { e ->
            val at = e.frag?.let { f ->
                Regex("\\b(?:id|name)=[\"']${Regex.escape(f)}[\"']").find(html)?.range?.first?.let { html.lastIndexOf('<', it) }
            }?.takeIf { it >= 0 } ?: bodyStart
            maxOf(at, bodyStart) to " ${MARK}T${e.index}$MARK "
        }.sortedByDescending { it.first }
        val sb = StringBuilder(html)
        for ((at, text) in inserts) sb.insert(at, text)
        return sb.toString()
    }

    private fun navEntries(html: String): List<TocEntry> {
        val m = Regex("(?is)<nav[^>]*epub:type=\"toc\"[^>]*>(.*?)</nav>").find(html) ?: return emptyList()
        val out = ArrayList<TocEntry>()
        var depth = 0
        for (t in Regex("(?is)<ol[^>]*>|</ol>|<a\\s[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>").findAll(m.groupValues[1])) {
            when {
                t.value.startsWith("</ol", true) -> depth--
                t.value.startsWith("<ol", true) -> depth++
                else -> tocEntry(out, t.groupValues[1], t.groupValues[2], depth)
            }
        }
        return out
    }

    private fun ncxEntries(xml: String): List<TocEntry> {
        val out = ArrayList<TocEntry>()
        var depth = 0
        var label = ""
        for (t in Regex("(?is)<navPoint\\b|</navPoint>|<text>(.*?)</text>|<content[^>]*src=\"([^\"]+)\"").findAll(xml)) {
            when {
                t.value.startsWith("</navPoint", true) -> depth--
                t.value.startsWith("<navPoint", true) -> depth++
                t.value.startsWith("<text", true) -> label = t.groupValues[1]
                else -> tocEntry(out, t.groupValues[2], label, depth)
            }
        }
        return out
    }

    private fun tocEntry(out: MutableList<TocEntry>, href: String, rawLabel: String, depth: Int) {
        val path = java.net.URLDecoder.decode(href.substringBefore('#'), "UTF-8")
        val frag = href.substringAfter('#', "").ifBlank { null }
        val label = cleanLabel(rawLabel)
        if (label.isBlank() || path.isBlank()) return
        // Page-number anchors some contents files list ("{vii}", "[Pg 2][Pg 3]", "12") are not chapters.
        if (PAGE_LABEL.matches(label)) return
        out += TocEntry(out.size, label, baseName(path), frag, depth)
    }

    /** Page anchors: bracketed ("{vii}", "[Pg 2][Pg 3]") or "Page 12" — never a bare numeral, which may be a real part. */
    private val PAGE_LABEL = Regex("(?i)^(\\s*([\\[{(]\\s*(pg\\.?|page|p\\.)?\\s*[0-9ivxlcdm]+\\s*[\\]})]|(pg\\.?|page)\\s*[0-9]+))+$")
    private val CHAPTER_WORD = Regex("(?i)\\b(chapter|book|part|volume|canto|act|letter|section)\\s+([0-9]+|[ivxlcdm]+)\\b")
    private val ROMAN = Regex("^(?=[IVXLCDM])M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})[.,:]?$")
    private val SMALL = setOf("a", "an", "and", "as", "at", "by", "for", "in", "of", "on", "or", "the", "to", "with")

    /**
     * A contents label as a reader wants it: tags and entities gone, a picture
     * caption in front of the chapter name dropped ("He rode a black horse.
     * CHAPTER III." → "Chapter III"), and SHOUTING turned to title case with
     * roman numerals kept.
     */
    fun cleanLabel(raw: String): String {
        var t = decodeEntities(raw.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()
        CHAPTER_WORD.find(t)?.let { if (it.range.first > 0) t = t.substring(it.range.first) }
        t = t.trim().trimEnd('.', ',', ':', ';').trim()
        val letters = t.filter { it.isLetter() }
        // Shouting, even with a stray lowercase joiner ("PRIDE. and PREJUDICE").
        if (letters.length > 2 && letters.count { it.isUpperCase() } >= letters.length * 3 / 4) {
            t = t.split(' ').mapIndexed { i, w ->
                when {
                    ROMAN.matches(w) -> w
                    i > 0 && w.lowercase() in SMALL && !ROMAN.matches(t.split(' ')[i - 1]) -> w.lowercase()
                    else -> w.lowercase().replaceFirstChar { it.titlecase() }
                }
            }.joinToString(" ")
        } else {
            // "CHAPTER 1. Loomings" → "Chapter 1. Loomings"
            t = t.replace(Regex("^(CHAPTER|BOOK|PART|VOLUME|CANTO|ACT|LETTER|SECTION)\\b")) { it.value.lowercase().replaceFirstChar { c -> c.titlecase() } }
        }
        return t.take(80)
    }

    private val NAVIGATION_ONLY = Regex("(?i)^(cover|contents|table of contents|toc|landmarks|page list|start)$")

    /**
     * Chapter list from the book's own contents, when it resolved: navigation
     * entries (Cover, Contents) dropped, sub-entries that are bare numerals named
     * with their parent ("A Scandal in Bohemia · II"), and entries at the same
     * spot collapsed to the most specific. Always starts at word 0.
     */
    private fun tocChapters(toc: List<TocEntry>): Pair<List<Int>, List<String>>? {
        if (toc.size < 2) return null
        val parents = HashMap<Int, String>()
        val named = toc.filter { it.position >= 0 }.map { e ->
            parents[e.depth] = e.label
            val bare = ROMAN.matches(e.label) || e.label.trimEnd('.').all { it.isDigit() }
            val label = if (bare && e.depth > 1) parents[e.depth - 1]?.let { "$it · ${e.label}" } ?: e.label else e.label
            e.position to label
        }
        if (named.size < 2 || named.size < toc.size / 3) return null
        val kept = named.filterIndexed { i, (_, l) -> i == 0 || !NAVIGATION_ONLY.matches(l) }
            .sortedBy { it.first }
        val collapsed = ArrayList<Pair<Int, String>>()
        for (e in kept) {
            // Same spot: keep the most specific (later) label — except at the very
            // start, where Cover / Title Page / Copyright pile up; keep the first.
            if (collapsed.isNotEmpty() && collapsed.last().first == e.first) { if (e.first > 0) collapsed[collapsed.size - 1] = e }
            else collapsed += e
        }
        if (collapsed.size < 2) return null
        if (collapsed[0].first > 0) collapsed.add(0, 0 to "Beginning")
        val first = collapsed[0]
        if (NAVIGATION_ONLY.matches(first.second)) collapsed[0] = 0 to "Beginning"
        else if (first.first != 0) collapsed[0] = 0 to first.second
        return collapsed.map { it.first } to collapsed.map { it.second }
    }

    /** Chapter list from headings found in the text ("Chapter 1"), for EPUBs without a usable contents. */
    private fun headingChapters(starts: List<Int>, titles: List<String>): Pair<List<Int>, List<String>>? {
        if (starts.size < 2) return null
        val s = ArrayList(starts); val t = ArrayList(titles.map(::cleanLabel))
        if (s[0] > 0) { s.add(0, 0); t.add(0, "Beginning") }
        return s to t
    }

    // ---- DOCX --------------------------------------------------------------

    /**
     * DOCX is a ZIP holding word/document.xml. Paragraphs are <w:p> elements,
     * their text lives in <w:t> runs, and Word's outline structure is carried by
     * paragraph styles: Heading1..9 / Title become chapter boundaries, so the
     * document's table of contents appears in the reader's 📑 ToC menu. The
     * rendered TOC-page paragraphs themselves (styles TOC1..9, TOCHeading) are
     * skipped — reading a page of dotted leader lines aloud helps no one.
     */
    private fun parseDocx(file: File, fallbackTitle: String): Book {
        var docXml: String? = null
        var coreXml: String? = null
        try {
            ZipInputStream(file.inputStream().buffered()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        "word/document.xml" -> docXml = zin.readBytes().toString(Charsets.UTF_8)
                        "docProps/core.xml" -> coreXml = zin.readBytes().toString(Charsets.UTF_8)
                    }
                    entry = zin.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "docx read failed: ${e.message}")
        }
        val xml = docXml ?: return fromText("Could not read this DOCX file.", fallbackTitle, "", "docx")
        val title = coreXml?.let { extractTag(it, "dc:title") }.orEmpty().ifBlank { fallbackTitle }
        val author = coreXml?.let { extractTag(it, "dc:creator") }.orEmpty()

        // Single-pass indexOf scanner. An OCR-heavy book can hold hundreds of
        // thousands of tiny <w:t> runs; regex-per-paragraph parsing took ~1 min
        // on the glasses' SoC for an 86k-word file, this takes a few seconds.
        val words = ArrayList<Word>(xml.length / 40)
        val starts = ArrayList<Int>()
        val titles = ArrayList<String>()
        val sb = StringBuilder(512)
        var pos = 0
        while (true) {
            val pStart = xml.indexOf("<w:p", pos)
            if (pStart < 0) break
            val marker = xml.getOrNull(pStart + 4)
            if (marker != ' ' && marker != '>') { pos = pStart + 4; continue }   // <w:pPr, <w:pict …
            val pEnd = xml.indexOf("</w:p>", pStart)
            if (pEnd < 0) break
            pos = pEnd + 6

            var style = ""
            val styleAt = xml.indexOf("<w:pStyle", pStart)
            if (styleAt in pStart until pEnd) {
                val valAt = xml.indexOf("w:val=\"", styleAt)
                if (valAt in styleAt until pEnd) {
                    val valEnd = xml.indexOf('"', valAt + 7)
                    if (valEnd in valAt until pEnd) style = xml.substring(valAt + 7, valEnd)
                }
            }
            if (style.startsWith("TOC", ignoreCase = true)) continue   // rendered contents page

            sb.setLength(0)
            var runAt = pStart
            while (true) {
                val tAt = xml.indexOf("<w:t", runAt)
                if (tAt < 0 || tAt >= pEnd) break
                when (xml.getOrNull(tAt + 4)) {
                    '>', ' ' -> {   // a text run, possibly with xml:space attr
                        val open = xml.indexOf('>', tAt)
                        val close = xml.indexOf("</w:t>", open)
                        if (open < 0 || close < 0 || close > pEnd) break
                        sb.append(xml, open + 1, close)
                        runAt = close + 6
                        continue
                    }
                    'a' -> if (xml.startsWith("<w:tab", tAt)) sb.append(' ')   // <w:tab/>
                }
                runAt = tAt + 4
            }
            // <w:br/> line breaks inside runs never reach sb (they sit between
            // <w:t> tags), so only entity decoding and trimming remain.
            var text = sb.toString()
            if ('&' in text) text = decodeEntities(text)
            text = text.trim()
            if (text.isEmpty()) continue

            if (style.startsWith("Heading", ignoreCase = true) || style.equals("Title", ignoreCase = true)) {
                starts.add(words.size)
                titles.add(text.replace(WHITESPACE, " ").take(60))
            }
            var first = true
            for (tok in text.split(WHITESPACE)) {
                if (tok.isEmpty()) continue
                val cs = if (words.isEmpty()) 0 else words.last().charStart + words.last().text.length + 1
                words.add(Word(tok, paragraphBreak = first, charStart = cs))
                first = false
            }
        }
        if (words.isEmpty()) return fromText("This DOCX file contains no readable text.", fallbackTitle, "", "docx")
        if (starts.isEmpty() || starts.first() != 0) { starts.add(0, 0); titles.add(0, "Beginning") }
        return Book(
            id = fallbackTitle.hashCode().toString(),
            title = title,
            author = author,
            words = words,
            chapterStarts = starts,
            chapterTitles = titles,
            format = "docx"
        )
    }

    /** OPF manifest (id → href) joined with the spine (idref order) → basename → position. */
    private fun spineOrder(opf: String): Map<String, Int> {
        val hrefById = HashMap<String, String>()
        for (m in Regex("<item\\s[^>]*>").findAll(opf)) {
            val tag = m.value
            val id = Regex("\\bid=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: continue
            val href = Regex("\\bhref=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: continue
            hrefById[id] = href
        }
        val order = HashMap<String, Int>()
        var index = 0
        for (m in Regex("<itemref[^>]*\\bidref=\"([^\"]+)\"").findAll(opf)) {
            val href = hrefById[m.groupValues[1]] ?: continue
            order.putIfAbsent(baseName(href), index++)
        }
        return order
    }

    private fun baseName(path: String): String =
        path.substringAfterLast('/').substringBefore('#').substringBefore('?').lowercase()

    /** Compare with embedded numbers as numbers, so "ch-2" sorts before "ch-10". */
    private fun naturalCompare(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var i2 = i; while (i2 < a.length && a[i2].isDigit()) i2++
                var j2 = j; while (j2 < b.length && b[j2].isDigit()) j2++
                val na = a.substring(i, i2).trimStart('0')
                val nb = b.substring(j, j2).trimStart('0')
                val c = if (na.length != nb.length) na.length - nb.length else na.compareTo(nb)
                if (c != 0) return c
                i = i2; j = j2
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    private fun guessChapterTitle(plain: String, idx: Int): String {
        val firstLine = plain.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (firstLine.length in 2..50) firstLine else "Chapter ${idx + 1}"
    }

    // ---- Tag / markup stripping -------------------------------------------

    private fun stripTags(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?i)</(p|div|br|h[1-6]|li|tr|section|article)\\s*>"), "\n\n")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        // Headings set as images ("<h1><img alt="Chapter 1"></h1>") keep their title.
        s = s.replace(Regex("(?i)<img[^>]*\\balt=\"((?:chapter|part|book|prologue|epilogue|interlude|act)\\b[^\"]{0,40})\"[^>]*>")) { " ${it.groupValues[1]} " }
        // Inline tags join, not split: a drop cap is often "“W<i>hat’s" or
        // "<span class="dropcap">I</span>T is", which must read "What’s" / "IT is".
        s = s.replace(Regex("(?i)</?(i|b|em|strong|span|a|small|u|s|font|abbr|cite|mark|q)(\\s[^>]*)?/?>"), "")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = decodeEntities(s)
        s = s.replace(Regex("[ \\t]+"), " ")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    private fun decodeEntities(s: String): String = if ('&' !in s) s else s
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&apos;", "'").replace("&mdash;", "—").replace("&ndash;", "–")
        .replace("&hellip;", "…").replace("&rsquo;", "'").replace("&lsquo;", "'")
        .replace("&ldquo;", "“").replace("&rdquo;", "”")
        .replace(NUMERIC_ENTITY) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }

    private fun stripRtf(rtf: String): String {
        var s = rtf.replace(Regex("\\\\'[0-9a-fA-F]{2}"), "")
        s = s.replace(Regex("\\\\par[d]?", RegexOption.IGNORE_CASE), "\n\n")
        s = s.replace(Regex("\\\\[a-zA-Z]+-?[0-9]* ?"), "")
        s = s.replace("{", "").replace("}", "")
        return s.trim()
    }

    private fun extractTag(xml: String, tag: String): String? =
        Regex("<$tag[^>]*>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(xml)?.groupValues?.get(1)?.let { decodeEntities(it).trim() }?.takeIf { it.isNotBlank() }
}

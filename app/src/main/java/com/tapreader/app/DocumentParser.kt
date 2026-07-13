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

    fun isSupported(name: String): Boolean =
        SUPPORTED.contains(name.substringAfterLast('.', "").lowercase())

    fun parse(context: Context, file: File): Book {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        val fallbackTitle = file.nameWithoutExtension.replace('_', ' ').trim()
        return when (ext) {
            "pdf" -> parsePdf(context, file, fallbackTitle)
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
    fun fromText(raw: String, title: String, author: String, format: String): Book {
        val text = stripGutenbergBoilerplate(raw).replace("\r\n", "\n").replace('\r', '\n')
        val words = ArrayList<Word>(text.length / 5)
        val chapterStarts = ArrayList<Int>()
        val chapterTitles = ArrayList<String>()
        chapterStarts.add(0); chapterTitles.add("Beginning")

        val lines = text.split('\n')
        var atParagraphStart = true
        var pendingBlank = true
        var i = 0
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) { atParagraphStart = true; pendingBlank = true; continue }

            if (pendingBlank && looksLikeHeading(trimmed) && words.isNotEmpty()) {
                chapterStarts.add(words.size)
                chapterTitles.add(trimmed.take(60))
            }
            pendingBlank = false

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
    private fun parseEpub(file: File, fallbackTitle: String): Book {
        val chapters = ArrayList<Pair<String, String>>() // name -> plain text
        var metaTitle = ""
        var metaAuthor = ""
        try {
            ZipInputStream(file.inputStream().buffered()).use { zin ->
                var entry = zin.nextEntry
                val docs = ArrayList<Pair<String, String>>()
                var opfXml: String? = null
                while (entry != null) {
                    val name = entry.name.lowercase()
                    if (!entry.isDirectory) {
                        if (name.endsWith(".opf")) {
                            val opf = zin.readBytes().toString(Charsets.UTF_8)
                            opfXml = opf
                            metaTitle = extractTag(opf, "dc:title") ?: extractTag(opf, "title").orEmpty()
                            metaAuthor = extractTag(opf, "dc:creator") ?: extractTag(opf, "creator").orEmpty()
                        } else if (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")) {
                            val html = zin.readBytes().toString(Charsets.UTF_8)
                            docs.add(entry.name to stripTags(html))
                        }
                    }
                    entry = zin.nextEntry
                }
                // Reading order comes from the OPF spine — the book's own sequence.
                // Plain alphabetical sorting scrambles it ("chapter-10" before
                // "chapter-2"), which shuffled the ToC. Natural numeric comparison
                // is the fallback for EPUBs with no usable spine.
                val spine = opfXml?.let(::spineOrder) ?: emptyMap()
                docs.sortWith(
                    compareBy<Pair<String, String>> { spine[baseName(it.first)] ?: Int.MAX_VALUE }
                        .thenComparator { a, b -> naturalCompare(a.first, b.first) }
                )
                chapters.addAll(docs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "epub parse failed: ${e.message}")
        }
        if (chapters.isEmpty()) return fromText("Could not read this EPUB.", fallbackTitle, "", "epub")

        // Build a combined Book with real chapter boundaries.
        val words = ArrayList<Word>()
        val starts = ArrayList<Int>()
        val titles = ArrayList<String>()
        for ((idx, ch) in chapters.withIndex()) {
            val chBook = fromText(ch.second, "c", "", "epub")
            if (chBook.words.isEmpty()) continue
            starts.add(words.size)
            titles.add(guessChapterTitle(ch.second, idx))
            val base = if (words.isEmpty()) 0 else words.last().charStart + words.last().text.length + 2
            for (w in chBook.words) {
                words.add(w.copy(charStart = base + w.charStart))
            }
        }
        if (starts.isEmpty()) { starts.add(0); titles.add("Full text") }
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

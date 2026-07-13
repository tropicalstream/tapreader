package com.tapreader.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * Native reader. One [Book] word-stream, three view modes that all track a
 * single [focusIndex] — the word currently being read. When TTS plays it drives
 * focusIndex word-for-word (perfect highlight sync); otherwise an internal pacer
 * advances it at the configured WPM / scroll speed. The highlighted word is the
 * shared anchor, so switching modes never loses your place.
 *
 *   MODE_PAGED      classic page of text; the focus word is highlighted and the
 *                   page turns when focus reaches the bottom.
 *   MODE_AUTOSCROLL text scrolls smoothly to keep the focus line in a reading
 *                   band; highlight follows speech.
 *   MODE_RSVP       one word at a time, centered, with the Optimal Recognition
 *                   Point letter tinted — the "single word at a time" method.
 */
class ReaderView(context: Context) : View(context) {

    companion object {
        const val MODE_PAGED = 0
        const val MODE_AUTOSCROLL = 1
        const val MODE_RSVP = 2
        // Comfortable reading band = inner 30% of the display height (the waveguide
        // is clearest/easiest on the eyes near the vertical center).
        private const val COMFORT_TOP = 0.35f
        private const val COMFORT_BOTTOM = 0.65f
    }

    // ---- Public state ------------------------------------------------------

    var onProgress: ((wordIndex: Int) -> Unit)? = null
    /** Fired only while the reading engine advances focus, never for navigation. */
    var onWordRead: ((wordIndex: Int) -> Unit)? = null
    var onFinished: (() -> Unit)? = null

    private var book: Book? = null
    var mode = MODE_PAGED
        set(value) {
            if (field == value) return
            field = value
            // Entering a reading mode never auto-runs: the pacer waits for an
            // explicit tap. (It used to keep playing across the switch, which both
            // surprised the reader and silently swallowed edge-scrub gestures.)
            if (!ttsDriven) pause()
            relayout()
            scrollY = targetScrollForFocus(); targetScrollY = scrollY
            invalidate()
        }
    var isPlaying = false
        private set
    /** When true, [focusIndex] is set externally by TTS; the pacer stays off. */
    var ttsDriven = false
    /** Global option: pin the focus word at dead center instead of the comfort band. */
    var deadCenter = false
        set(value) { field = value; if (mode != MODE_RSVP) { keepFocusInComfortBand(); invalidate() } }

    var focusIndex = 0
        private set
    private var lastReadFocus = -1

    private var wpm = 320
    private var scrollSpeed = 1.0f
    private var fontSp = 21f

    // ---- Theme -------------------------------------------------------------

    private var bgColor = Color.BLACK
    private var textColor = 0xFFFFC466.toInt()
    private var dimColor = 0xFF6E5A34.toInt()
    private var hlTextColor = 0xFFFFE7B0.toInt()
    private var hlBgColor = 0xFF3A2A10.toInt()
    private var orpColor = 0xFFE24B4A.toInt()

    fun applyTheme(theme: Int) {
        when (theme) {
            1 -> { bgColor = Color.BLACK; textColor = 0xFFC8CCD0.toInt(); dimColor = 0xFF5A5E63.toInt()
                   hlTextColor = 0xFFFFFFFF.toInt(); hlBgColor = 0xFF23324A.toInt(); orpColor = 0xFF58A6FF.toInt() }
            // Green: microLED waveguides render green brightest and sharpest, so
            // green-on-black is the classic HMD reading scheme (the old "Sepia"
            // was just dim amber — indistinguishable on this display).
            2 -> { bgColor = Color.BLACK; textColor = 0xFF7FE08C.toInt(); dimColor = 0xFF3E7048.toInt()
                   hlTextColor = 0xFFC8F5CE.toInt(); hlBgColor = 0xFF12331A.toInt(); orpColor = 0xFFFFB347.toInt() }
            else -> { bgColor = Color.BLACK; textColor = 0xFFFFC466.toInt(); dimColor = 0xFF6E5A34.toInt()
                   hlTextColor = 0xFFFFE7B0.toInt(); hlBgColor = 0xFF3A2A10.toInt(); orpColor = 0xFFE24B4A.toInt() }
        }
        setBackgroundColor(bgColor)
        rebuildPaints()
        invalidate()
    }

    fun applySettings(fontSp: Float, wpm: Int, scrollSpeed: Float) {
        // Only a font-size change alters text metrics. Re-running relayout() for
        // theme/wpm/speed tweaks froze the UI for seconds on long books — it
        // measures every word of the book on the main thread.
        val fontChanged = fontSp != this.fontSp
        this.fontSp = fontSp; this.wpm = wpm.coerceIn(60, 1200); this.scrollSpeed = scrollSpeed
        rebuildPaints()
        if (fontChanged) { relayout(); scrollY = targetScrollForFocus() }
        invalidate()
    }

    // ---- Paints ------------------------------------------------------------

    private val density get() = resources.displayMetrics.density
    private lateinit var textPaint: Paint
    private lateinit var hlPaint: Paint
    private lateinit var dimPaint: Paint
    private lateinit var rsvpPaint: Paint
    private lateinit var rsvpOrpPaint: Paint
    private lateinit var metaPaint: Paint
    private lateinit var hlBgPaint: Paint
    private val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)

    init {
        rebuildPaints()
        setBackgroundColor(bgColor)
    }

    private fun rebuildPaints() {
        val px = fontSp * density
        textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor; textSize = px; typeface = serif }
        dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dimColor; textSize = px; typeface = serif }
        hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hlTextColor; textSize = px; typeface = serif }
        hlBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hlBgColor }
        rsvpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor; textSize = px * 2.4f; typeface = serif; textAlign = Paint.Align.LEFT }
        rsvpOrpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = orpColor; textSize = px * 2.4f; typeface = serif; textAlign = Paint.Align.LEFT }
        metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dimColor; textSize = 12f * density; typeface = Typeface.SANS_SERIF }
    }

    // ---- Layout ------------------------------------------------------------

    private class Line(val start: Int, val end: Int, val paraStart: Boolean)

    private var lines: List<Line> = emptyList()
    private var wordToLine: IntArray = IntArray(0)
    private var lineHeight = 0f
    private var paraGap = 0f
    private val padX get() = 22f * density
    private val padY get() = 26f * density
    private var scrollY = 0f
    private var targetScrollY = 0f

    fun setBook(b: Book, startWord: Int) {
        book = b
        focusIndex = startWord.coerceIn(0, max(0, b.wordCount - 1))
        lastReadFocus = -1
        relayout()
        scrollY = targetScrollForFocus()
        targetScrollY = scrollY
        invalidate()
        onProgress?.invoke(focusIndex)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        relayout(); scrollY = targetScrollForFocus(); targetScrollY = scrollY
    }

    // True when [lines]/[wordToLine] don't match the current book/size — happens
    // when a book is opened while in RSVP mode (layout is skipped for speed) and
    // must be rebuilt the moment a text-layout mode is entered.
    private var layoutDirty = false

    private fun relayout() {
        val b = book ?: run { lines = emptyList(); layoutDirty = false; return }
        if (width == 0 || mode == MODE_RSVP) { layoutDirty = true; return }
        val fm = textPaint.fontMetrics
        lineHeight = (fm.descent - fm.ascent) * 1.42f
        paraGap = lineHeight * 0.55f
        val avail = width - padX * 2
        val out = ArrayList<Line>(b.wordCount / 8 + 1)
        val w2l = IntArray(b.wordCount)
        var i = 0
        val space = textPaint.measureText(" ")
        while (i < b.wordCount) {
            val paraStart = b.words[i].paragraphBreak
            var lineStart = i
            var lineW = 0f
            while (i < b.wordCount) {
                if (i > lineStart && b.words[i].paragraphBreak) break
                val ww = textPaint.measureText(b.words[i].text)
                val add = if (i == lineStart) ww else space + ww
                if (i > lineStart && lineW + add > avail) break
                lineW += add
                w2l[i] = out.size
                i++
            }
            out.add(Line(lineStart, i, paraStart))
        }
        lines = out
        wordToLine = w2l
        layoutDirty = false
    }

    private fun linesPerPage(): Int {
        if (lineHeight <= 0) return 1
        val avail = height - padY * 2
        return max(1, (avail / (lineHeight)).toInt())
    }

    private fun lineTop(lineIdx: Int): Float {
        // y of a line within the fully-laid-out column (paragraph gaps included).
        var y = padY
        var li = 0
        while (li < lineIdx && li < lines.size) {
            y += lineHeight
            if (li + 1 < lines.size && lines[li + 1].paraStart) y += paraGap
            li++
        }
        return y
    }

    private fun targetScrollForFocus(): Float {
        val b = book ?: return 0f
        if (lines.isEmpty() || mode == MODE_RSVP) return 0f
        val li = wordToLine.getOrElse(focusIndex) { 0 }
        return when (mode) {
            // Page the focus line to the top (uses real line positions incl. gaps).
            MODE_PAGED -> (lineTop(li) - padY).coerceAtLeast(0f)
            // autoscroll: keep focus line ~40% down the viewport
            else -> (lineTop(li) - height * 0.40f).coerceAtLeast(0f)
        }
    }

    /**
     * Keep the focus word inside the comfortable central band (inner 30% of the
     * display) while the full page scrolls behind it. The word drifts down through
     * the band as reading advances; when it exits, the page scrolls so the word
     * re-anchors at the band top. Positions use real line tops (paragraph gaps
     * included), so the focus word is always in the easy-to-read center.
     */
    private fun keepFocusInComfortBand() {
        if (lines.isEmpty()) return
        val li = wordToLine.getOrElse(focusIndex) { 0 }
        val top = lineTop(li)                       // content-space y of the focus line
        if (deadCenter) {
            // Pin the focus line at the vertical center; the whole page scrolls
            // under it so the current word never moves off dead-center.
            scrollY = (top - height * 0.5f + lineHeight * 0.5f).coerceAtLeast(0f)
        } else {
            // Keep the focus word inside the comfortable central band (the waveguide
            // is easiest on the eyes here). While it's in the band, don't move; when
            // it drifts out, scroll the full page so the focus line re-anchors at the
            // band top and reads back down. The whole page is still shown.
            val screenY = top - scrollY
            val bandTop = height * COMFORT_TOP
            val bandBottom = height * COMFORT_BOTTOM
            if (screenY < bandTop || screenY + lineHeight > bandBottom) {
                scrollY = (top - bandTop).coerceAtLeast(0f)
            }
        }
        targetScrollY = scrollY
    }

    // ---- Focus / playback --------------------------------------------------

    /** Called by TTS for each spoken word — the perfect-sync path. */
    fun setFocus(index: Int) {
        val b = book ?: return
        val idx = index.coerceIn(0, b.wordCount - 1)
        if (idx == focusIndex) return
        focusIndex = idx
        // TTS is a real reading path. Manual moves use setFocusHard and never
        // arrive here, so they cannot affect progress or word counts.
        if (ttsDriven) reportFocusedWordRead()
        // During TTS (this path) the frame loop is paused, so enforce the comfort
        // band directly for BOTH paged and auto-scroll — otherwise the word just
        // drifts to the bottom as narration advances.
        when (mode) {
            MODE_RSVP -> {}
            else -> keepFocusInComfortBand()
        }
        onProgress?.invoke(focusIndex)
        invalidate()
    }

    fun seekWords(delta: Int) {
        val b = book ?: return
        setFocusHard((focusIndex + delta).coerceIn(0, b.wordCount - 1))
    }

    fun seekToChapter(chapter: Int) {
        val b = book ?: return
        val c = chapter.coerceIn(0, b.chapterStarts.size - 1)
        setFocusHard(b.chapterStarts[c])
    }

    /** Turn one page (paged) or jump a screenful (autoscroll). */
    fun page(forward: Boolean) {
        val b = book ?: return
        when (mode) {
            MODE_RSVP -> setFocusHard((focusIndex + if (forward) 1 else -1).coerceIn(0, b.wordCount - 1))
            else -> {
                val lpp = linesPerPage()
                val li = wordToLine.getOrElse(focusIndex) { 0 }
                val targetLine = (li + if (forward) lpp else -lpp).coerceIn(0, max(0, lines.size - 1))
                setFocusHard(lines[targetLine].start)
            }
        }
    }

    private fun setFocusHard(idx: Int) {
        focusIndex = idx
        targetScrollY = targetScrollForFocus()
        scrollY = targetScrollY
        onProgress?.invoke(focusIndex)
        invalidate()
    }

    fun play() {
        if (isPlaying) return
        isPlaying = true
        // The already-highlighted first word is genuinely being read once play
        // starts; navigation alone never triggers this callback.
        reportFocusedWordRead()
        lastTick = SystemClock.uptimeMillis(); wordAccum = 0f; postFrame()
    }
    fun pause() { isPlaying = false; removeCallbacks(frame) }
    fun togglePlay() { if (isPlaying) pause() else play() }

    private var lastTick = 0L
    private var wordAccum = 0f
    private val frame = Runnable { tick() }
    private fun postFrame() { removeCallbacks(frame); postOnAnimation(frame) }

    private fun tick() {
        if (!isPlaying) return
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastTick).coerceAtMost(60L) / 1000f
        lastTick = now
        val b = book
        if (b == null) { isPlaying = false; return }

        if (!ttsDriven) {
            // Advance focus by WPM. Autoscroll scales pace by scrollSpeed.
            val perSec = (wpm / 60f) * if (mode == MODE_AUTOSCROLL) scrollSpeed else 1f
            wordAccum += perSec * dt
            while (wordAccum >= 1f && focusIndex < b.wordCount - 1) {
                wordAccum -= 1f
                focusIndex++
                reportFocusedWordRead()
            }
            if (mode == MODE_PAGED) keepFocusInComfortBand()    // paced paged reading
            else targetScrollY = targetScrollForFocus()         // paced auto-scroll eases smoothly
            onProgress?.invoke(focusIndex)
            if (focusIndex >= b.wordCount - 1) { isPlaying = false; onFinished?.invoke() }
        }

        // Smoothly ease scroll toward target (autoscroll & TTS follow).
        if (mode == MODE_AUTOSCROLL) {
            val diff = targetScrollY - scrollY
            scrollY += diff * (1f - Math.pow(0.001, dt.toDouble()).toFloat())
            if (abs(diff) < 0.5f) scrollY = targetScrollY
        }
        invalidate()
        if (isPlaying) postFrame()
    }

    private fun reportFocusedWordRead() {
        if (focusIndex == lastReadFocus) return
        lastReadFocus = focusIndex
        onWordRead?.invoke(focusIndex)
    }

    // ---- Draw --------------------------------------------------------------

    private val hlRect = RectF()

    override fun onDraw(canvas: Canvas) {
        val b = book ?: return
        if (mode == MODE_RSVP) { drawRsvp(canvas, b); return }
        if (lines.isEmpty()) return

        val space = textPaint.measureText(" ")
        var y = padY - scrollY
        val bottom = height.toFloat()
        val topCut = -lineHeight * 2
        for (li in lines.indices) {
            val line = lines[li]
            if (li > 0 && line.paraStart) y += paraGap
            if (y > topCut && y < bottom + lineHeight) {
                var x = padX
                val baseline = y - textPaint.fontMetrics.ascent
                for (wi in line.start until line.end) {
                    val wtxt = b.words[wi].text
                    val ww = textPaint.measureText(wtxt)
                    if (wi == focusIndex) {
                        hlRect.set(x - 3f, y - 1f, x + ww + 3f, y + lineHeight * 0.86f)
                        canvas.drawRoundRect(hlRect, 5f, 5f, hlBgPaint)
                        canvas.drawText(wtxt, x, baseline, hlPaint)
                    } else {
                        val p = if (wi < focusIndex) dimPaint else textPaint
                        canvas.drawText(wtxt, x, baseline, p)
                    }
                    x += ww + space
                }
            }
            y += lineHeight
            if (y > bottom + lineHeight * 2) break
        }
    }

    private fun drawRsvp(canvas: Canvas, b: Book) {
        val word = b.words.getOrNull(focusIndex)?.text ?: return
        val cy = height / 2f
        val cx = width / 2f
        // Optimal Recognition Point: a letter slightly left of center.
        val orp = orpIndex(word)
        val pre = word.substring(0, orp)
        val piv = word.substring(orp, orp + 1)
        val post = word.substring(orp + 1)
        val preW = rsvpPaint.measureText(pre)
        val pivW = rsvpPaint.measureText(piv)
        val startX = cx - preW - pivW / 2f
        val baseline = cy - (rsvpPaint.fontMetrics.ascent + rsvpPaint.fontMetrics.descent) / 2f
        // Focus guides above/below the pivot.
        val guideX = startX + preW + pivW / 2f
        val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dimColor; strokeWidth = 1.5f * density }
        canvas.drawLine(guideX, cy - rsvpPaint.textSize * 0.85f, guideX, cy - rsvpPaint.textSize * 0.55f, gp)
        canvas.drawLine(guideX, cy + rsvpPaint.textSize * 0.55f, guideX, cy + rsvpPaint.textSize * 0.85f, gp)
        var x = startX
        canvas.drawText(pre, x, baseline, rsvpPaint); x += preW
        canvas.drawText(piv, x, baseline, rsvpOrpPaint); x += pivW
        canvas.drawText(post, x, baseline, rsvpPaint)
    }

    private fun orpIndex(word: String): Int = when (word.length) {
        1 -> 0
        in 2..5 -> 1
        in 6..9 -> 2
        else -> 3
    }
}

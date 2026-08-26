package com.tapreader.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TapReader on the glasses. A native reader (no WebView) rendered through the
 * binocular SBS layout. Screens (library / reader / get-books / settings) are
 * native view groups toggled by visibility; the SBS cursor's click is dispatched
 * into the active screen so ordinary Android onClick handlers fire. Reading,
 * TTS, downloads and LAN receive are wired to the engines built alongside.
 *
 * Gesture map:
 *   single tap ....... click UI under cursor (reader tap = play/pause)
 *   double tap ....... toggle the reader control bar / library
 *   triple tap ....... open Settings
 *   pull left edge ... back (reader -> library, saves progress)
 *   pull right edge .. cycle reading mode (paged / autoscroll / word-at-a-time)
 *   top/bottom edge .. scrub reading position (or scroll the active list)
 */
class MainActivity : Activity(), CustomKeyboardView.OnKeyboardActionListener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var library: LibraryStore
    private lateinit var summaryClient: SummaryClient
    private lateinit var covers: CoverStore
    private lateinit var tts: TtsReader
    private var receiver: ReceiveServer? = null

    private lateinit var binocular: BinocularSbsLayout
    private lateinit var viewport: FrameLayout
    private lateinit var reader: ReaderView
    private lateinit var libraryPanel: ScrollView
    private lateinit var libraryList: LinearLayout
    private lateinit var getBooksPanel: ScrollView
    private lateinit var getBooksList: LinearLayout
    private lateinit var settingsPanel: ScrollView
    private lateinit var settingsList: LinearLayout
    private lateinit var controlBar: LinearLayout
    private lateinit var topHud: TextView
    private lateinit var statusPill: TextView
    private lateinit var banner: TextView
    private lateinit var keyboardContainer: FrameLayout
    private var keyboardView: CustomKeyboardView? = null

    private var book: Book? = null
    private var bookFile: String = ""
    private var actualReadPosition = 0
    private var actualWordsRead = 0
    private var ttsOn = false

    private enum class Screen { LIBRARY, READER, GET_BOOKS, SETTINGS }
    private var screen = Screen.LIBRARY

    // Text-input session (keyboard types into a buffer).
    private var inputBuffer = StringBuilder()
    private var inputPrompt = ""
    private var onInputSubmit: ((String) -> Unit)? = null
    private var inputView: TextView? = null

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val CONTROL_BAR_TIMEOUT_MS = 7000L
    private val MAX_SUMMARY_WORDS = 3000   // cap the passage sent to the LLM
    private var batteryLevel = -1
    private var batteryCharging = false
    private val hudClockFormat = SimpleDateFormat("h:mm a · EEE, MMM d", Locale.getDefault())
    private val hudTick = object : Runnable {
        override fun run() {
            refreshHud()
            main.postDelayed(this, 30_000L)
        }
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            batteryLevel = if (level >= 0 && scale > 0) level * 100 / scale else -1
            batteryCharging = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
                else -> false
            }
            refreshHud()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        library = LibraryStore(this)
        summaryClient = SummaryClient(library)
        covers = CoverStore(this, library)
        tts = TtsReader(this)

        buildUi()
        wireGestures()
        wireTts()
        applySettingsToReader()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        refreshHud()
        main.post(hudTick)

        receiver = ReceiveServer(this, library,
            onReceived = { name ->
                // Refresh no matter which screen is up — rebuilding a hidden panel
                // is cheap, and the library is guaranteed current when it appears.
                main.post { flash("📚 Received: ${name.take(36)}"); refreshLibrary() }
            },
            onLibraryChanged = { main.post { refreshLibrary() } }
        ).also { it.start() }

        refreshLibrary()
        showLibrary()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(hudTick)
        runCatching { unregisterReceiver(batteryReceiver) }
        receiver?.stop()
        tts.stop()
        saveProgress()
    }

    override fun onPause() { super.onPause(); saveProgress() }

    // ---- UI construction ---------------------------------------------------

    private fun buildUi() {
        reader = ReaderView(this).apply {
            isClickable = true
            setOnClickListener { toggleReading() }
        }
        libraryList = column()
        libraryPanel = scroll(libraryList)
        getBooksList = column()
        getBooksPanel = scroll(getBooksList)
        settingsList = column()
        settingsPanel = scroll(settingsList)

        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC0A0A0A.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            visibility = View.GONE
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(52))
            lp.gravity = Gravity.BOTTOM
            layoutParams = lp
        }
        rebuildControlBar()

        banner = TextView(this).apply {
            textSize = 13f; setTextColor(0xFFFFC466.toInt()); gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        statusPill = TextView(this).apply {
            setBackgroundColor(0xE6101418.toInt()); setTextColor(Color.WHITE)
            textSize = 13f; setPadding(dp(14), dp(6), dp(14), dp(6))
            visibility = View.GONE
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; lp.topMargin = dp(34)
            layoutParams = lp
        }

        topHud = TextView(this).apply {
            textSize = 11f; setTextColor(0xFFD8DEE9.toInt()); gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true); setPadding(dp(12), 0, dp(12), 0)
            setBackgroundColor(0xC9000000.toInt())
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(26))
            lp.gravity = Gravity.TOP; layoutParams = lp
        }

        keyboardContainer = FrameLayout(this).apply {
            visibility = View.GONE
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(150))
            lp.gravity = Gravity.BOTTOM
            layoutParams = lp
        }

        buildConfirmOverlay()
        buildTocOverlay()
        buildBookMenuOverlay()
        buildBookDetailOverlay()

        viewport = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(reader, match())
            addView(libraryPanel, match())
            addView(getBooksPanel, match())
            addView(settingsPanel, match())
            addView(controlBar)
            addView(topHud)
            addView(statusPill)
            addView(keyboardContainer)
            addView(tocOverlay, match())
            addView(bookMenuOverlay, match())
            addView(bookDetailOverlay, match())
            addView(confirmOverlay, match())   // topmost — mirrors to both eyes
        }

        binocular = BinocularSbsLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(viewport, 0)
            setContentTarget(viewport)   // route SBS clicks into native UI
        }
        setContentView(binocular)
        enableImmersive()
    }

    // ---- Confirm overlay (mirrored to both eyes, unlike a system dialog) ----

    private lateinit var confirmOverlay: FrameLayout
    private lateinit var confirmMessage: TextView
    private lateinit var confirmYes: Button
    private lateinit var confirmNo: Button
    // Focus for cursor-free contexts (library): 0 = Cancel (safe default), 1 = Confirm.
    private var confirmFocus = 0

    private fun buildConfirmOverlay() {
        confirmMessage = TextView(this).apply {
            textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(16))
        }
        confirmYes = Button(this).apply {
            text = "Confirm"; isAllCaps = false; textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
        }
        confirmNo = Button(this).apply {
            text = "Cancel"; isAllCaps = false; textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
            setOnClickListener { hideConfirm() }
        }
        val cancel = confirmNo
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cancel); addView(confirmYes)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF161B22.toInt()); cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0xFF30363D.toInt())
            }
            val lp = FrameLayout.LayoutParams(dp(300), FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.CENTER; layoutParams = lp
            addView(confirmMessage); addView(buttons)
        }
        confirmOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())   // scrim
            isClickable = true                          // swallow taps behind it
            visibility = View.GONE
            addView(card)
        }
    }

    /** Show a mirrored yes/no confirm. [confirmLabel] labels the confirm button. */
    private fun showConfirm(message: String, confirmLabel: String, onConfirm: () -> Unit) {
        confirmMessage.text = message
        confirmYes.text = confirmLabel
        confirmYes.setOnClickListener { hideConfirm(); onConfirm() }
        confirmFocus = 0            // safe default: Cancel
        applyConfirmFocus()
        confirmOverlay.visibility = View.VISIBLE
        confirmOverlay.bringToFront()
    }

    private fun applyConfirmFocus() {
        listOf(confirmNo, confirmYes).forEachIndexed { i, b ->
            val focused = i == confirmFocus
            b.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(if (focused) 0xFF4B4829.toInt() else 0xFF1B2026.toInt())
                setStroke(dp(if (focused) 2 else 1), if (focused) 0xFFFFC466.toInt() else 0xFF30363D.toInt())
            }
            b.setTextColor(if (focused) 0xFFFFF3C4.toInt() else 0xFFD8DEE9.toInt())
        }
    }

    private fun stepConfirmFocus(delta: Int) {
        confirmFocus = (confirmFocus + delta).mod(2)
        applyConfirmFocus()
    }

    private fun hideConfirm() { confirmOverlay.visibility = View.GONE }

    // ---- Book action submenu (library is cursor-free: swipe selects, tap acts,
    // ---- double-tap closes) --------------------------------------------------

    private lateinit var bookMenuOverlay: FrameLayout
    private lateinit var bookMenuCard: LinearLayout
    private val bookMenuItems = mutableListOf<Button>()
    private var bookMenuFocus = 0

    private fun buildBookMenuOverlay() {
        bookMenuCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF161B22.toInt()); cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0xFF30363D.toInt())
            }
            val lp = FrameLayout.LayoutParams(dp(300), FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.CENTER; layoutParams = lp
        }
        bookMenuOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            isClickable = true
            visibility = View.GONE
            addView(bookMenuCard)
        }
    }

    private fun showBookMenu(item: Shelf) {
        bookMenuCard.removeAllViews()
        bookMenuItems.clear()
        bookMenuCard.addView(TextView(this).apply {
            text = item.title.take(48); textSize = 15f; setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), dp(10)); maxLines = 2
        })
        fun option(label: String, onPick: () -> Unit) {
            val b = Button(this).apply {
                text = label; isAllCaps = false; textSize = 14f
                stateListAnimator = null
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(3), 0, dp(3)) }
                setOnClickListener { hideBookMenu(); onPick() }
            }
            bookMenuItems += b
            bookMenuCard.addView(b)
        }
        option("📖  Open") { requestOpenBook(item.fileName) }
        option("↩  Reset progress") {
            showConfirm("Reset progress for “${item.title.take(40)}”?", "Reset") {
                library.resetProgress(item.fileName)
                if (bookFile == item.fileName) { actualReadPosition = 0; actualWordsRead = 0 }
                refreshLibrary(); flash("Progress reset")
            }
        }
        option("🗑  Remove from glasses") {
            showConfirm("Remove “${item.title.take(40)}” from the glasses? It stays in the companion, ready to restore.", "Remove") {
                library.archive(item.fileName); refreshLibrary(); flash("Moved off the glasses — restore it from the companion")
            }
        }
        bookMenuCard.addView(TextView(this).apply {
            text = "swipe · tap to choose · double-tap to close"
            textSize = 11f; setTextColor(0xFF6E7A85.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        bookMenuFocus = 0
        applyBookMenuFocus()
        bookMenuOverlay.visibility = View.VISIBLE
        bookMenuOverlay.bringToFront()
    }

    private fun hideBookMenu() { bookMenuOverlay.visibility = View.GONE }

    private fun applyBookMenuFocus() {
        bookMenuItems.forEachIndexed { i, b ->
            val focused = i == bookMenuFocus
            b.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(if (focused) 0xFF4B4829.toInt() else 0xFF1B2026.toInt())
                setStroke(dp(if (focused) 2 else 1), if (focused) 0xFFFFC466.toInt() else 0xFF30363D.toInt())
            }
            b.setTextColor(if (focused) 0xFFFFF3C4.toInt() else 0xFFD8DEE9.toInt())
        }
    }

    private fun stepBookMenuFocus(delta: Int) {
        if (bookMenuItems.isEmpty()) return
        bookMenuFocus = (bookMenuFocus + delta).mod(bookMenuItems.size)
        applyBookMenuFocus()
    }

    // ---- 4-way hover selection (library + get-books are cursor-free) ----------
    //
    // Every gallery screen registers its actionable views in visual order. An
    // amber ring marks the hovered item; horizontal swipes step ±1, vertical
    // swipes move by GEOMETRY to the nearest item in the next row (same column
    // preferred, d-pad style). Tap activates whatever is ringed.

    private val hoverItems = mutableListOf<View>()
    private var hoverFocus = 0

    private fun hoverReset() {
        hoverItems.clear()
        hoverFocus = 0
    }

    /** Register every actionable view under [root] in visual (tree) order. A
     *  clickable container with clickable children defers to the children. */
    private fun hoverRegisterTree(root: View) {
        if (root.visibility != View.VISIBLE) return
        if (root is ViewGroup) {
            if (root.isClickable && !hasClickableDescendant(root)) { hoverItems += root; return }
            for (i in 0 until root.childCount) hoverRegisterTree(root.getChildAt(i))
        } else if (root.isClickable) hoverItems += root
    }

    private fun hasClickableDescendant(g: ViewGroup): Boolean {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            if (c.isClickable) return true
            if (c is ViewGroup && hasClickableDescendant(c)) return true
        }
        return false
    }

    /** Rebuild the hover registry for the visible gallery screen. Focus is
     *  PRESERVED — refreshes mid-navigation must not snap the ring to the top;
     *  screen entry points call [hoverReset] explicitly for a fresh start. */
    private fun rebuildHover(root: View) {
        val keep = hoverFocus
        hoverItems.clear()
        hoverRegisterTree(root)
        hoverFocus = keep.coerceIn(0, (hoverItems.size - 1).coerceAtLeast(0))
        applyHoverFocus(scrollTo = false)
    }

    /** Center of a hover item in its screen's scroll-content coordinate space. */
    private fun hoverCenter(v: View): Pair<Float, Float> {
        var x = 0; var y = 0; var cur: View = v
        val content = activeScroll()?.getChildAt(0)
        while (cur !== content && cur.parent is View) {
            x += cur.left; y += cur.top
            cur = cur.parent as View
        }
        return (x + v.width / 2f) to (y + v.height / 2f)
    }

    private fun stepHover(delta: Int) {
        if (hoverItems.isEmpty()) return
        // Clamped at both ends — wrap-around teleports read as random jumps.
        hoverFocus = (hoverFocus + delta).coerceIn(0, hoverItems.lastIndex)
        applyHoverFocus()
    }

    /** Vertical navigation by geometry: nearest item in the next row down/up,
     *  preferring the same column — index arithmetic moves sideways whenever
     *  mixed-size items interleave with grid rows. */
    private fun stepHoverVertical(dir: Int) {
        val cur = hoverItems.getOrNull(hoverFocus) ?: return
        val (cx, cy) = hoverCenter(cur)
        // Same-row neighbors can differ in height (wrapped titles), putting
        // their centers a few px "below" — a real next-row candidate must
        // clear half the current item's height.
        val rowClear = cur.height * 0.45f
        var best = -1
        var bestKey = Float.MAX_VALUE
        hoverItems.forEachIndexed { i, v ->
            if (i == hoverFocus) return@forEachIndexed
            val (x, y) = hoverCenter(v)
            if (dir > 0 && y <= cy + rowClear) return@forEachIndexed
            if (dir < 0 && y >= cy - rowClear) return@forEachIndexed
            val key = kotlin.math.abs(y - cy) * 1000f + kotlin.math.abs(x - cx)
            if (key < bestKey) { bestKey = key; best = i }
        }
        if (best >= 0) { hoverFocus = best; applyHoverFocus() }
        // No candidate = top/bottom edge: stay put, never wrap-teleport.
    }

    private fun activateHover() { hoverItems.getOrNull(hoverFocus)?.performClick() }

    private fun hoverLabel(v: View): String =
        v.contentDescription?.toString()?.take(30)
            ?: (v as? Button)?.text?.toString()?.take(30)
            ?: v.javaClass.simpleName

    /** The amber selection ring drawn over the hovered view. */
    private fun hoverRing() = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = dp(9).toFloat()
        setColor(0x22FFC466)
        setStroke(dp(2), 0xFFFFC466.toInt())
    }

    private fun applyHoverFocus(scrollTo: Boolean = true) {
        if (hoverItems.isEmpty()) return
        hoverFocus = hoverFocus.coerceIn(0, hoverItems.lastIndex)
        hoverItems.forEachIndexed { i, v ->
            v.foreground = if (i == hoverFocus) hoverRing() else null
        }
        android.util.Log.i("TapReaderInput", "hover $hoverFocus/${hoverItems.size} on ${hoverLabel(hoverItems[hoverFocus])}")
        if (!scrollTo) return
        // Keep the ringed item comfortably on screen.
        val panel = activeScroll() ?: return
        val content = panel.getChildAt(0) ?: return
        hoverItems.getOrNull(hoverFocus)?.let { v ->
            var y = 0; var cur: View = v
            while (cur !== content && cur.parent is View) { y += cur.top; cur = cur.parent as View }
            panel.smoothScrollTo(0, (y - dp(80)).coerceAtLeast(0))
        }
    }

    // ---- Table-of-contents overlay (mirrored to both eyes) ------------------

    private lateinit var tocOverlay: FrameLayout
    private lateinit var tocList: LinearLayout

    private fun buildTocOverlay() {
        tocList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            addView(tocList, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val title = TextView(this).apply {
            text = "📑 Contents"; textSize = 16f; setTextColor(Color.WHITE)
            setPadding(dp(8), dp(2), dp(8), dp(10))
        }
        val close = Button(this).apply {
            text = "Close"; isAllCaps = false; textSize = 14f
            setOnClickListener { hideToc() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF161B22.toInt()); cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0xFF30363D.toInt())
            }
            val lp = FrameLayout.LayoutParams(dp(330), dp(330))
            lp.gravity = Gravity.CENTER; layoutParams = lp
            addView(title); addView(scroll); addView(close)
        }
        tocOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            isClickable = true
            visibility = View.GONE
            addView(card)
        }
    }

    private fun showToc() {
        val b = book ?: return
        tocList.removeAllViews()
        val current = b.chapterAt(reader.focusIndex)
        for (i in b.chapterTitles.indices) {
            val label = "${i + 1}.  ${b.chapterTitles[i].ifBlank { "Chapter ${i + 1}" }.take(46)}"
            tocList.addView(Button(this).apply {
                text = if (i == current) "▶ $label" else label
                isAllCaps = false; textSize = 13f; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(if (i == current) 0xFFFFC466.toInt() else Color.WHITE)
                setBackgroundColor(if (i == current) 0x33FFC466 else 0x00000000)
                setPadding(dp(10), dp(2), dp(10), dp(2))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
                setOnClickListener {
                    hideToc()
                    reader.seekToChapter(i)
                    if (ttsOn) tts.start(b, reader.focusIndex)
                    flash("Chapter ${i + 1}: ${b.chapterTitles[i].take(40)}")
                    saveProgress()
                }
            })
        }
        hideControlBar()
        tocOverlay.visibility = View.VISIBLE
        tocOverlay.bringToFront()
    }

    private fun hideToc() { tocOverlay.visibility = View.GONE }

    // Focus-driven menu bar: swiping the pad moves the highlight, a tap activates
    // it, so no cursor aiming is ever needed while the bar is up.
    private val ctlButtons = mutableListOf<Button>()
    private var ctlFocus = 0

    private fun rebuildControlBar() {
        controlBar.removeAllViews()
        ctlButtons.clear()
        fun add(b: Button) { ctlButtons += b; controlBar.addView(b) }
        // Play/pause lives on the screen tap; this slot is the AI section summary.
        add(ctlButton("📖 Summary") { summarizeSection() })
        add(ctlButton(if (ttsOn) "🔊 TTS ✓" else "🔊 TTS") { toggleTts() })
        add(ctlButton("Mode") { cycleMode() })
        if ((book?.chapterTitles?.size ?: 0) > 1) add(ctlButton("📑 ToC") { showToc() })
        add(ctlButton("◄ Ch") { gotoChapter(-1) })
        add(ctlButton("Ch ►") { gotoChapter(1) })
        add(ctlButton("Library") { closeBook() })
        // Rebuilds (e.g. the TTS label toggling) keep the user's place in the bar.
        ctlFocus = ctlFocus.coerceIn(0, ctlButtons.lastIndex)
        applyCtlFocus()
    }

    private fun applyCtlFocus() {
        ctlButtons.forEachIndexed { i, b ->
            val focused = i == ctlFocus
            b.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(if (focused) 0xFF4B4829.toInt() else 0xFF1B2026.toInt())
                setStroke(dp(if (focused) 2 else 1), if (focused) 0xFFFFC466.toInt() else 0xFF30363D.toInt())
            }
            b.setTextColor(if (focused) 0xFFFFF3C4.toInt() else 0xFFD8DEE9.toInt())
        }
    }

    /** Swipe step while the bar is up. Wraps at the ends; every step re-arms the
     *  auto-hide timer so the bar never vanishes mid-navigation. */
    private fun stepCtlFocus(delta: Int) {
        if (ctlButtons.isEmpty()) return
        ctlFocus = (ctlFocus + delta).mod(ctlButtons.size)
        applyCtlFocus()
        resetControlBarTimer()
    }

    /** The bar owns input only when nothing sits above it (overlays, keyboard). */
    private fun menuNavActive(): Boolean =
        screen == Screen.READER && controlBar.visibility == View.VISIBLE &&
            confirmOverlay.visibility != View.VISIBLE && tocOverlay.visibility != View.VISIBLE &&
            keyboardContainer.visibility != View.VISIBLE

    // ---- Screen switching --------------------------------------------------

    private fun showOnly(v: View) {
        for (panel in listOf(reader, libraryPanel, getBooksPanel, settingsPanel)) {
            panel.visibility = if (panel === v) View.VISIBLE else View.GONE
        }
    }

    // Always rebuild on entry: books can arrive over Wi-Fi at any moment, and a
    // stale list here looks like the companion "didn't sync".
    private fun showLibrary() {
        screen = Screen.LIBRARY; showOnly(libraryPanel); controlBar.visibility = View.GONE
        hideKeyboard(); hideBookMenu()
        hoverReset()
        refreshLibrary(); refreshHud()
    }
    private fun showReader() { screen = Screen.READER; showOnly(reader); reader.bringToFront(); controlBar.bringToFront(); topHud.bringToFront(); statusPill.bringToFront(); refreshHud() }
    private fun showGetBooks() { screen = Screen.GET_BOOKS; showOnly(getBooksPanel); controlBar.visibility = View.GONE; refreshHud() }
    private fun showSettings() { screen = Screen.SETTINGS; showOnly(settingsPanel); controlBar.visibility = View.GONE; refreshSettings(); refreshHud() }

    // ---- Library -----------------------------------------------------------

    private fun refreshLibrary() {
        libraryList.removeAllViews()
        val s = library.streak()
        banner.text = encouragement(s)
        libraryList.addView(banner)
        libraryList.addView(sectionTitle("📚  Your library"))

        val shelf = library.shelf()
        if (shelf.isEmpty()) {
            libraryList.addView(hint("No books yet. Tap “Get free books” or send one from the phone companion over Wi-Fi."))
        } else {
            libraryList.addView(bookGallery(shelf))
        }
        libraryList.addView(spacer())
        libraryList.addView(bigButton("🔍  Get free books") { openGetBooks() })
        libraryList.addView(bigButton("⚙  Settings") { showSettings() })
        libraryList.addView(hint(deviceInfoLine()))
        rebuildHover(libraryList)
    }

    private fun bookGallery(items: List<Shelf>): View {
        val gallery = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var row: LinearLayout? = null
        items.forEachIndexed { index, item ->
            if (index % 2 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                gallery.addView(row)
            }
            row!!.addView(bookTile(item))
        }
        if (items.size % 2 == 1) row!!.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        return gallery
    }

    private fun bookTile(item: Shelf): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(6), dp(6), dp(6), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF12161B.toInt()); cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            // Tap on the focused book opens its action submenu (Open/Reset/Remove).
            setOnClickListener { showBookMenu(item) }
            contentDescription = item.title
        }
        val holder = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(178))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(coverPlaceholderColor(item.title)); cornerRadius = dp(6).toFloat()
            }
        }
        val initials = TextView(this).apply {
            text = item.title.take(2).uppercase(); textSize = 28f; setTextColor(0x88FFFFFF.toInt()); gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        holder.addView(initials)
        holder.addView(image)
        tile.addView(holder)
        tile.addView(TextView(this).apply {
            text = item.title; textSize = 14f; maxLines = 2; setTextColor(0xFFFFE7B0.toInt()); setPadding(0, dp(6), 0, 0)
        })
        tile.addView(TextView(this).apply {
            text = buildString {
                if (item.author.isNotBlank()) append(item.author).append(" · ")
                append("${item.percent}% read · ${item.wordsRead} words")
            }
            textSize = 11f; maxLines = 2; setTextColor(0xFF8B949E.toInt())
        })
        // Per-tile Reset/Delete buttons are gone: those actions live in the
        // focus-driven submenu now (tap the highlighted book to open it).
        val cached = covers.cachedCover(item.fileName)
        if (cached != null) {
            image.setImageBitmap(cached); initials.visibility = View.GONE
        } else {
            Thread {
                val cover = covers.loadOrFetch(item)
                if (cover != null) main.post {
                    image.setImageBitmap(cover)
                    initials.visibility = View.GONE
                }
            }.start()
        }
        return tile
    }

    private fun coverPlaceholderColor(seed: String): Int {
        val palette = intArrayOf(0xFF3A2A10.toInt(), 0xFF1D3A2E.toInt(), 0xFF23324A.toInt(), 0xFF3A1D2E.toInt(), 0xFF2E2A3A.toInt())
        return palette[(seed.hashCode().and(0x7fffffff)) % palette.size]
    }

    private fun requestOpenBook(fileName: String) {
        // Confirm whenever a different book is already loaded (you select books from
        // the library screen, so gating on Screen.READER never fired).
        if (book != null && bookFile != fileName) {
            val current = book?.title?.take(40).orEmpty()
            showConfirm("Close “$current” and open the selected book?", "Open") { openBook(fileName) }
        } else {
            openBook(fileName)
        }
    }

    private fun openBook(fileName: String) {
        // Persist until parsing finishes — a large book can take seconds, and a
        // pill that fades early leaves the reader staring at a silent screen.
        flash("📖 Opening ${fileName.substringBeforeLast('.').replace('_', ' ').take(36)}…", persist = true)
        Thread {
            val f = java.io.File(library.booksDir, fileName)
            val parsed = runCatching { DocumentParser.parse(this, f) }.getOrNull()
            main.post {
                if (parsed == null || parsed.wordCount == 0) { flash("Couldn't open this file"); return@post }
                book = parsed; bookFile = fileName
                actualReadPosition = library.savedWordIndex(fileName).coerceIn(0, parsed.wordCount - 1)
                actualWordsRead = library.shelf().firstOrNull { it.fileName == fileName }?.wordsRead ?: 0
                reader.setBook(parsed, library.savedWordIndex(fileName))
                rebuildControlBar()
                showReader()
                flash("${parsed.title} · ${parsed.chapterTitles.getOrElse(parsed.chapterAt(reader.focusIndex)) { "" }}")
            }
        }.start()
    }

    private fun closeBook() { saveProgress(); stopTts(); showLibrary() }

    private fun saveProgress() {
        val b = book ?: return
        library.saveProgress(b, bookFile, actualReadPosition, actualWordsRead)
    }

    // ---- Reading control ---------------------------------------------------

    private fun isReadingActive(): Boolean = reader.isPlaying || tts.isActive
    /** True when reading is actively progressing (not paused/stopped). */
    private fun isReadingPlaying(): Boolean = reader.isPlaying || (tts.isActive && !tts.isPaused)

    /** Play/pause: pauses & resumes narration (keeps TTS on) or the auto-pacer. */
    private fun toggleReading() {
        if (ttsOn) {
            // An explicit tap supersedes any pending scrub-restart; without this a
            // delayed restart could land right after a resume → two voices at once.
            main.removeCallbacks(scrubTtsRestart)
            if (tts.isPaused) { tts.resume(); flash("Narrating…") } else { tts.pause(); flash("Paused") }
            rebuildControlBar()
        } else {
            reader.togglePlay(); afterPlayStateChange()
        }
    }

    /** Jump chapters; if narrating, restart TTS from the new position so audio and
     *  highlight stay together. */
    private fun gotoChapter(delta: Int) {
        val b = book ?: return
        reader.seekToChapter(b.chapterAt(reader.focusIndex) + delta)
        if (ttsOn) tts.start(b, reader.focusIndex)
        flash("Chapter ${b.chapterAt(reader.focusIndex) + 1}: ${b.chapterTitles.getOrElse(b.chapterAt(reader.focusIndex)) { "" }.take(40)}")
    }

    private fun cycleMode() {
        reader.mode = (reader.mode + 1) % 3
        library.putInt(LibraryStore.K_MODE, reader.mode)
        val name = when (reader.mode) {
            ReaderView.MODE_PAGED -> "Page"
            ReaderView.MODE_AUTOSCROLL -> "Auto-scroll"
            else -> "One word at a time"
        }
        flash(if (ttsOn) "Mode: $name" else "Mode: $name — tap to read")
    }

    private fun afterPlayStateChange() { rebuildControlBar(); if (!reader.isPlaying) saveProgress() }

    // ---- TTS ---------------------------------------------------------------

    private fun wireTts() {
        tts.onWord = { idx -> reader.setFocus(idx) }
        tts.onError = { msg -> main.post { flash(msg) } }
        tts.onNotice = { msg -> main.post { flash(msg) } }
        // TTS stopped itself (finished/errored): return control to the tap-pacer.
        tts.onStopped = { main.post { ttsOn = false; reader.ttsDriven = false; saveProgress(); rebuildControlBar() } }
    }

    private fun toggleTts() {
        val b = book ?: return
        if (ttsOn) { stopTts(); return }
        val key = library.getString(LibraryStore.K_FISH_KEY, "")
        if (key.isBlank()) { flash("Add a fish.audio key in Settings for narration"); return }
        tts.configure(key, library.getString(LibraryStore.K_FISH_VOICE, ""))
        reader.pause(); reader.ttsDriven = true
        ttsOn = true
        tts.start(b, reader.focusIndex)
        // Immediate spoken feedback while the first sentence synthesizes (cached
        // per voice, so it plays instantly after the first ever use).
        tts.speakCue("Starting narration")
        flash("Narrating…")
        rebuildControlBar()
    }

    private fun stopTts() {
        main.removeCallbacks(scrubTtsRestart)
        if (tts.isActive) tts.stop()
        reader.ttsDriven = false
        ttsOn = false
        saveProgress()
        rebuildControlBar()
    }

    // ---- AI section summary (spoken via fish TTS, never shown) --------------

    private fun summarizeSection() {
        val b = book ?: return
        if (tts.isSpeakingOnce) { tts.stopOneOff(); flash("Summary stopped"); return }  // toggle off
        if (!summaryClient.hasKey()) { flash("Add a summary AI key in Settings"); return }
        val fishKey = library.getString(LibraryStore.K_FISH_KEY, "")
        if (fishKey.isBlank()) { flash("Add a fish.audio key to hear summaries"); return }

        // Passage = the current section up to the focus word (what you've read).
        val focus = reader.focusIndex.coerceIn(0, b.wordCount - 1)
        val chapterStart = b.chapterStarts.getOrElse(b.chapterAt(focus)) { 0 }
        val start = maxOf(chapterStart, focus - MAX_SUMMARY_WORDS)
        val end = (focus + 1).coerceAtMost(b.wordCount)
        if (end - start < 20) { flash("Read a little more first"); return }
        val passage = b.words.subList(start, end).joinToString(" ") { it.text }

        // Don't let narration and the summary talk over each other.
        if (ttsOn && !tts.isPaused) tts.pause()
        reader.pause()
        rebuildControlBar()
        flash("Summarizing the section…", persist = true)
        summaryClient.summarize(b.title, b.author, passage) { result ->
            main.post {
                result.onSuccess { summary ->
                    tts.configure(fishKey, library.getString(LibraryStore.K_FISH_VOICE, ""))
                    flash("🔊 Section summary")
                    tts.speakOnce(summary)
                }.onFailure { flash("Summary unavailable: ${it.message}") }
            }
        }
    }

    // ---- Get free books (modern gallery: library cards → cover grid → detail) ----

    private fun openGetBooks() {
        hoverReset()
        showGetBooks()
        renderGetBooks(emptyList(), null)
    }

    private var getBooksSource = "gutenberg"
    private val thumbExecutor = java.util.concurrent.Executors.newFixedThreadPool(3)

    private fun renderGetBooks(results: List<BookSource.Result>, query: String?, focusResults: Boolean = false) {
        getBooksList.removeAllViews()
        getBooksList.addView(sectionTitle("📚  Get free books"))

        // The two in-app catalogs as graphical library cards.
        getBooksList.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(libraryCard("📖", "Project Gutenberg", "75,000+ free classics",
                "Browse popular ›", 0xFF1D3A2E.toInt()) { browseSource("gutenberg") })
            addView(libraryCard("✒", "Standard Ebooks", "Beautifully typeset editions",
                "Browse newest ›", 0xFF23324A.toInt()) { browseSource("standardebooks") })
        })
        getBooksList.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(searchChip("🔍  Search Gutenberg") { promptSearch("gutenberg") })
            addView(searchChip("🔍  Search Standard Ebooks") { promptSearch("standardebooks") })
        })

        var firstTile: View? = null
        if (results.isNotEmpty()) {
            val src = if (getBooksSource == "standardebooks") "Standard Ebooks" else "Project Gutenberg"
            getBooksList.addView(sectionTitle("$src — ${query.orEmpty()} · ${results.size} books"))
            // Cover-thumbnail grid, three per row, names underneath. Tapping a
            // book opens its detail card (cover, summary, download).
            var row: LinearLayout? = null
            results.forEachIndexed { i, r ->
                if (i % 3 == 0) {
                    row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    getBooksList.addView(row)
                }
                val tile = freeBookTile(r)
                if (firstTile == null) firstTile = tile
                row!!.addView(tile)
            }
            val rem = results.size % 3
            if (rem != 0) repeat(3 - rem) {
                row!!.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
            }
        }

        getBooksList.addView(sectionTitle("More free libraries"))
        var repoRow: LinearLayout? = null
        BookSource.REPOSITORIES.forEachIndexed { i, repo ->
            if (i % 2 == 0) {
                repoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                getBooksList.addView(repoRow)
            }
            repoRow!!.addView(repoCard(repo))
        }
        if (BookSource.REPOSITORIES.size % 2 != 0) {
            repoRow!!.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        }
        getBooksList.addView(spacer())
        getBooksList.addView(bigButton("‹ Back to library") { showLibrary() })
        rebuildHover(getBooksList)
        // After a browse/search lands, put the ring on the first book so the
        // next swipe walks the results, not the header.
        if (focusResults) firstTile?.let { t ->
            val i = hoverItems.indexOf(t)
            if (i >= 0) { hoverFocus = i; applyHoverFocus() }
        }
    }

    /** Big graphical catalog card: glyph, name, tagline, action line. */
    private fun libraryCard(glyph: String, name: String, tag: String, action: String,
                            accent: Int, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            contentDescription = name
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(accent, 0xFF10151B.toInt())
            ).apply { cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0xFF30363D.toInt()) }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(4), dp(4), dp(4), dp(2)) }
            addView(TextView(this@MainActivity).apply { text = glyph; textSize = 28f })
            addView(TextView(this@MainActivity).apply {
                text = name; textSize = 15f; setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(4), 0, 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = tag; textSize = 11f; setTextColor(0xFF9AA6B2.toInt())
            })
            addView(TextView(this@MainActivity).apply {
                text = action; textSize = 12f; setTextColor(0xFF3FB950.toInt()); setPadding(0, dp(6), 0, 0)
            })
            setOnClickListener { onClick() }
        }

    private fun searchChip(label: String, onClick: () -> Unit): View =
        Button(this).apply {
            text = label; isAllCaps = false; textSize = 12f
            stateListAnimator = null
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFF1B2026.toInt()); setStroke(dp(1), 0xFF30363D.toInt())
            }
            setTextColor(0xFFB9C2CC.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(4), dp(2), dp(4), dp(4)) }
            setOnClickListener { onClick() }
        }

    /** Small book tile: cover thumbnail with the name underneath. */
    private fun freeBookTile(r: BookSource.Result): View {
        val holder = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(coverPlaceholderColor(r.title)); cornerRadius = dp(8).toFloat()
            }
            clipToOutline = true
        }
        val initials = TextView(this).apply {
            text = r.title.take(2).uppercase(); textSize = 24f
            setTextColor(0x88FFFFFF.toInt()); gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        holder.addView(initials); holder.addView(image)
        loadThumb(r.coverUrl, image, initials)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            contentDescription = r.title
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(4), dp(4), dp(4), dp(6)) }
            addView(holder)
            addView(TextView(this@MainActivity).apply {
                text = r.title; textSize = 12f; maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(0xFFFFE7B0.toInt()); setPadding(dp(2), dp(5), dp(2), 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = r.author.ifBlank { " " }; textSize = 10f; maxLines = 1
                setTextColor(0xFF8B949E.toInt()); setPadding(dp(2), dp(1), dp(2), 0)
            })
            setOnClickListener { showBookDetail(r) }
        }
    }

    private fun loadThumb(url: String?, image: ImageView, initials: TextView) {
        if (url.isNullOrBlank()) return
        covers.cachedThumb(url)?.let {
            image.setImageBitmap(it); initials.visibility = View.GONE; return
        }
        thumbExecutor.execute {
            val bmp = covers.loadOrFetchThumb(url)
            if (bmp != null) main.post {
                image.setImageBitmap(bmp); initials.visibility = View.GONE
            }
        }
    }

    /** Compact card for the external libraries list. */
    private fun repoCard(repo: BookSource.Repo): View {
        val browsable = repo.url.contains("gutenberg.org") || repo.url.contains("standardebooks.org")
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            contentDescription = repo.name
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF12161B.toInt()); cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0xFF262D34.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(4), dp(3), dp(4), dp(3)) }
            addView(TextView(this@MainActivity).apply {
                text = repo.name.take(2).uppercase(); textSize = 14f
                setTextColor(0xFFD6D2A0.toInt()); gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(coverPlaceholderColor(repo.name)); cornerRadius = dp(8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = repo.name; textSize = 13f; maxLines = 1
                    setTextColor(if (browsable) 0xFF58A6FF.toInt() else Color.WHITE)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@MainActivity).apply {
                    text = if (browsable) "Browse here ›" else repo.note
                    textSize = 10f; maxLines = 2
                    setTextColor(if (browsable) 0xFF3FB950.toInt() else 0xFF8B949E.toInt())
                })
            })
            setOnClickListener {
                when {
                    repo.url.contains("gutenberg.org") -> browseSource("gutenberg")
                    repo.url.contains("standardebooks.org") -> browseSource("standardebooks")
                    // No web browser on the glasses; these need accounts/DRM anyway.
                    else -> flash("${repo.name}: open on the phone companion or a computer — the glasses have no web browser.")
                }
            }
        }
    }

    private fun browseSource(source: String) {
        getBooksSource = source
        val label = if (source == "standardebooks") "Standard Ebooks" else "Project Gutenberg"
        flash("Loading $label…", persist = true)
        Thread {
            val results = if (source == "standardebooks") BookSource.browseStandardEbooks() else BookSource.popularGutenberg()
            main.post {
                renderGetBooks(results, if (source == "standardebooks") "newest" else "popular", focusResults = true)
                flash(if (results.isEmpty()) "Couldn't load $label — check Wi-Fi" else "${results.size} books · tap one for details")
            }
        }.start()
    }

    private fun promptSearch(source: String) {
        getBooksSource = source
        val label = if (source == "standardebooks") "Standard Ebooks" else "Project Gutenberg"
        textInput("Search $label (title or author)", "") { q -> if (q.isNotBlank()) runSearch(q, source) }
    }

    private fun runSearch(q: String, source: String) {
        getBooksSource = source
        val label = if (source == "standardebooks") "Standard Ebooks" else "Gutenberg"
        flash("Searching $label…")
        Thread {
            val results = if (source == "standardebooks") BookSource.searchStandardEbooks(q)
            else BookSource.searchGutenberg(q)
            main.post {
                renderGetBooks(results, q, focusResults = true)
                flash(if (results.isEmpty()) "No results" else "${results.size} results")
            }
        }.start()
    }

    // ---- Book detail overlay (cover · summary · download) -------------------

    private lateinit var bookDetailOverlay: FrameLayout
    private lateinit var bookDetailCard: LinearLayout
    private val bookDetailButtons = mutableListOf<Button>()
    private var bookDetailFocus = 0
    // Vertical swipes scroll the summary while the card is open.
    private var bookDetailScroll: ScrollView? = null

    private fun buildBookDetailOverlay() {
        bookDetailCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF161B22.toInt()); cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0xFF30363D.toInt())
            }
            val lp = FrameLayout.LayoutParams(dp(390), FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.CENTER; layoutParams = lp
        }
        bookDetailOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            isClickable = true
            visibility = View.GONE
            addView(bookDetailCard)
        }
    }

    private fun showBookDetail(r: BookSource.Result) {
        bookDetailCard.removeAllViews()
        bookDetailButtons.clear()

        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val initials = TextView(this).apply {
            text = r.title.take(2).uppercase(); textSize = 22f
            setTextColor(0x88FFFFFF.toInt()); gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        loadThumb(r.coverUrl, image, initials)
        bookDetailCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(FrameLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(84), dp(122))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(coverPlaceholderColor(r.title)); cornerRadius = dp(8).toFloat()
                }
                clipToOutline = true
                addView(initials); addView(image)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = r.title; textSize = 16f; maxLines = 3; setTextColor(Color.WHITE)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@MainActivity).apply {
                    text = r.author.ifBlank { "Unknown author" }; textSize = 12f
                    setTextColor(0xFFFFE7B0.toInt()); setPadding(0, dp(3), 0, 0)
                })
                addView(TextView(this@MainActivity).apply {
                    text = "${r.source} · ${r.ext.uppercase()}"; textSize = 11f
                    setTextColor(0xFF58A6FF.toInt()); setPadding(0, dp(3), 0, 0)
                })
            })
        })

        val summaryView = TextView(this).apply {
            textSize = 12.5f; setTextColor(0xFFC6CFD8.toInt())
            setLineSpacing(0f, 1.15f)
            setPadding(0, 0, dp(6), dp(4))
        }
        bookDetailScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply { setMargins(0, dp(10), 0, dp(8)) }
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false   // always show there's more to read
            addView(summaryView)
        }
        bookDetailCard.addView(bookDetailScroll)
        loadBookSummary(r, summaryView)

        fun action(label: String, onPick: () -> Unit) {
            val b = Button(this).apply {
                text = label; isAllCaps = false; textSize = 14f
                stateListAnimator = null
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(dp(4), 0, dp(4), 0) }
                setOnClickListener { onPick() }
            }
            bookDetailButtons += b
        }
        action("⬇  Download") { hideBookDetail(); downloadBook(r) }
        action("✕  Close") { hideBookDetail() }
        bookDetailCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            bookDetailButtons.forEach { addView(it) }
        })
        bookDetailCard.addView(TextView(this).apply {
            text = "swipe ⇅ read summary · ⇄ buttons · tap chooses · double-tap closes"
            textSize = 11f; setTextColor(0xFF6E7A85.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })

        bookDetailFocus = 0   // Download is why you opened the card
        applyBookDetailFocus()
        bookDetailOverlay.visibility = View.VISIBLE
        bookDetailOverlay.bringToFront()
    }

    /** Summary chain, reputable public sources only: catalog blurb (Gutendex) →
     *  publisher description (Standard Ebooks page) → Wikipedia/Open Library. */
    private fun loadBookSummary(r: BookSource.Result, into: TextView) {
        if (!r.summary.isNullOrBlank()) { into.text = r.summary; return }
        into.text = "Loading summary…"
        Thread {
            val text = r.bookPageUrl?.let { BookSource.fetchStandardEbooksSummary(it) }
                ?: BookInfoClient.summary(r.title, r.author).getOrNull()
                    ?.let { "${it.text}\n\n— ${it.source}" }
                ?: "No summary available for this edition."
            main.post { if (bookDetailOverlay.visibility == View.VISIBLE) into.text = text }
        }.start()
    }

    private fun downloadBook(r: BookSource.Result) {
        flash("Downloading ${r.title.take(30)}…", persist = true)
        Thread {
            val bytes = BookSource.download(r.downloadUrl)
            main.post {
                if (bytes == null) { flash("Download failed"); return@post }
                val f = library.importFile(r.suggestedFileName(), bytes)
                library.saveBookMetadata(f.name, r.title, r.author, r.coverUrl)
                flash("✓ Added: ${r.title.take(30)}")
                requestOpenBook(f.name)
            }
        }.start()
    }

    private fun hideBookDetail() { bookDetailOverlay.visibility = View.GONE }

    private fun applyBookDetailFocus() {
        bookDetailButtons.forEachIndexed { i, b ->
            val focused = i == bookDetailFocus
            b.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(if (focused) 0xFF4B4829.toInt() else 0xFF1B2026.toInt())
                setStroke(dp(if (focused) 2 else 1), if (focused) 0xFFFFC466.toInt() else 0xFF30363D.toInt())
            }
            b.setTextColor(if (focused) 0xFFFFF3C4.toInt() else 0xFFD8DEE9.toInt())
        }
    }

    private fun stepBookDetailFocus(delta: Int) {
        if (bookDetailButtons.isEmpty()) return
        bookDetailFocus = (bookDetailFocus + delta).mod(bookDetailButtons.size)
        applyBookDetailFocus()
    }

    // ---- Settings ----------------------------------------------------------

    private fun refreshSettings() {
        settingsList.removeAllViews()
        settingsList.addView(sectionTitle("⚙  Settings"))

        stepper("Font size", library.getInt(LibraryStore.K_FONT_SP, 21), "sp", 12, 40, 1) {
            library.putInt(LibraryStore.K_FONT_SP, it); applySettingsToReader()
        }
        stepper("Speed (words/min)", library.getInt(LibraryStore.K_WPM, 320), "wpm", 80, 900, 20) {
            library.putInt(LibraryStore.K_WPM, it); applySettingsToReader()
        }
        stepper("Daily goal", library.getInt(LibraryStore.K_DAILY_GOAL, 2000), "words", 200, 20000, 200) {
            library.putInt(LibraryStore.K_DAILY_GOAL, it)
        }
        settingsList.addView(choiceRow("Theme", listOf("Amber", "White", "Green"), library.getInt(LibraryStore.K_THEME, 0)) {
            library.putInt(LibraryStore.K_THEME, it); applySettingsToReader()
        })
        settingsList.addView(choiceRow("Default mode", listOf("Page", "Auto-scroll", "One word"), library.getInt(LibraryStore.K_MODE, 0)) {
            library.putInt(LibraryStore.K_MODE, it); reader.mode = it
        })
        settingsList.addView(choiceRow("Focus position", listOf("Comfort band", "Dead center"), library.getInt(LibraryStore.K_FOCUS_MODE, 0)) {
            library.putInt(LibraryStore.K_FOCUS_MODE, it); applySettingsToReader()
        })
        settingsList.addView(choiceRow("Top status HUD", listOf("On", "Off"),
            if (library.getBool(LibraryStore.K_TOP_HUD, true)) 0 else 1) {
            library.putBool(LibraryStore.K_TOP_HUD, it == 0)
            refreshHud()
        })

        settingsList.addView(sectionTitle("🔊  fish.audio narration"))
        val hasKey = library.getString(LibraryStore.K_FISH_KEY, "").isNotBlank()
        settingsList.addView(hint(if (hasKey) "✓ Key saved" else "Paste your fish.audio key (scrcpy: Ctrl+V), or push it from the phone companion."))
        settingsList.addView(pasteField("fish.audio API key", LibraryStore.K_FISH_KEY, password = true) {
            flash("Key saved ✓")
        })
        settingsList.addView(hint("Saved voices · choose one"))
        for (preset in library.voicePresets()) settingsList.addView(voicePresetRow(preset))
        settingsList.addView(hint("Add or manage up to five saved voices in the web companion, or choose a starter voice below."))
        for (v in TtsReader.SUGGESTED) {
            settingsList.addView(bigButton("Voice: ${v.label}") {
                library.saveVoicePreset(v.label, v.referenceId)
                library.putString(LibraryStore.K_FISH_VOICE, v.referenceId); flash("Voice: ${v.label}"); refreshSettings()
            })
        }
        settingsList.addView(pasteField("Or paste a voice reference ID", LibraryStore.K_FISH_VOICE) {
            flash("Voice set ✓"); refreshSettings()
        })

        settingsList.addView(sectionTitle("📖  Section summary (AI)"))
        val sp = summaryClient.provider()
        settingsList.addView(hint("The 📖 Summary button reads an AI recap of the section you've read (with hard words defined), aloud via fish.audio. ${if (summaryClient.hasKey()) "✓ key saved" else "No key yet."}"))
        settingsList.addView(choiceRow("Summary provider", SummaryClient.PROVIDERS.map { it.label },
            SummaryClient.PROVIDERS.indexOfFirst { it.id == sp.id }.coerceAtLeast(0)) {
            summaryClient.setProvider(SummaryClient.PROVIDERS[it].id); refreshSettings()
        })
        settingsList.addView(pasteField("${sp.label} key", "llm_key_${sp.id}", password = true) { flash("Summary key saved ✓") })

        settingsList.addView(sectionTitle("📡  Receive over Wi-Fi"))
        settingsList.addView(hint(deviceInfoLine()))
        settingsList.addView(pasteField("Device name", "device_name") { refreshSettings() })

        settingsList.addView(spacer())
        settingsList.addView(bigButton("‹ Back to library") { showLibrary() })
    }

    private fun currentVoiceLabel(): String {
        val id = library.getString(LibraryStore.K_FISH_VOICE, "")
        return library.voicePresets().firstOrNull { it.id == id }?.name
            ?: TtsReader.SUGGESTED.firstOrNull { it.referenceId == id }?.label
            ?: if (id.isBlank()) "Model default" else id.take(10) + "…"
    }

    private fun voicePresetRow(preset: VoicePreset): View = RadioButton(this).apply {
        text = preset.name
        textSize = 14f
        setTextColor(if (preset.id == library.getString(LibraryStore.K_FISH_VOICE, "")) 0xFFFFC466.toInt() else Color.LTGRAY)
        isChecked = preset.id == library.getString(LibraryStore.K_FISH_VOICE, "")
        setOnClickListener {
            library.putString(LibraryStore.K_FISH_VOICE, preset.id)
            flash("Voice: ${preset.name}")
            refreshSettings()
        }
    }

    private fun applySettingsToReader() {
        reader.applyTheme(library.getInt(LibraryStore.K_THEME, 0))
        reader.applySettings(
            library.getInt(LibraryStore.K_FONT_SP, 21).toFloat(),
            library.getInt(LibraryStore.K_WPM, 320),
            (library.getInt(LibraryStore.K_SCROLL_SPEED, 100) / 100f)
        )
        reader.deadCenter = library.getInt(LibraryStore.K_FOCUS_MODE, 0) == 1
        reader.mode = library.getInt(LibraryStore.K_MODE, 0)
    }

    // ---- Reading encouragement --------------------------------------------

    private fun encouragement(s: LibraryStore.Streak): String {
        val flame = if (s.days > 0) "🔥 ${s.days}-day streak" else "Start a reading streak today"
        val goal = when {
            s.goalMet -> "✓ Daily goal met — ${s.todayWords} words"
            s.todayWords > 0 -> "${s.goalPercent}% of today's goal (${s.todayWords}/${s.goalWords})"
            else -> "Read ${s.goalWords} words today to hit your goal"
        }
        return "$flame\n$goal"
    }

    private fun onWordRead(index: Int) {
        val b = book ?: return
        actualReadPosition = index.coerceIn(0, b.wordCount - 1)
        actualWordsRead = (actualWordsRead + 1).coerceAtMost(b.wordCount)
        library.recordWordsRead(1)
        if (actualWordsRead % 20 == 0) saveProgress()
        refreshHud()
    }

    // ---- Top status HUD ---------------------------------------------------

    private fun refreshHud() {
        if (!::topHud.isInitialized) return
        val enabled = library.getBool(LibraryStore.K_TOP_HUD, true)
        topHud.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) return

        val power = if (batteryLevel >= 0) {
            "${if (batteryCharging) "Charging " else ""}${batteryLevel}%"
        } else "Battery ?"
        val b = book
        val progress = if (b == null || b.wordCount == 0) {
            "No book open"
        } else {
            val chapter = b.chapterAt(actualReadPosition)
            val start = b.chapterStarts[chapter]
            val end = b.chapterStarts.getOrElse(chapter + 1) { b.wordCount }
            val chapterPercent = if (end > start && actualWordsRead > 0) {
                ((actualReadPosition - start + 1) * 100 / (end - start)).coerceIn(0, 100)
            } else 0
            "«${b.title.take(22)}» ${if (b.wordCount > 0) actualWordsRead * 100 / b.wordCount else 0}% · Ch ${chapter + 1} $chapterPercent%"
        }
        topHud.text = "${hudClockFormat.format(Date())}   $power   ${networkStatus()}   $progress"
    }

    private fun networkStatus(): String {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "Offline"
        val caps = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) } ?: return "Offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connected"
        }
    }

    // ---- Gestures ----------------------------------------------------------

    private fun wireGestures() {
        reader.onProgress = { _ -> }
        reader.onWordRead = { idx -> onWordRead(idx) }
        reader.onFinished = { main.post { flash("📖 Finished — great work!") } }

        binocular.apply {
            logicalClickHandler = { x, y -> handleClick(x, y) }
            edgeScrollHandler = { dy -> handleScroll(dy) }
            leftEdgeBackHandler = { onBack() }
            rightEdgePullHandler = { if (screen == Screen.READER) cycleMode() }
            doubleTapHandler = { onDoubleTap() }
            tripleTapHandler = { showSettings() }
            tapInterceptor = { false }
            // Focus-driven surfaces: the reader's menu bar AND the gallery
            // screens (library, get-books), which are cursor-free — 4-way
            // swipes move the hover ring instead. The keyboard needs the
            // cursor back (its keys are position-targeted).
            menuNavigationActive = {
                keyboardContainer.visibility != View.VISIBLE &&
                    (menuNavActive() || screen == Screen.LIBRARY || screen == Screen.GET_BOOKS)
            }
            horizontalStepHandler = { delta ->
                when {
                    confirmOverlay.visibility == View.VISIBLE -> stepConfirmFocus(delta)
                    bookMenuOverlay.visibility == View.VISIBLE -> stepBookMenuFocus(delta)
                    bookDetailOverlay.visibility == View.VISIBLE -> stepBookDetailFocus(delta)
                    screen == Screen.LIBRARY || screen == Screen.GET_BOOKS -> stepHover(delta)
                    else -> stepCtlFocus(delta)
                }
            }
            verticalStepHandler = { delta ->
                when {
                    confirmOverlay.visibility == View.VISIBLE -> stepConfirmFocus(delta)
                    bookMenuOverlay.visibility == View.VISIBLE -> stepBookMenuFocus(delta)
                    // On the detail card, vertical swipes READ: they scroll the
                    // summary text. Horizontal swipes move between the buttons.
                    bookDetailOverlay.visibility == View.VISIBLE ->
                        bookDetailScroll?.smoothScrollBy(0, delta * dp(84))
                    screen == Screen.LIBRARY || screen == Screen.GET_BOOKS -> stepHoverVertical(delta)
                    // Reader menu keeps its convention: swipe up dismisses the bar.
                    else -> if (delta < 0) hideControlBar()
                }
            }
            menuDismissHandler = { if (menuNavActive()) hideControlBar() }
            cursorSuppressed = {
                (screen == Screen.LIBRARY || screen == Screen.GET_BOOKS) &&
                    keyboardContainer.visibility != View.VISIBLE
            }
            // Don't scrub the reading position while the menu bar or keyboard is up
            // (moving the cursor toward the bottom menu must not advance the word).
            contentInteractionBlocked = { controlBar.visibility == View.VISIBLE || keyboardContainer.visibility == View.VISIBLE }
            setContentTarget(viewport)
        }
    }

    // ---- Reader menu bar (auto-hides after 7s of no interaction) ------------

    private val controlBarHideRunnable = Runnable { hideControlBar() }

    private fun showControlBar() {
        ctlFocus = 0                       // always open on the first button
        rebuildControlBar()
        controlBar.visibility = View.VISIBLE
        controlBar.bringToFront()
        resetControlBarTimer()
    }

    private fun hideControlBar() {
        controlBar.visibility = View.GONE
        main.removeCallbacks(controlBarHideRunnable)
    }

    private fun resetControlBarTimer() {
        main.removeCallbacks(controlBarHideRunnable)
        main.postDelayed(controlBarHideRunnable, CONTROL_BAR_TIMEOUT_MS)
    }

    /** Route the cursor click: keyboard first, then focus-driven surfaces, else
     *  let it flow to native UI at the cursor position. */
    private fun handleClick(x: Float, y: Float): Boolean {
        val kb = keyboardView
        if (keyboardContainer.visibility == View.VISIBLE && kb != null) {
            val top = keyboardContainer.top.toFloat()
            if (y >= top) { kb.handleAnchoredTap(x, y - top); return true }
        }
        // While the bar is up the interaction is focus-driven: a tap activates the
        // highlighted button no matter where the cursor happens to sit.
        if (menuNavActive()) {
            ctlButtons.getOrNull(ctlFocus)?.performClick()
            return true
        }
        // Gallery screens are entirely hover-driven (no cursor): a tap activates
        // whatever is ringed — book, overlay option, or confirm button.
        if (screen == Screen.LIBRARY || screen == Screen.GET_BOOKS) {
            when {
                confirmOverlay.visibility == View.VISIBLE ->
                    (if (confirmFocus == 1) confirmYes else confirmNo).performClick()
                bookMenuOverlay.visibility == View.VISIBLE ->
                    bookMenuItems.getOrNull(bookMenuFocus)?.performClick()
                bookDetailOverlay.visibility == View.VISIBLE ->
                    bookDetailButtons.getOrNull(bookDetailFocus)?.performClick()
                else -> activateHover()
            }
            return true
        }
        return false // dispatched to viewport -> native onClick
    }

    // Scrubbing during narration: pause the voice, follow the thumb, then resume
    // narration from wherever the scrub landed once it settles. (Scrub used to be
    // silently disabled whenever TTS was on — which read as "scroll is broken".)
    private val scrubTtsRestart = Runnable {
        val b = book ?: return@Runnable
        if (ttsOn) tts.start(b, reader.focusIndex)
    }

    private fun handleScroll(dy: Int) {
        android.util.Log.d("TapReaderInput", "scrub dy=$dy screen=$screen ttsOn=$ttsOn")
        when (screen) {
            Screen.READER -> {
                if (ttsOn) {
                    tts.pause()
                    main.removeCallbacks(scrubTtsRestart)
                    main.postDelayed(scrubTtsRestart, 700L)
                }
                reader.seekWords(if (dy > 0) 2 else -2)
            }
            else -> activeScroll()?.scrollBy(0, dy * 2)
        }
    }

    private fun activeScroll(): ScrollView? = when (screen) {
        Screen.LIBRARY -> libraryPanel
        Screen.GET_BOOKS -> getBooksPanel
        Screen.SETTINGS -> settingsPanel
        else -> null
    }

    private fun onBack() {
        when {
            confirmOverlay.visibility == View.VISIBLE -> hideConfirm()
            bookMenuOverlay.visibility == View.VISIBLE -> hideBookMenu()
            bookDetailOverlay.visibility == View.VISIBLE -> hideBookDetail()
            keyboardContainer.visibility == View.VISIBLE -> hideKeyboard()
            screen == Screen.READER -> closeBook()
            screen == Screen.GET_BOOKS || screen == Screen.SETTINGS -> showLibrary()
            else -> { /* at library root */ }
        }
    }

    private fun onDoubleTap() {
        // Double-tap backs out of any overlay first, on every screen.
        when {
            confirmOverlay.visibility == View.VISIBLE -> { hideConfirm(); return }
            bookMenuOverlay.visibility == View.VISIBLE -> { hideBookMenu(); return }
            bookDetailOverlay.visibility == View.VISIBLE -> { hideBookDetail(); return }
        }
        when (screen) {
            Screen.READER -> if (controlBar.visibility == View.VISIBLE) hideControlBar() else showControlBar()
            // Otherwise it jumps back into the open book.
            Screen.LIBRARY -> if (book != null) showReader()
            else -> showLibrary()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { onBack() }

    // The temple click arrives as a KEY event. View-hierarchy key dispatch only
    // reaches our layout while some view holds focus — and a screen rebuild
    // (removeAllViews) silently drops focus, after which taps would go dead.
    // The Activity sees every key regardless, so route center keys explicitly.
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A ||
            event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
        ) return binocular.dispatchKeyEvent(event)
        return super.dispatchKeyEvent(event)
    }

    // ---- Standard inline text field (paste via scrcpy Ctrl+V) --------------

    /**
     * A real focusable EditText inline in Settings. Move the cursor onto it and
     * click to focus, then paste from scrcpy (Ctrl+V) and hit Save. The visible
     * text lets you confirm the paste landed. Used for the fish.audio key, voice
     * reference ID, and device name — long values that are painful to type on the
     * on-screen keyboard.
     */
    private fun pasteField(label: String, key: String, password: Boolean = false, onSaved: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        row.addView(TextView(this).apply { text = label; textSize = 13f; setTextColor(0xFF8B949E.toInt()) })
        val saved = library.getString(key, "")
        val et = EditText(this).apply {
            // Like the companion: a saved secret is never shown, on screen or in a
            // scrcpy mirror — the field only accepts a replacement, typed as dots.
            if (!password) { setText(saved); setSelection(text.length) }
            textSize = 15f; setTextColor(Color.WHITE)
            setBackgroundColor(0xFF161B22.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            inputType = if (password)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            isFocusableInTouchMode = true
            // Focusable so scrcpy can paste, but never raise the on-screen keyboard.
            showSoftInputOnFocus = false
            hint = when {
                password && saved.isNotBlank() -> "•••••••• saved · paste to replace"
                password -> "not set · paste with scrcpy (Ctrl+V)"
                else -> "tap to focus · paste with scrcpy (Ctrl+V)"
            }
        }
        row.addView(et)
        row.addView(Button(this).apply {
            text = "Save"; isAllCaps = false
            setOnClickListener {
                val v = et.text.toString().trim()
                when {
                    key == "device_name" -> library.putString(key, v.ifBlank { "Glasses" })
                    // Blank on a secret field keeps the stored key (mirrors the companion).
                    password && v.isBlank() -> {
                        flash(if (saved.isBlank()) "Nothing pasted yet" else "Kept the saved key")
                        et.clearFocus(); hideSystemKeyboard(et); return@setOnClickListener
                    }
                    else -> library.putString(key, v)
                }
                if (password) { et.setText(""); et.hint = "•••••••• saved · paste to replace" }
                et.clearFocus(); hideSystemKeyboard(et); onSaved()
            }
        })
        return row
    }

    private fun hideSystemKeyboard(v: View) {
        runCatching {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(v.windowToken, 0)
        }
    }

    // ---- Keyboard text input ----------------------------------------------

    private fun textInput(prompt: String, initial: String, onSubmit: (String) -> Unit) {
        inputPrompt = prompt
        inputBuffer = StringBuilder(initial)
        onInputSubmit = onSubmit
        showKeyboard()
        updateInputPill()
    }

    private fun updateInputPill() {
        flash("$inputPrompt: ${inputBuffer}", persist = true)
    }

    private fun showKeyboard() {
        if (keyboardView == null) {
            keyboardView = CustomKeyboardView(this).apply {
                setOnKeyboardActionListener(this@MainActivity)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            keyboardContainer.addView(keyboardView)
        }
        keyboardContainer.visibility = View.VISIBLE
        keyboardContainer.bringToFront()
    }

    private fun hideKeyboard() {
        keyboardContainer.visibility = View.GONE
        onInputSubmit = null
        statusPill.visibility = View.GONE
    }

    override fun onKeyPressed(key: String) { inputBuffer.append(key); updateInputPill() }
    override fun onBackspacePressed() { if (inputBuffer.isNotEmpty()) inputBuffer.deleteCharAt(inputBuffer.length - 1); updateInputPill() }
    override fun onEnterPressed() {
        val cb = onInputSubmit; val text = inputBuffer.toString()
        hideKeyboard(); cb?.invoke(text)
    }
    override fun onHideKeyboard() { hideKeyboard() }
    override fun onClearPressed() { inputBuffer.clear(); updateInputPill() }
    override fun onMoveCursorLeft() {}
    override fun onMoveCursorRight() {}
    override fun onMicrophonePressed() { flash("Voice typing isn't available in TapReader") }

    // ---- Small view builders ----------------------------------------------

    private fun flash(text: String, persist: Boolean = false) {
        statusPill.text = text
        statusPill.visibility = View.VISIBLE
        statusPill.bringToFront()
        main.removeCallbacks(hidePill)
        if (!persist) main.postDelayed(hidePill, 2600)
    }
    private val hidePill = Runnable { if (keyboardContainer.visibility != View.VISIBLE) statusPill.visibility = View.GONE }

    private fun deviceInfoLine(): String =
        "This device: ${library.getString("device_name", "Glasses")} · ${receiver?.ipAddress() ?: "?"}:${ReceiveServer.PORT}"

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(38), dp(18), dp(28))
    }
    private fun scroll(child: View) = ScrollView(this).apply {
        setBackgroundColor(Color.BLACK); isVerticalScrollBarEnabled = false
        addView(child); visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }
    private fun match() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; isClickable = true
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF12161B.toInt()); cornerRadius = dp(12).toFloat()
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(5), 0, dp(5)); layoutParams = lp
    }
    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(0xFFB0B6BD.toInt()); setPadding(dp(2), dp(14), 0, dp(6))
    }
    private fun hint(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(0xFF7D8590.toInt()); setPadding(dp(2), dp(6), 0, dp(6))
    }
    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(10)) }
    private fun bigButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f; setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(5), 0, dp(5)); layoutParams = lp
    }
    private fun ctlButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f
        stateListAnimator = null   // flat: no elevation shadow under custom backgrounds
        // Any tap on the menu bar counts as interaction and restarts the 7s timer.
        setOnClickListener { resetControlBarTimer(); onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }
    }

    private fun stepper(label: String, value: Int, unit: String, min: Int, max: Int, step: Int, onChange: (Int) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(4), 0, dp(4)); layoutParams = lp
        }
        val label2 = TextView(this).apply {
            text = "$label: $value $unit"; textSize = 14f; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        var v = value
        fun upd(nv: Int) { v = nv.coerceIn(min, max); label2.text = "$label: $v $unit"; onChange(v) }
        row.addView(label2)
        row.addView(Button(this).apply { text = "−"; isAllCaps = false; setOnClickListener { upd(v - step) } })
        row.addView(Button(this).apply { text = "+"; isAllCaps = false; setOnClickListener { upd(v + step) } })
        settingsList.addView(row)
    }

    private fun choiceRow(label: String, options: List<String>, selected: Int, onChange: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(6), 0, dp(6)); layoutParams = lp
        }
        row.addView(TextView(this).apply { text = label; textSize = 14f; setTextColor(Color.WHITE) })
        val opts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = ArrayList<Button>()
        options.forEachIndexed { i, name ->
            val b = Button(this).apply {
                text = name; isAllCaps = false; textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            b.setOnClickListener {
                onChange(i)
                buttons.forEachIndexed { j, bb -> bb.setTextColor(if (j == i) 0xFFFFC466.toInt() else Color.LTGRAY) }
            }
            buttons.add(b); opts.addView(b)
        }
        buttons.forEachIndexed { j, bb -> bb.setTextColor(if (j == selected) 0xFFFFC466.toInt() else Color.LTGRAY) }
        row.addView(opts)
        return row
    }

    private fun enableImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
}

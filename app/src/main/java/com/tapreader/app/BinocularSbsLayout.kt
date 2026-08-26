package com.tapreader.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioManager
import android.os.SystemClock
import android.util.AttributeSet
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Side-by-side binocular compositor (lineage: TapGarden / TapGPT / TapLinkX3).
 *
 * The first child is a single logical viewport measured to half the physical
 * width and drawn twice (left + right eye). The right temple pad (cyttsp5)
 * drives a cross-hair cursor; the left pad (cyttsp6) is volume. The temple's
 * physical click arrives as KEYCODE_BUTTON_A/DPAD_CENTER — a KEY, never a touch.
 *
 * SmartView specifics:
 *  - Vertical EDGE SCROLLING instead of scroll bars: parking the cursor in the
 *    top/bottom band scrolls the page via [edgeScrollHandler].
 *  - DOUBLE-TAP detection (touch taps or temple-key presses): fires
 *    [doubleTapHandler] (voice/STT entry point). Single clicks are therefore
 *    deferred by the double-tap window before dispatching.
 *  - [tapInterceptor]: when it returns true the tap is consumed before any
 *    click/double-tap logic (used to stop an in-progress voice recording).
 */
class BinocularSbsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val cursorView = CursorView(context)
    private var content: View? = null

    /** Consume a tap before click logic (e.g. stop recording). Return true = consumed. */
    var tapInterceptor: (() -> Boolean)? = null
    /** Double-tap on the right pad / temple key. */
    var doubleTapHandler: (() -> Unit)? = null
    /** Triple-tap on the right pad / temple key (opens Settings). */
    var tripleTapHandler: (() -> Unit)? = null
    /** Click at logical cursor position; return true to consume (keyboard overlay). */
    var logicalClickHandler: ((Float, Float) -> Boolean)? = null
    /** Vertical edge scroll ticks (dy in logical px, +down). */
    var edgeScrollHandler: ((Int) -> Unit)? = null
    /** Pull against the left edge → back (or exit dim mode). Fires once per pull. */
    var leftEdgeBackHandler: (() -> Unit)? = null
    /** Pull against the right edge → enter dim mode. Fires once per pull. */
    var rightEdgePullHandler: (() -> Unit)? = null
    var contentInteractionBlocked: (() -> Boolean)? = null
    /** While true, right-pad movement is consumed as menu steps (cursor parks). */
    var menuNavigationActive: (() -> Boolean)? = null
    /** Menu focus steps while [menuNavigationActive]: -1 = left, +1 = right. */
    var horizontalStepHandler: ((Int) -> Unit)? = null
    /** Vertical focus steps while [menuNavigationActive]: -1 = up, +1 = down.
     *  When wired, vertical travel steps focus (4-way hover navigation);
     *  when null, the legacy swipe-up [menuDismissHandler] behavior applies. */
    var verticalStepHandler: ((Int) -> Unit)? = null
    /** Deliberate swipe UP while the menu is active — conventional "dismiss". */
    var menuDismissHandler: (() -> Unit)? = null

    private var cursorX = 320f
    private var cursorY = 240f
    private var activeSide = Side.NONE
    private var lastInputX = 0f
    private var lastInputY = 0f
    private var downInputX = 0f
    private var downInputY = 0f
    private var lastMoveTimeMs = 0L
    private var preMoveCursorX = 0f
    private var preMoveCursorY = 0f
    private var lastMoveDist = 0f
    private var leftVolumeStartY = 0f
    private var leftVolumeStart = 0
    private var currentInputUsesMirroredCoordinates = false
    private var lastClickTime = 0L

    // Multi-tap machinery: each tap-up (re)schedules a resolve after the tap
    // window; taps arriving within the window increment the count. When the
    // sequence settles it dispatches single click / double / triple by count.
    private var lastTapUpTime = 0L
    private var tapCount = 0
    private var multiTapRunnable: Runnable? = null

    private var edgeScrollDy = 0
    private var edgeScrollActive = false
    private var menuStepAccum = 0f
    private var lastMenuStepMs = 0L
    private var menuDismissAccum = 0f
    private var vStepAccum = 0f
    // Per-gesture axis commitment for focus stepping (see the axis-lock comment
    // in handleGlassesInput). Reset on every touch-down.
    private var menuAxisLock = AXIS_NONE
    // True once the current center-key press has auto-repeated or been flagged
    // long — its eventual UP is then the system's, never a tap.
    private var keyHeldLong = false
    // Edge PULL gestures: with the cursor pinned at an edge, continued push in
    // that direction accumulates until it crosses the threshold (parking or
    // passing never triggers). The *Fired latches after one fire so a single
    // continuous pull acts exactly once.
    private var leftPullAccum = 0f
    private var leftPullFired = false
    private var rightPullAccum = 0f
    private var rightPullFired = false
    private val edgeScrollRunnable = object : Runnable {
        override fun run() {
            if (!edgeScrollActive || content == null) return
            if (edgeScrollDy != 0) {
                edgeScrollHandler?.invoke(edgeScrollDy)
                postDelayed(this, EDGE_SCROLL_INTERVAL_MS)
            } else {
                stopEdgeScroll()
            }
        }
    }

    private enum class Side { NONE, LEFT_VOLUME, RIGHT_CURSOR }

    init {
        clipChildren = false
        clipToPadding = false
        addView(cursorView)
    }

    fun setContentTarget(view: View) {
        content = view
        cursorView.bringToFront()
        post {
            val logicalWidth = logicalViewportWidth(width).coerceAtLeast(1)
            cursorX = logicalWidth * 0.5f
            cursorY = height * 0.5f
            updateCursor()
            pokeCursor()
        }
    }

    // The crosshair is only useful while the hand is on the pad; parked over book
    // text it is just clutter. Hide it after a quiet period; any pad movement,
    // tap, or temple click brings it straight back.
    private val cursorHideRunnable = Runnable { cursorView.visibility = View.INVISIBLE }

    /** While this returns true the crosshair never shows — used by fully
     *  focus-driven screens (the library) where swipes move a highlight instead. */
    var cursorSuppressed: (() -> Boolean)? = null

    private fun pokeCursor() {
        if (cursorSuppressed?.invoke() == true) {
            cursorView.visibility = View.INVISIBLE
            removeCallbacks(cursorHideRunnable)
            return
        }
        cursorView.visibility = View.VISIBLE
        removeCallbacks(cursorHideRunnable)
        postDelayed(cursorHideRunnable, CURSOR_HIDE_MS)
    }

    val cursorPosX: Float get() = cursorX
    val cursorPosY: Float get() = cursorY

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val logicalWidth = logicalViewportWidth(measuredWidth)
        val logicalHeight = measuredHeight.coerceAtLeast(0)
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(logicalWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(logicalHeight, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val logicalWidth = logicalViewportWidth(width)
        if (logicalWidth <= 0) return
        val drawTime = drawingTime
        canvas.save()
        canvas.clipRect(0, 0, logicalWidth, height)
        drawLogicalChildren(canvas, drawTime)
        canvas.restore()
        canvas.save()
        canvas.translate(logicalWidth.toFloat(), 0f)
        canvas.clipRect(0, 0, logicalWidth, height)
        drawLogicalChildren(canvas, drawTime)
        canvas.restore()
    }

    private fun drawLogicalChildren(canvas: Canvas, drawTime: Long) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            // Must be == VISIBLE: this manual SBS redraw otherwise paints
            // INVISIBLE children too (which broke the cursor auto-hide).
            if (child.visibility == VISIBLE) drawChild(canvas, child, drawTime)
        }
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        invalidate() // both halves must redraw when logical content changes
    }

    // ------------------------------------------------------------------
    //  Input
    // ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val kc = event.keyCode
        // Temple physical click = KEY event (X3 guide gotcha #1). Only SHORT
        // presses are ours: a LONG press belongs to the SYSTEM (it summons the
        // system menu), so downs are never consumed at all, a held press is
        // remembered via its auto-repeats, and only a genuinely short UP
        // becomes a tap.
        if (kc == KeyEvent.KEYCODE_BUTTON_A || kc == KeyEvent.KEYCODE_DPAD_CENTER) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) keyHeldLong = event.isLongPress
                    else keyHeldLong = true
                    return super.dispatchKeyEvent(event)
                }
                KeyEvent.ACTION_UP -> {
                    val heldMs = event.eventTime - event.downTime
                    if (keyHeldLong || event.isCanceled || heldMs >= LONG_PRESS_MS) {
                        keyHeldLong = false
                        return super.dispatchKeyEvent(event)
                    }
                    pokeCursor()
                    onTapUp()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val logicalWidth = logicalViewportWidth(width)
        if (logicalWidth <= 0) return super.dispatchTouchEvent(ev)
        return handleGlassesInput(ev, logicalWidth)
    }

    private fun handleGlassesInput(event: MotionEvent, logicalWidth: Int): Boolean {
        val rawX = event.getX(0)
        val rawY = event.getY(0)
        val localX =
            if (currentInputUsesMirroredCoordinates && rawX >= logicalWidth) rawX - logicalWidth else rawX
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_HOVER_ENTER -> {
                activeSide = classifyInputSide(event, logicalWidth)
                currentInputUsesMirroredCoordinates = isUnknownMirroredCoordinateEvent(event, logicalWidth)
                val startX =
                    if (currentInputUsesMirroredCoordinates && rawX >= logicalWidth) rawX - logicalWidth else rawX
                lastInputX = startX; lastInputY = rawY
                downInputX = startX; downInputY = rawY
                lastMoveTimeMs = 0L; lastMoveDist = 0f
                leftPullAccum = 0f; leftPullFired = false
                rightPullAccum = 0f; rightPullFired = false
                menuStepAccum = 0f
                menuDismissAccum = 0f
                vStepAccum = 0f
                menuAxisLock = AXIS_NONE
                if (activeSide == Side.LEFT_VOLUME) {
                    leftVolumeStartY = rawY
                    leftVolumeStart = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    return true
                }
                pokeCursor()
                updateCursor()
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                if (activeSide == Side.LEFT_VOLUME) { adjustVolume(rawY); return true }
                if (activeSide == Side.NONE) {
                    activeSide = classifyInputSide(event, logicalWidth)
                    if (activeSide == Side.LEFT_VOLUME) {
                        leftVolumeStartY = rawY
                        leftVolumeStart = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        adjustVolume(rawY)
                        return true
                    }
                }
                val dx = localX - lastInputX
                val dy = rawY - lastInputY
                if (abs(dx) < 0.35f && abs(dy) < 0.35f) return true
                if (menuNavigationActive?.invoke() == true) {
                    // Focus-driven navigation: pad travel becomes discrete focus
                    // steps; the cursor parks so pointing accuracy is never needed.
                    //
                    // AXIS LOCK: each gesture commits to its dominant axis after
                    // MENU_AXIS_LOCK_PX of total travel from the touch-down point,
                    // and only that axis can fire until liftoff. Without the lock,
                    // per-event classification let thumb wobble on a horizontal
                    // swipe bank vertical credit too — one swipe could fire a step
                    // AND a vertical move, which read as items being skipped.
                    if (menuAxisLock == AXIS_NONE) {
                        val totX = localX - downInputX
                        val totY = rawY - downInputY
                        if (abs(totX) >= MENU_AXIS_LOCK_PX || abs(totY) >= MENU_AXIS_LOCK_PX) {
                            // The temple pad is wide but SHORT: vertical travel is
                            // physically cramped and thumbs arc, so mixed-direction
                            // swipes are usually vertical intent. Only clearly flat
                            // swipes lock horizontal.
                            menuAxisLock = if (abs(totY) * VERTICAL_AXIS_BIAS > abs(totX))
                                AXIS_VERTICAL else AXIS_HORIZONTAL
                        }
                    }
                    // Bank travel while undecided or on the locked axis. A DELIBERATE
                    // reversal (contrary delta past the noise floor) restarts the
                    // count instead of crediting both ways — but zero/near-zero
                    // deltas from the off-axis component must not wipe the bank,
                    // or wobbly swipes silently lose their credit and go dead.
                    if (menuAxisLock != AXIS_VERTICAL) {
                        if (menuStepAccum != 0f && abs(dx) >= FLIP_RESET_MIN_PX &&
                            (menuStepAccum > 0f) != (dx > 0f)) menuStepAccum = 0f
                        menuStepAccum += dx
                    }
                    if (menuAxisLock != AXIS_HORIZONTAL) {
                        if (vStepAccum != 0f && abs(dy) >= FLIP_RESET_MIN_PX &&
                            (vStepAccum > 0f) != (dy > 0f)) vStepAccum = 0f
                        vStepAccum += dy
                        // Legacy dismiss accumulator: upward travel only, downward drains.
                        menuDismissAccum = (menuDismissAccum - dy).coerceAtLeast(0f)
                    }
                    when (menuAxisLock) {
                        AXIS_HORIZONTAL -> if (abs(menuStepAccum) >= MENU_STEP_PX) {
                            // One step per crossing, never banking the excess: a
                            // hard flick can't overshoot past the intended button,
                            // and the next step needs a fresh full swipe plus a
                            // beat of cooldown (a slow deliberate drag still walks
                            // the focus at a controlled pace).
                            if (event.eventTime - lastMenuStepMs >= MENU_STEP_COOLDOWN_MS) {
                                android.util.Log.i("TapReaderInput", "menu step H ${if (menuStepAccum > 0f) "+1" else "-1"}")
                                horizontalStepHandler?.invoke(if (menuStepAccum > 0f) 1 else -1)
                                lastMenuStepMs = event.eventTime
                            }
                            menuStepAccum = 0f
                        }
                        AXIS_VERTICAL -> if (verticalStepHandler != null) {
                            if (abs(vStepAccum) >= MENU_STEP_V_PX) {
                                if (event.eventTime - lastMenuStepMs >= MENU_STEP_COOLDOWN_MS) {
                                    android.util.Log.i("TapReaderInput", "menu step V ${if (vStepAccum > 0f) "+1" else "-1"}")
                                    verticalStepHandler?.invoke(if (vStepAccum > 0f) 1 else -1)
                                    lastMenuStepMs = event.eventTime
                                }
                                vStepAccum = 0f
                            }
                        } else if (menuDismissAccum >= MENU_DISMISS_PX) {
                            // Deliberate upward swipe dismisses the menu.
                            menuDismissAccum = 0f
                            menuDismissHandler?.invoke()
                        }
                    }
                    lastInputX = localX; lastInputY = rawY
                    stopEdgeScroll()
                    return true
                }
                preMoveCursorX = cursorX; preMoveCursorY = cursorY
                moveCursor(dx, dy, logicalWidth)
                pokeCursor()
                lastMoveDist = hypot(cursorX - preMoveCursorX, cursorY - preMoveCursorY)
                lastMoveTimeMs = event.eventTime
                lastInputX = localX; lastInputY = rawY
                updateEdgePull(dx, logicalWidth)
                if (contentInteractionBlocked?.invoke() == true) stopEdgeScroll() else updateEdgeScroll()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_HOVER_EXIT -> {
                if (activeSide == Side.LEFT_VOLUME) { activeSide = Side.NONE; return true }
                // Capacitive liftoff jump: undo a final micro-move so the cursor
                // stays put on release.
                if (activeSide == Side.RIGHT_CURSOR && lastMoveTimeMs != 0L &&
                    event.eventTime - lastMoveTimeMs <= LIFT_JUMP_WINDOW_MS &&
                    lastMoveDist <= LIFT_JUMP_MAX_PX
                ) {
                    cursorX = preMoveCursorX; cursorY = preMoveCursorY
                    updateCursor()
                }
                val moved = abs(localX - downInputX) > touchSlop || abs(rawY - downInputY) > touchSlop
                val ended = event.actionMasked == MotionEvent.ACTION_UP
                // A long stationary hold is the system's long-press, not a tap.
                val held = event.eventTime - event.downTime >= LONG_PRESS_MS
                if (!moved && ended && !held) onTapUp()
                currentInputUsesMirroredCoordinates = false
                stopEdgeScroll()
                leftPullAccum = 0f; leftPullFired = false
                rightPullAccum = 0f; rightPullFired = false
                activeSide = Side.NONE
                return true
            }
        }
        return true
    }

    /** Unified tap entry for touch taps and temple-key presses. */
    private fun onTapUp() {
        android.util.Log.i("TapReaderInput", "tapUp count=${tapCount + 1}")
        if (tapInterceptor?.invoke() == true) {
            // Consumed (e.g. stopped a recording); reset the tap sequence.
            cancelMultiTap()
            return
        }
        val now = SystemClock.uptimeMillis()
        val sincePrev = now - lastTapUpTime
        lastTapUpTime = now
        // Continue the sequence if this tap is inside the window, else start fresh.
        tapCount = if (multiTapRunnable != null && sincePrev in DOUBLE_TAP_MIN_GAP_MS..DOUBLE_TAP_WINDOW_MS)
            tapCount + 1 else 1
        multiTapRunnable?.let { removeCallbacks(it) }
        val r = Runnable {
            multiTapRunnable = null
            val n = tapCount
            tapCount = 0
            android.util.Log.i("TapReaderInput", "tap resolve n=$n at ($cursorX,$cursorY)")
            when {
                n >= 3 -> tripleTapHandler?.invoke()
                n == 2 -> doubleTapHandler?.invoke()
                else -> performCursorClick()
            }
        }
        multiTapRunnable = r
        // Wait one window after the latest tap so a further tap can extend the count.
        postDelayed(r, DOUBLE_TAP_WINDOW_MS + 20L)
    }

    private fun cancelMultiTap() {
        multiTapRunnable?.let { removeCallbacks(it) }
        multiTapRunnable = null
        tapCount = 0
        lastTapUpTime = 0L
    }

    private fun performCursorClick() {
        val now = SystemClock.uptimeMillis()
        if (now - lastClickTime < CLICK_DEBOUNCE_MS) { android.util.Log.i("TapReaderInput", "click debounced"); return }
        lastClickTime = now
        if (logicalClickHandler?.invoke(cursorX, cursorY) == true) { android.util.Log.i("TapReaderInput", "click consumed by handler"); return }
        android.util.Log.i("TapReaderInput", "click dispatched at ($cursorX,$cursorY)")
        clickAtCursor()
    }

    private fun clickAtCursor() {
        val target = content ?: return
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0)
        target.dispatchTouchEvent(down); down.recycle()
        postDelayed({
            val up = MotionEvent.obtain(t, t + 48L, MotionEvent.ACTION_UP, cursorX, cursorY, 0)
            target.dispatchTouchEvent(up); up.recycle()
        }, 48L)
    }

    private fun classifyInputSide(event: MotionEvent, logicalWidth: Int): Side {
        val name = runCatching {
            event.device?.name ?: InputDevice.getDevice(event.deviceId)?.name
        }.getOrNull().orEmpty()
        return when {
            name.contains("cyttsp6", ignoreCase = true) -> Side.LEFT_VOLUME
            name.contains("cyttsp5", ignoreCase = true) -> Side.RIGHT_CURSOR
            event.isFromSource(InputDevice.SOURCE_MOUSE) -> Side.RIGHT_CURSOR
            event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE -> Side.RIGHT_CURSOR
            else -> if (event.getX(0) < logicalWidth) Side.LEFT_VOLUME else Side.RIGHT_CURSOR
        }
    }

    private fun isUnknownMirroredCoordinateEvent(event: MotionEvent, logicalWidth: Int): Boolean {
        val name = runCatching {
            event.device?.name ?: InputDevice.getDevice(event.deviceId)?.name
        }.getOrNull().orEmpty()
        if (name.contains("cyttsp5", true) || name.contains("cyttsp6", true)) return false
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
        ) return false
        return event.getX(0) >= logicalWidth
    }

    private fun adjustVolume(rawY: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val steps = ((leftVolumeStartY - rawY) / 34f).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (leftVolumeStart + steps).coerceIn(0, max), 0)
    }

    private fun moveCursor(dx: Float, dy: Float, logicalWidth: Int) {
        cursorX = (cursorX + dx * CURSOR_SENSITIVITY).coerceIn(1f, logicalWidth - 1f)
        cursorY = (cursorY + dy * CURSOR_SENSITIVITY).coerceIn(1f, height - 1f)
        updateCursor()
    }

    private fun updateCursor() {
        cursorView.cursorX = cursorX
        cursorView.cursorY = cursorY
        cursorView.invalidate()
        invalidate()
    }

    /**
     * Left-edge back as a PULL: only accumulates while the cursor is pinned at the
     * far-left edge AND the finger is still pushing left (dx<0). Crossing the
     * threshold fires back once; any rightward move resets the pull.
     */
    private fun updateEdgePull(dx: Float, logicalWidth: Int) {
        // Left edge → pull left (back / exit dim).
        if (cursorX <= EDGE_PULL_ZONE_PX && dx < 0f) {
            if (!leftPullFired) {
                leftPullAccum += -dx
                if (leftPullAccum >= EDGE_PULL_THRESHOLD_PX) {
                    leftPullAccum = 0f; leftPullFired = true
                    leftEdgeBackHandler?.invoke()
                }
            }
        } else if (dx > 0f) leftPullAccum = 0f
        // Right edge → pull right (enter dim).
        if (cursorX >= logicalWidth - EDGE_PULL_ZONE_PX && dx > 0f) {
            if (!rightPullFired) {
                rightPullAccum += dx
                if (rightPullAccum >= EDGE_PULL_THRESHOLD_PX) {
                    rightPullAccum = 0f; rightPullFired = true
                    rightEdgePullHandler?.invoke()
                }
            }
        } else if (dx < 0f) rightPullAccum = 0f
    }

    /** Vertical edge scrolling (the "no scroll bar" scroll). */
    private fun updateEdgeScroll() {
        val band = EDGE_SCROLL_BAND_PX
        val maxY = height.toFloat()
        val up = ((band - cursorY) / band).coerceIn(0f, 1f)
        val down = ((cursorY - (maxY - band)) / band).coerceIn(0f, 1f)
        edgeScrollDy = ((down - up) * EDGE_SCROLL_MAX_STEP_PX).toInt()
        if (edgeScrollDy == 0) { stopEdgeScroll(); return }
        if (!edgeScrollActive) {
            edgeScrollActive = true
            android.util.Log.d("TapReaderInput", "edge scroll engaged dy=$edgeScrollDy cursorY=$cursorY")
            removeCallbacks(edgeScrollRunnable)
            post(edgeScrollRunnable)
        }
    }

    private fun stopEdgeScroll() {
        edgeScrollActive = false
        edgeScrollDy = 0
        removeCallbacks(edgeScrollRunnable)
    }

    private fun logicalViewportWidth(totalWidth: Int): Int = (totalWidth / 2).coerceAtLeast(0)

    private class CursorView(context: Context) : View(context) {
        var cursorX = 320f
        var cursorY = 240f
        private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2.5f; color = Color.BLACK
        }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.WHITE
        }
        init { visibility = VISIBLE; isClickable = false; isFocusable = false }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawCircle(cursorX, cursorY, 7f, outer)
            canvas.drawCircle(cursorX, cursorY, 7f, inner)
            canvas.drawLine(cursorX - 12f, cursorY, cursorX + 12f, cursorY, outer)
            canvas.drawLine(cursorX, cursorY - 12f, cursorX, cursorY + 12f, outer)
            canvas.drawLine(cursorX - 12f, cursorY, cursorX + 12f, cursorY, inner)
            canvas.drawLine(cursorX, cursorY - 12f, cursorX, cursorY + 12f, inner)
        }
    }

    companion object {
        private const val CURSOR_SENSITIVITY = 0.86f
        private const val CLICK_DEBOUNCE_MS = 350L
        private const val LIFT_JUMP_WINDOW_MS = 140L
        private const val LIFT_JUMP_MAX_PX = 48f
        private const val EDGE_SCROLL_BAND_PX = 44f
        // Left-edge back PULL: cursor must be within ZONE of the edge and the user
        // must push a cumulative THRESHOLD of leftward travel against it.
        private const val EDGE_PULL_ZONE_PX = 8f
        private const val EDGE_PULL_THRESHOLD_PX = 140f
        private const val EDGE_SCROLL_MAX_STEP_PX = 26f
        private const val EDGE_SCROLL_INTERVAL_MS = 33L
        // Double-tap timing per the X3 guide: gap 40–320 ms (40 ms floor filters
        // keycode echo of the same physical tap).
        private const val DOUBLE_TAP_MIN_GAP_MS = 40L
        private const val DOUBLE_TAP_WINDOW_MS = 320L
        // Horizontal pad travel per menu-focus step. Comfortable on the temple
        // pad: a deliberate swipe is one step, a full sweep crosses a few buttons.
        // (60 proved twitchy on-head; 110 + the horizontal-intent gate steadies it.)
        private const val MENU_STEP_PX = 110f
        // Minimum pause between focus steps — even a hard flick moves one button,
        // then the pad must travel a fresh MENU_STEP_PX after this beat.
        private const val MENU_STEP_COOLDOWN_MS = 220L
        // Upward pad travel that dismisses the menu bar (swipe-up-to-close).
        private const val MENU_DISMISS_PX = 90f
        // Total travel from touch-down before a gesture commits to one axis.
        // Below MENU_STEP_PX so the lock always decides before any step fires.
        private const val MENU_AXIS_LOCK_PX = 48f
        // Vertical pad travel per step — lower than horizontal because the pad
        // is short: a full-height flick only reports ~100–150px.
        private const val MENU_STEP_V_PX = 70f
        // Mixed-direction swipes lock vertical unless clearly flat (see above).
        private const val VERTICAL_AXIS_BIAS = 1.4f
        // Contrary per-event delta below this is wobble, not a reversal — it
        // must not reset the step accumulator.
        private const val FLIP_RESET_MIN_PX = 8f
        // Presses held at least this long belong to the SYSTEM (long-press
        // menu) — the app must neither consume them nor read them as taps.
        private const val LONG_PRESS_MS = 450L
        private const val AXIS_NONE = 0
        private const val AXIS_HORIZONTAL = 1
        private const val AXIS_VERTICAL = 2
        // Hide the crosshair after this long with no pad/temple activity.
        private const val CURSOR_HIDE_MS = 5_000L
    }
}

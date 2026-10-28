package dev.halone.app.pointer

import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import dev.halone.compositor.FreeCursor
import dev.halone.compositor.Scene
import dev.halone.compositor.StereoCompositor

/**
 * The cursor for pointing devices that move it rather than aim it: the phone
 * as a touchpad, and a physical mouse or trackpad the app has captured.
 *
 * It owns a [FreeCursor], keeps it inside the panels as they move, and hands
 * its direction to the compositor, which hit-tests and draws it like any
 * other pointer.
 */
class CursorInput(
    private val compositor: () -> StereoCompositor?,
    private val scene: () -> Scene?,
    private val actions: Actions,
) {
    interface Actions {
        /** The configured action for a gesture setting key. */
        fun action(key: String): String
        fun run(action: String)
        fun beginTouch(followPointer: Boolean)
        fun moveTouchBy(dx: Float, dy: Float)
        fun endTouch()
        fun grabStart()
        fun grabEnd()
        fun touchpadDegreesPerDp(): Float
        fun mouseDegreesPerCount(): Float
        fun movesWithWindows(): Boolean
    }

    val cursor = FreeCursor()

    var running = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var activeHold: String? = null

    private val frame = object : Runnable {
        override fun run() {
            handler.postDelayed(this, FRAME_MS)
            val scene = scene() ?: return
            val compositor = compositor()
            if (actions.movesWithWindows()) cursor.ride(compositor?.lastHit?.panel, scene) else cursor.stopRiding()
            cursor.clampTo(scene)
            compositor?.pointerDirection = cursor.direction()
        }
    }

    fun start() {
        if (running) return
        running = true
        recenter()
        handler.post(frame)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(frame)
        compositor()?.pointerDirection = null
    }

    /** Puts the cursor where the wearer is looking. */
    fun recenter() {
        val compositor = compositor() ?: return
        val scene = scene() ?: return
        cursor.placeAt(compositor.headYawRadians, compositor.headPitchRadians, scene)
        compositor.pointerDirection = cursor.direction()
    }

    private fun moveBy(rightDegrees: Float, upDegrees: Float) {
        val scene = scene() ?: return
        cursor.moveBy(rightDegrees, upDegrees, scene)
        compositor()?.pointerDirection = cursor.direction()
    }

    /** What the phone touchpad and a captured trackpad both mean. */
    val touchpad = TouchpadGestures(object : TouchpadGestures.Listener {
        private var scrolling = false

        override fun onMove(dxDp: Float, dyDp: Float) {
            val k = actions.touchpadDegreesPerDp()
            pointerMoved(dxDp * k, -dyDp * k, kotlin.math.hypot(dxDp, dyDp), TOUCHPAD_CLICK_SLOP_DP)
        }

        override fun onScroll(dxDp: Float, dyDp: Float) {
            if (!scrolling) {
                scrolling = true
                actions.beginTouch(followPointer = false)
            }
            actions.moveTouchBy(dxDp * SCROLL_PX_PER_DP, dyDp * SCROLL_PX_PER_DP)
        }

        override fun onScrollEnd() {
            if (!scrolling) return
            scrolling = false
            actions.endTouch()
        }

        override fun onTap(fingers: Int) = actions.run(actions.action("gesture.touchpad.tap$fingers"))

        override fun onHoldStart(fingers: Int) {
            val action = actions.action("gesture.touchpad.hold$fingers")
            activeHold = action
            if (action == "move-window") actions.grabStart() else actions.run(action)
        }

        override fun onHoldEnd(fingers: Int) {
            if (activeHold == "move-window") actions.grabEnd()
            activeHold = null
        }

        override fun onDragStart() = actions.beginTouch(followPointer = true)

        override fun onDragEnd() = actions.endTouch()
    })

    /** Schedules [TouchpadGestures.tick] for whoever feeds [touchpad]. */
    private val touchpadTick = Runnable { decideTouchpad() }

    fun decideTouchpad() {
        touchpad.tick(android.os.SystemClock.uptimeMillis())
        handler.removeCallbacks(touchpadTick)
        val deadline = touchpad.nextDeadline() ?: return
        handler.postDelayed(touchpadTick, (deadline - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    private var primaryDown = false
    private var heldRight = 0f
    private var heldUp = 0f
    private var heldTravel = 0f
    private var holdingStill = false

    /**
     * Pressing a trackpad down rolls the finger, and a press that moves is a
     * drag, which the page takes as anything but a click. So while the button
     * is down the cursor stays put until the motion adds up to [slop], and then
     * catches up all at once.
     */
    private fun pointerMoved(rightDegrees: Float, upDegrees: Float, travel: Float, slop: Float) {
        if (!holdingStill) return moveBy(rightDegrees, upDegrees)
        heldRight += rightDegrees
        heldUp += upDegrees
        heldTravel += travel
        if (heldTravel < slop) return
        holdingStill = false
        moveBy(heldRight, heldUp)
    }

    /**
     * An event from a captured pointing device. A mouse reports relative motion;
     * a trackpad reports its fingers, which go through [touchpad] like the
     * phone's own, and its physical click presses like a mouse button.
     */
    /** Set to see every captured event but plain motion, for working out what a new device sends. */
    var trace: ((String) -> Unit)? = null

    fun onCaptured(event: MotionEvent): Boolean {
        trace?.let { log ->
            if (event.actionMasked != MotionEvent.ACTION_MOVE || event.buttonState != 0) {
                log(
                    "  captured ${MotionEvent.actionToString(event.action)} source=0x${Integer.toHexString(event.source)} " +
                        "buttons=${event.buttonState} actionButton=${event.actionButton} pointers=${event.pointerCount} " +
                        "device='${event.device?.name}'",
                )
            }
        }
        if (onSecondaryButton(event)) return true
        if (onMiddleButton(event)) return true
        if (event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) {
            if (!onButton(event)) {
                touchpad.feed(event, trackpadDpPerUnit(event))
                decideTouchpad()
            }
            return true
        }
        if (onButton(event)) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                var dx = 0f
                var dy = 0f
                for (i in 0 until event.historySize) {
                    dx += relative(event, MotionEvent.AXIS_RELATIVE_X, event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, i), event.getHistoricalX(i))
                    dy += relative(event, MotionEvent.AXIS_RELATIVE_Y, event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, i), event.getHistoricalY(i))
                }
                dx += relative(event, MotionEvent.AXIS_RELATIVE_X, event.getAxisValue(MotionEvent.AXIS_RELATIVE_X), event.x)
                dy += relative(event, MotionEvent.AXIS_RELATIVE_Y, event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y), event.y)
                val k = actions.mouseDegreesPerCount()
                pointerMoved(dx * k, -dy * k, kotlin.math.hypot(dx, dy), MOUSE_CLICK_SLOP_COUNTS)
            }
            MotionEvent.ACTION_SCROLL -> {
                val hit = compositor()?.lastHit ?: return true
                hit.panel.engine.dispatchWheel(
                    hit.hit.x,
                    hit.hit.y,
                    event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                    event.getAxisValue(MotionEvent.AXIS_VSCROLL),
                )
            }
        }
        return true
    }

    /**
     * A captured mouse's motion is relative either way, but which field carries
     * it has varied: the relative axis when the device reports one, else X and Y.
     */
    private fun relative(event: MotionEvent, axis: Int, axisValue: Float, plain: Float): Float =
        if (event.device?.getMotionRange(axis) != null) axisValue else plain

    private var secondaryDown = false

    /** The secondary button, a right click or a trackpad's two-finger click, asks for the context menu as it goes down. */
    private fun onSecondaryButton(event: MotionEvent): Boolean {
        val pressed = event.buttonState and MotionEvent.BUTTON_SECONDARY != 0
        if (pressed == secondaryDown) return false
        secondaryDown = pressed
        if (pressed) {
            touchpad.consume()
            actions.run("context-menu")
        }
        return event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
    }

    private var middleDown = false

    /** The middle button clicks as it goes down: in the browser, a link opens in a new tab. */
    private fun onMiddleButton(event: MotionEvent): Boolean {
        val pressed = event.buttonState and MotionEvent.BUTTON_TERTIARY != 0
        if (pressed == middleDown) return false
        middleDown = pressed
        if (pressed) {
            touchpad.consume()
            actions.run("middle-click")
        }
        return event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
    }

    /** The primary button presses a finger onto the page, which a quick release makes a click. */
    private fun onButton(event: MotionEvent): Boolean {
        val pressed = event.buttonState and MotionEvent.BUTTON_PRIMARY != 0
        if (pressed == primaryDown) return false
        primaryDown = pressed
        holdingStill = pressed
        heldRight = 0f
        heldUp = 0f
        heldTravel = 0f
        if (pressed) {
            touchpad.consume()
            actions.beginTouch(followPointer = true)
        } else {
            actions.endTouch()
        }
        return event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
    }

    /** Trackpads report in their own units; their resolution says how many to the millimetre. */
    private fun trackpadDpPerUnit(event: MotionEvent): Float {
        val range = event.device?.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_TOUCHPAD)
        val resolution = range?.resolution ?: 0f
        return if (resolution > 0f) DP_PER_MM / resolution
        else TRACKPAD_WIDTH_DP / (range?.range?.takeIf { it > 0f } ?: TRACKPAD_WIDTH_DP)
    }

    private companion object {
        const val FRAME_MS = 8L
        const val SCROLL_PX_PER_DP = 2f
        const val DP_PER_MM = 160f / 25.4f
        const val TRACKPAD_WIDTH_DP = 500f
        const val TOUCHPAD_CLICK_SLOP_DP = 8f
        const val MOUSE_CLICK_SLOP_COUNTS = 6f
    }
}

package dev.halone.compositor

import dev.halone.glasses.math.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * A cursor moved by relative steps — a touchpad, a mouse — rather than aimed
 * like head gaze or the gyro pointer.
 *
 * It lives at a heading and elevation in the world and cannot leave what can
 * be clicked ([PanelScene.cursorReach]: the windows and their title bars): it
 * stops at the outer edge in either direction, at the top of the highest and
 * at the bottom of the lowest, so it can never get lost off to the side.
 * Between windows it is free, and is drawn in the gap. While a window is held
 * or resized it is not kept anywhere, so the window can go anywhere.
 *
 * Angles are native: positive yaw is to the left, positive pitch up.
 */
class FreeCursor {
    @Volatile
    var yawRadians = 0f
        private set

    @Volatile
    var pitchRadians = 0f
        private set

    /** Moves by [rightDegrees] and [upDegrees], as the hand moved, then keeps to the panels. */
    fun moveBy(rightDegrees: Float, upDegrees: Float, scene: PanelScene<*>) {
        clampTo(scene)
        val step = -Math.toRadians(rightDegrees.toDouble()).toFloat()
        // The step is limited before it is wrapped, so a fast flick past ±180°
        // cannot come out on the far side of the span.
        val span = span(scene)
        yawRadians = ScenePanel.wrapPi(yawRadians + if (span == null) step else step.coerceIn(span.low, span.high))
        pitchRadians += Math.toRadians(upDegrees.toDouble()).toFloat()
        clampTo(scene)
    }

    fun placeAt(yawRadians: Float, pitchRadians: Float, scene: PanelScene<*>) {
        this.yawRadians = ScenePanel.wrapPi(yawRadians)
        this.pitchRadians = pitchRadians
        clampTo(scene)
    }

    /**
     * Pulls the cursor back inside the panels' extent. Panels move — following,
     * layout, a window closing — so this is for every frame, not only for moves.
     */
    fun clampTo(scene: PanelScene<*>) {
        val span = span(scene) ?: run {
            pitchRadians = pitchRadians.coerceIn(-MAX_PITCH, MAX_PITCH)
            return
        }
        if (span.low > 0f) yawRadians = ScenePanel.wrapPi(yawRadians + span.low)
        if (span.high < 0f) yawRadians = ScenePanel.wrapPi(yawRadians + span.high)
        pitchRadians = pitchRadians.coerceIn(span.bottom, span.top)
    }

    /** Headings relative to the cursor's own, so the seam behind the wearer never splits the span. */
    private class Span(val low: Float, val high: Float, val bottom: Float, val top: Float)

    private fun span(scene: PanelScene<*>): Span? {
        if (scene.grabbed != null || scene.resizing != null) return null
        val reach = scene.cursorReach()
        if (reach.isEmpty()) return null
        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        var bottom = Float.MAX_VALUE
        var top = -Float.MAX_VALUE
        for (region in reach) {
            val offset = ScenePanel.wrapPi(region.yaw - yawRadians)
            // A hair inside the edge, where the hit test still lands on it.
            low = minOf(low, offset - region.halfWidth + INSET)
            high = maxOf(high, offset + region.halfWidth - INSET)
            bottom = minOf(bottom, region.bottom + INSET)
            top = maxOf(top, region.top - INSET)
        }
        return Span(low, high, bottom, top)
    }

    fun direction(): Vec3 {
        val cp = cos(pitchRadians)
        return Vec3(-sin(yawRadians) * cp, sin(pitchRadians), -cos(yawRadians) * cp)
    }

    private companion object {
        val MAX_PITCH = Math.toRadians(60.0).toFloat()
        val INSET = Math.toRadians(0.05).toFloat()
    }
}

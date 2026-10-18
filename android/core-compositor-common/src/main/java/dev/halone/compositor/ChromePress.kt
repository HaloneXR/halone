package dev.halone.compositor

import dev.halone.glasses.math.Vec3
import kotlin.math.acos

/**
 * A press on a window's chrome, from the pointer going down to it coming up.
 *
 * A press on the bar picks the window up at once, so a drag carries it from
 * the first movement; a press that never moves is a click and is undone on
 * release. A press on a corner resizes. A press on a button only counts if it
 * is released on it.
 */
class ChromePress<P : ScenePanel>(
    private val scene: PanelScene<P>,
    private val actions: Actions<P>,
) {
    interface Actions<P : ScenePanel> {
        /** The content takes its new size; the panel must report it before this returns. */
        fun resizeContent(panel: P, widthPx: Int, heightPx: Int)

        /** Pins or unpins through whatever owns the window; false to leave it to the scene. */
        fun pin(panel: P, pinned: Boolean): Boolean = false

        fun back(panel: P)

        fun close(panel: P)

        fun dropped(panel: P) {}

        fun resized(panel: P, oldWidthPx: Int, oldHeightPx: Int) {}
    }

    private var press: PanelSceneHit<P>? = null
    private var pressAim: Vec3? = null

    val active: Boolean get() = press != null

    /** Forgets a press whose release can no longer be acted on. */
    fun abandon() {
        press = null
    }

    fun press(hit: PanelSceneHit<P>, aim: Vec3) {
        press = hit
        pressAim = aim
        if (hit.part == HitPart.TITLE_BAR && scene.grabbed == null) scene.beginGrab(hit.panel, aim)
        hit.part.corner?.let { scene.beginResize(hit.panel, it) }
    }

    /** Ends the press; false if there was none. [now] is what the pointer is on at release. */
    fun release(aim: Vec3, now: PanelSceneHit<P>?, headYawRadians: Float): Boolean {
        val press = press ?: return false
        this.press = null
        if (press.part == HitPart.TITLE_BAR) {
            if (scene.grabbed !== press.panel) return true
            if (degreesBetween(pressAim, aim) < CLICK_SLOP_DEGREES) {
                scene.cancelGrab()
            } else {
                scene.endGrab(headYawRadians)?.let { actions.dropped(it) }
            }
            return true
        }
        if (press.part.corner != null) {
            val drag = scene.endResize() ?: return true
            val panel = drag.panel
            val oldWidth = panel.widthPx
            val oldHeight = panel.heightPx
            if (drag.widthPx == oldWidth && drag.heightPx == oldHeight) return true
            actions.resizeContent(panel, drag.widthPx, drag.heightPx)
            scene.resized(panel, oldWidth, oldHeight, drag.corner.opposite)
            actions.resized(panel, oldWidth, oldHeight)
            return true
        }
        if (now?.panel === press.panel && now.part == press.part) activate(press.panel, press.part)
        return true
    }

    /** What a click or tap on [part] of [panel]'s chrome does. */
    fun activate(panel: P, part: HitPart) {
        when (part) {
            HitPart.PIN -> {
                val pinned = panel.follow.enabled
                if (!actions.pin(panel, pinned)) scene.setFollow(panel, panel.follow.copy(enabled = !pinned))
            }
            HitPart.BACK -> actions.back(panel)
            HitPart.CLOSE -> actions.close(panel)
            else -> Unit
        }
    }

    companion object {
        const val CLICK_SLOP_DEGREES = 1f

        fun degreesBetween(a: Vec3?, b: Vec3?): Float {
            if (a == null || b == null || a.length < 1e-6f || b.length < 1e-6f) return 0f
            val cos = (a.x * b.x + a.y * b.y + a.z * b.z) / (a.length * b.length)
            return Math.toDegrees(acos(cos.coerceIn(-1f, 1f)).toDouble()).toFloat()
        }
    }
}

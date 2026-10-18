package dev.halone.desktop.compositor.producer

import dev.halone.compositor.PanelScene
import dev.halone.desktop.compositor.render.DesktopPanel
import dev.halone.glasses.math.Vec3
import org.lwjgl.glfw.GLFW.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A pointer the Mac's mouse moves through the scene, and the producer input it
 * turns into: world-locked, kept within reach of the head, hit-tested against
 * the panels, and forwarded to whichever producer panel is under it.
 */
class InputRouter(
    private val host: ProducerHost,
    private val scene: PanelScene<DesktopPanel>,
    private val halfFovYawRadians: Float,
    private val halfFovPitchRadians: Float,
) {
    var yawRadians = 0f
        private set
    var pitchRadians = 0f
        private set

    /** Where the pointer last met a panel, for drawing the cursor; null when it is over nothing. */
    var hitDistanceMetres: Float? = null
        private set

    private var hovered: Int? = null
    private var focused: Int? = null
    private var buttons = 0
    private var lastClickAt = 0.0
    private var lastClickButton = -1
    private var clicks = 0
    private var pendingKeyCode: Int? = null
    private val typed = HashMap<Int, Int>()

    val direction: Vec3
        get() = Vec3(-sin(yawRadians) * cos(pitchRadians), sin(pitchRadians), -cos(yawRadians) * cos(pitchRadians))

    /** Points straight at pixel ([xPx], [yPx]) of [panel], ignoring the reach limit; for scripted tests. */
    fun pointAt(panel: DesktopPanel, xPx: Float, yPx: Float, modifiers: Int = 0) {
        val pose = panel.pose
        val x = (xPx / panel.widthPx - 0.5f) * pose.widthMetres
        val y = (0.5f - yPx / panel.heightPx) * pose.heightMetres
        val z = -pose.distanceMetres
        val cp = cos(pose.pitchRadians); val sp = sin(pose.pitchRadians)
        val y1 = y * cp - z * sp
        val z1 = y * sp + z * cp
        val cy = cos(pose.yawRadians); val sy = sin(pose.yawRadians)
        val x2 = x * cy + z1 * sy
        val z2 = -x * sy + z1 * cy
        yawRadians = kotlin.math.atan2(-x2, -z2)
        pitchRadians = kotlin.math.atan2(y1, kotlin.math.sqrt(x2 * x2 + z2 * z2))
        hover(modifiers)
    }

    fun recentre(headYaw: Float, headPitch: Float) {
        yawRadians = headYaw
        pitchRadians = headPitch
    }

    /** Moves the pointer by mouse pixels and keeps it within sight of the head. */
    fun move(dx: Double, dy: Double, headYaw: Float, headPitch: Float, modifiers: Int) {
        yawRadians -= (dx * RADIANS_PER_PIXEL).toFloat()
        pitchRadians = (pitchRadians - (dy * RADIANS_PER_PIXEL).toFloat()).coerceIn(-1.3f, 1.3f)
        var offset = wrap(yawRadians - headYaw)
        offset = offset.coerceIn(-halfFovYawRadians, halfFovYawRadians)
        yawRadians = headYaw + offset
        pitchRadians = pitchRadians.coerceIn(headPitch - halfFovPitchRadians, headPitch + halfFovPitchRadians)
        hover(modifiers)
    }

    /** Updates where the pointer meets the scene, for drawing it, without telling any producer. */
    fun refreshHit() {
        hitDistanceMetres = scene.hitTest(direction)?.distanceMetres
    }

    private fun hover(modifiers: Int) {
        val hit = scene.hitTest(direction)
        val index = hit?.panel?.producerIndex()
        hitDistanceMetres = hit?.distanceMetres
        hovered?.takeIf { it != index }?.let { host.input(it, ProducerHost.INPUT_LEAVE, 0f, 0f) }
        hovered = index
        if (hit != null && index != null) {
            host.input(index, ProducerHost.INPUT_MOVE, hit.hit.x, hit.hit.y, button = buttons, modifiers = macFlags(modifiers))
        }
    }

    fun button(button: Int, pressed: Boolean, modifiers: Int, now: Double) {
        val hit = scene.hitTest(direction) ?: return
        val index = hit.panel.producerIndex() ?: return
        val mask = 1 shl button
        if (pressed) {
            clicks = if (button == lastClickButton && now - lastClickAt < DOUBLE_CLICK_SECONDS) clicks + 1 else 1
            lastClickAt = now
            lastClickButton = button
            buttons = buttons or mask
            focused = index
        } else {
            buttons = buttons and mask.inv()
        }
        host.input(index, if (pressed) ProducerHost.INPUT_DOWN else ProducerHost.INPUT_UP, hit.hit.x, hit.hit.y,
            button = button, clicks = clicks, modifiers = macFlags(modifiers))
    }

    fun scroll(dx: Double, dy: Double, modifiers: Int) {
        val hit = scene.hitTest(direction) ?: return
        val index = hit.panel.producerIndex() ?: return
        host.input(index, ProducerHost.INPUT_WHEEL, hit.hit.x, hit.hit.y,
            dx = (dx * WHEEL_PIXELS).toFloat(), dy = (dy * WHEEL_PIXELS).toFloat(), modifiers = macFlags(modifiers))
    }

    /**
     * Keys go to the panel last clicked. A key that types something waits for
     * GLFW's character callback, which follows it, so the producer gets the key
     * and its character together; anything else goes straight through.
     */
    fun key(key: Int, scancode: Int, action: Int, modifiers: Int) {
        val target = focused ?: return
        when (action) {
            GLFW_PRESS, GLFW_REPEAT -> {
                val special = SPECIAL_CHARACTERS[key]
                if (special != null || glfwGetKeyName(key, scancode) == null || modifiers and (GLFW_MOD_CONTROL or GLFW_MOD_SUPER) != 0) {
                    val character = special ?: glfwGetKeyName(key, scancode)?.firstOrNull()?.code ?: 0
                    typed[scancode] = character
                    host.input(target, ProducerHost.INPUT_KEY_DOWN, 0f, 0f, modifiers = macFlags(modifiers),
                        keyCode = scancode, character = character, unmodified = character)
                } else {
                    pendingKeyCode = scancode
                }
            }
            GLFW_RELEASE -> {
                val character = typed.remove(scancode) ?: 0
                host.input(target, ProducerHost.INPUT_KEY_UP, 0f, 0f, modifiers = macFlags(modifiers),
                    keyCode = scancode, character = character, unmodified = character)
            }
        }
    }

    fun character(codepoint: Int, modifiers: Int) {
        val target = focused ?: return
        val keyCode = pendingKeyCode ?: return
        pendingKeyCode = null
        typed[keyCode] = codepoint
        host.input(target, ProducerHost.INPUT_KEY_DOWN, 0f, 0f, modifiers = macFlags(modifiers),
            keyCode = keyCode, character = codepoint, unmodified = codepoint)
    }

    private fun DesktopPanel.producerIndex(): Int? = id.removePrefix("producer-").takeIf { it != id }?.toIntOrNull()

    private fun wrap(angle: Float): Float {
        var a = angle
        while (a > PI) a -= (2 * PI).toFloat()
        while (a < -PI) a += (2 * PI).toFloat()
        return a
    }

    private companion object {
        const val RADIANS_PER_PIXEL = 0.0012
        const val WHEEL_PIXELS = 40.0
        const val DOUBLE_CLICK_SECONDS = 0.4

        fun macFlags(glfw: Int): Int {
            var flags = 0
            if (glfw and GLFW_MOD_SHIFT != 0) flags = flags or (1 shl 17)
            if (glfw and GLFW_MOD_CONTROL != 0) flags = flags or (1 shl 18)
            if (glfw and GLFW_MOD_ALT != 0) flags = flags or (1 shl 19)
            if (glfw and GLFW_MOD_SUPER != 0) flags = flags or (1 shl 20)
            if (glfw and GLFW_MOD_CAPS_LOCK != 0) flags = flags or (1 shl 16)
            return flags
        }

        /** What AppKit reports as the character for keys that type nothing printable. */
        val SPECIAL_CHARACTERS = mapOf(
            GLFW_KEY_ENTER to 0x0D, GLFW_KEY_KP_ENTER to 0x03, GLFW_KEY_TAB to 0x09, GLFW_KEY_BACKSPACE to 0x7F,
            GLFW_KEY_ESCAPE to 0x1B, GLFW_KEY_DELETE to 0xF728, GLFW_KEY_UP to 0xF700, GLFW_KEY_DOWN to 0xF701,
            GLFW_KEY_LEFT to 0xF702, GLFW_KEY_RIGHT to 0xF703, GLFW_KEY_HOME to 0xF729, GLFW_KEY_END to 0xF72B,
            GLFW_KEY_PAGE_UP to 0xF72C, GLFW_KEY_PAGE_DOWN to 0xF72D, GLFW_KEY_SPACE to 0x20,
        )
    }
}

package dev.halone.desktop.compositor.producer

import dev.halone.compositor.ChromePress
import dev.halone.compositor.FreeCursor
import dev.halone.compositor.HitPart
import dev.halone.compositor.PanelScene
import dev.halone.desktop.compositor.render.DesktopPanel
import dev.halone.glasses.math.Vec3
import org.lwjgl.glfw.GLFW.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Mac's mouse and keyboard, as producer input: the mouse drives a
 * [FreeCursor] (world-locked, kept to what can be clicked, as the touchpad does
 * on Android), which is hit-tested against the panels and forwarded to
 * whichever producer panel is under it.
 */
class InputRouter(
    private val host: ProducerHost?,
    private val scene: PanelScene<DesktopPanel>,
    chromeActions: ChromePress.Actions<DesktopPanel>,
) {
    private val cursor = FreeCursor()
    private val chrome = ChromePress(scene, chromeActions)

    val yawRadians: Float get() = cursor.yawRadians
    val pitchRadians: Float get() = cursor.pitchRadians

    private var hovered: Int? = null
    private var focused: Int? = null
    private var buttons = 0
    private var lastClickAt = 0.0
    private var lastClickButton = -1
    private var clicks = 0
    private var pendingKeyCode: Int? = null
    private val typed = HashMap<Int, Int>()

    val direction: Vec3 get() = cursor.direction()

    /** Points straight at pixel ([xPx], [yPx]) of [panel]; for scripted tests. */
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
        cursor.placeAt(kotlin.math.atan2(-x2, -z2), kotlin.math.atan2(y1, kotlin.math.sqrt(x2 * x2 + z2 * z2)), scene)
        hover(modifiers)
    }

    /** Puts the cursor where the wearer is looking, as CursorInput.recenter does. */
    fun recentre(headYaw: Float, headPitch: Float) = cursor.placeAt(headYaw, headPitch, scene)

    /** Degrees of cursor travel per mouse count; Android's `input.mouseSpeed` default. */
    var degreesPerCount = DEFAULT_MOUSE_SPEED

    private var holdingStill = false
    private var heldRight = 0f
    private var heldUp = 0f
    private var heldTravel = 0f

    /**
     * Ported from CursorInput.pointerMoved: while a button is down the cursor
     * stays put until the motion adds up to the click slop, then catches up at
     * once, so a press that rolls the finger is still a click.
     */
    fun move(dx: Double, dy: Double, modifiers: Int) {
        val right = (dx * degreesPerCount).toFloat()
        val up = (-dy * degreesPerCount).toFloat()
        if (chrome.active) {
            cursor.moveBy(right, up, scene)
            return
        }
        if (holdingStill) {
            heldRight += right
            heldUp += up
            heldTravel += kotlin.math.hypot(dx, dy).toFloat()
            if (heldTravel < MOUSE_CLICK_SLOP_COUNTS) return
            holdingStill = false
            cursor.moveBy(heldRight, heldUp, scene)
        } else {
            cursor.moveBy(right, up, scene)
        }
        hover(modifiers)
    }

    /** Keeps the cursor inside the panels as they move. Every frame. */
    fun clamp() = cursor.clampTo(scene)

    private var keyTarget: Int? = null

    /** Where keys go, as on Android: the menu, the launcher when it shows, or the focused window. */
    private fun keyTarget(): Int? = scene.keyTarget?.producerIndex ?: focused

    /** Tells the producer when keys start going to another panel, so its page has the keyboard focus. Call every frame. */
    fun followKeyTarget() {
        val target = scene.keyTarget?.producerIndex ?: return
        if (target == keyTarget) return
        keyTarget = target
        host?.input(target, ProducerHost.INPUT_FOCUS, 0f, 0f)
    }

    private fun hover(modifiers: Int) {
        val hit = scene.hitTest(direction)?.takeIf { it.part == HitPart.CONTENT }
        val index = hit?.panel?.producerIndex
        hovered?.takeIf { it != index }?.let { host?.input(it, ProducerHost.INPUT_LEAVE, 0f, 0f) }
        hovered = index
        if (hit != null && index != null) {
            host?.input(index, ProducerHost.INPUT_MOVE, hit.hit.x, hit.hit.y, button = buttons, modifiers = macFlags(modifiers))
        }
    }

    fun button(button: Int, pressed: Boolean, modifiers: Int, now: Double, headYawRadians: Float) {
        holdingStill = pressed && button == 0
        heldRight = 0f
        heldUp = 0f
        heldTravel = 0f
        val hit = scene.hitTest(direction)
        if (button == 0) {
            if (!pressed && chrome.release(direction, hit, headYawRadians)) return
            if (pressed && hit != null && hit.part != HitPart.CONTENT) {
                holdingStill = false
                chrome.press(hit, direction)
                return
            }
        }
        if (hit == null || hit.part != HitPart.CONTENT) return
        val host = host ?: return
        val index = hit.panel.producerIndex ?: return
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
        val hit = scene.hitTest(direction)?.takeIf { it.part == HitPart.CONTENT } ?: return
        val index = hit.panel.producerIndex ?: return
        val host = host ?: return
        host.input(index, ProducerHost.INPUT_WHEEL, hit.hit.x, hit.hit.y,
            dx = (dx * WHEEL_PIXELS).toFloat(), dy = (dy * WHEEL_PIXELS).toFloat(), modifiers = macFlags(modifiers))
    }

    /**
     * Keys go to [keyTarget]. A key that types something waits for
     * GLFW's character callback, which follows it, so the producer gets the key
     * and its character together; anything else goes straight through.
     */
    fun key(key: Int, scancode: Int, action: Int, modifiers: Int) {
        val target = keyTarget() ?: return
        when (action) {
            GLFW_PRESS, GLFW_REPEAT -> {
                val special = SPECIAL_CHARACTERS[key]
                if (special != null || glfwGetKeyName(key, scancode) == null || modifiers and (GLFW_MOD_CONTROL or GLFW_MOD_SUPER) != 0) {
                    val character = special ?: glfwGetKeyName(key, scancode)?.firstOrNull()?.code ?: 0
                    typed[scancode] = character
                    host?.input(target, ProducerHost.INPUT_KEY_DOWN, 0f, 0f, modifiers = macFlags(modifiers),
                        keyCode = scancode, character = character, unmodified = character)
                } else {
                    pendingKeyCode = scancode
                }
            }
            GLFW_RELEASE -> {
                val character = typed.remove(scancode) ?: 0
                host?.input(target, ProducerHost.INPUT_KEY_UP, 0f, 0f, modifiers = macFlags(modifiers),
                    keyCode = scancode, character = character, unmodified = character)
            }
        }
    }

    fun character(codepoint: Int, modifiers: Int) {
        val target = keyTarget() ?: return
        val keyCode = pendingKeyCode ?: return
        pendingKeyCode = null
        typed[keyCode] = codepoint
        host?.input(target, ProducerHost.INPUT_KEY_DOWN, 0f, 0f, modifiers = macFlags(modifiers),
            keyCode = keyCode, character = codepoint, unmodified = codepoint)
    }


    private companion object {
        const val DEFAULT_MOUSE_SPEED = 0.04
        const val MOUSE_CLICK_SLOP_COUNTS = 6f
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

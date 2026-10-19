package dev.halone.desktop.compositor.mac

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaMonitor
import org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaWindow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where the glasses sit in the Mac's display arrangement. While Halone shows on them they
 * touch the other displays only at a corner, as XREAL's own app leaves them, so neither
 * the cursor nor a dragged window wanders onto them; they go back when the result is closed.
 */
class MacDisplays private constructor(private val display: Int, private val x: Int, private val y: Int) : AutoCloseable {
    private val restored = AtomicBoolean()

    override fun close() {
        if (restored.compareAndSet(false, true)) native.halone_display_move(display, x, y)
    }

    @Suppress("FunctionName")
    private interface Bridge : Library {
        fun halone_display_origin(display: Int, x: IntByReference, y: IntByReference): Int
        fun halone_display_move(display: Int, x: Int, y: Int): Int
        fun halone_display_corner(display: Int, x: IntByReference, y: IntByReference): Int
        fun halone_display_beside(display: Int, x: IntByReference, y: IntByReference): Int
        fun halone_cursor_to_main()
        fun halone_window_overscan(window: Long, display: Int, margin: Int): Int
    }

    companion object {
        private val native by lazy { Native.load("halone_bridge", Bridge::class.java) }

        /** Tucks the display GLFW knows as [monitor] into a corner, until the result is closed. */
        fun tuck(monitor: Long): MacDisplays? {
            val display = glfwGetCocoaMonitor(monitor)
            val x = IntByReference()
            val y = IntByReference()
            val cornerX = IntByReference()
            val cornerY = IntByReference()
            if (native.halone_display_origin(display, x, y) != 0) return null
            if (native.halone_display_corner(display, cornerX, cornerY) != 0) return null
            // Still in the corner, from a run that was killed outright: they go back beside the others.
            if (x.value == cornerX.value && y.value == cornerY.value && native.halone_display_beside(display, x, y) != 0) return null
            if (native.halone_display_move(display, cornerX.value, cornerY.value) != 0) return null
            return MacDisplays(display, x.value, y.value)
        }

        /**
         * Grows the fullscreen [window] on [monitor] by [OVERSCAN] on every side, so the outline
         * macOS draws around windows falls off the display. Turning off the window's shadow
         * removes the outline too, but then macOS scans the window out directly and its drawables
         * come back a refresh later, which held Halone to 40 fps.
         */
        fun overscan(window: Long, monitor: Long): Int =
            if (native.halone_window_overscan(glfwGetCocoaWindow(window), glfwGetCocoaMonitor(monitor), OVERSCAN) == 0) OVERSCAN else 0

        private const val OVERSCAN = 1

        /** The cursor to the middle of the display with the menu bar. */
        fun cursorToMain() = native.halone_cursor_to_main()
    }
}

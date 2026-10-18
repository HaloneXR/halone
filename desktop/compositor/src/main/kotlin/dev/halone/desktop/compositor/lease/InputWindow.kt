package dev.halone.desktop.compositor.lease

import org.lwjgl.glfw.GLFW.*

/**
 * The desktop window that takes the keyboard and mouse while the glasses are leased.
 *
 * Wayland only shows, and focuses, a window that has drawn something. Giving it a
 * Vulkan surface would put the Wayland WSI on the compositor's instance, and RADV
 * then drops VK_GOOGLE_display_timing for the leased display too; an OpenGL ES
 * context swapping an untouched opaque buffer maps it with nothing shared.
 */
object InputWindow {
    fun create(title: String): Long {
        glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_ES_API)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2)
        glfwWindowHint(GLFW_ALPHA_BITS, 0)
        val window = glfwCreateWindow(640, 360, title, 0, 0)
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API)
        check(window != 0L) { "could not create the input window" }
        glfwMakeContextCurrent(window)
        glfwSwapInterval(0)
        glfwSetWindowRefreshCallback(window) { glfwSwapBuffers(it) }
        glfwSetFramebufferSizeCallback(window) { w, _, _ -> glfwSwapBuffers(w) }
        glfwSwapBuffers(window)
        return window
    }
}

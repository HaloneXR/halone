package dev.halone.desktop.compositor

import dev.halone.compositor.PanelPose
import dev.halone.desktop.compositor.render.DesktopCompositor
import dev.halone.desktop.compositor.render.DesktopPanel
import dev.halone.desktop.compositor.render.StereoRenderer
import dev.halone.desktop.compositor.render.TestPattern
import dev.halone.desktop.compositor.vk.FrameLoop
import dev.halone.desktop.compositor.vk.StaticTexture
import dev.halone.desktop.compositor.vk.VulkanContext
import dev.halone.glasses.api.Optics
import dev.halone.glasses.api.UsbIdentity
import dev.halone.glasses.math.Quaternion
import dev.halone.glasses.registry.GlassesRegistry
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWVulkan.glfwVulkanSupported
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.VK10.vkDeviceWaitIdle
import kotlin.math.sin
import kotlin.system.exitProcess

private const val PANEL_VERTICAL_FILL = 0.75f
private const val MOUSE_DEGREES_PER_PIXEL = 0.08f

private class Options(args: Array<String>) {
    val seconds = value(args, "--seconds")?.toDouble()
    val sweep = "--sweep" in args
    val glasses = "--glasses" in args

    private fun value(args: Array<String>, name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
}

/** The Air 2 Pro's optics, from the driver's own model table, so the window shows what the glasses would. */
private fun referenceOptics(): Optics =
    GlassesRegistry.default().probe(UsbIdentity(vendorId = 0x3318, productId = 0x0432))!!.match.optics

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val options = Options(args)
    check(glfwInit()) { "glfwInit failed" }
    check(glfwVulkanSupported()) { "GLFW reports no Vulkan loader" }
    glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API)

    val session = if (options.glasses) GlassesSession.open(::println) else null
    val window: Long
    var refreshHz = 60
    if (session != null) {
        val mode = session.enterStereo().getOrElse {
            session.close()
            println("could not enter stereo: ${it.message}")
            exitProcess(1)
        }
        println("stereo mode: $mode")
        val monitor = session.findMonitor(mode.frameWidth) ?: run {
            session.close()
            println("the glasses' display never appeared at ${mode.frameWidth} wide")
            exitProcess(1)
        }
        val video = glfwGetVideoMode(monitor)!!
        refreshHz = video.refreshRate()
        println("monitor: ${glfwGetMonitorName(monitor)} ${video.width()}x${video.height()} @${video.refreshRate()}")
        window = glfwCreateWindow(video.width(), video.height(), "Halone", monitor, 0)
    } else {
        window = glfwCreateWindow(1600, 900, "Halone", 0, 0)
    }

    val optics = session?.device?.optics ?: referenceOptics()
    val vk = VulkanContext(window)
    val loop = FrameLoop(vk, window)
    var verticalFov = optics.verticalFovDegrees
    val stereo = session?.let {
        StereoRenderer(vk, loop.swapchain.format, it.calibration, optics, loop.swapchain.width, loop.swapchain.height).also { renderer ->
            verticalFov = renderer.visibleVerticalDegrees
            println("stereo: ${renderer.describe()}, frame ${loop.swapchain.width}x${loop.swapchain.height}")
        }
    }
    val compositor = DesktopCompositor(vk, loop.swapchain.format, verticalFov)
    compositor.stereo = stereo
    println("device: ${vk.deviceName}, Vulkan ${vk.versionString()}, vertical FOV %.1f°".format(verticalFov))
    stackPush().use { stack ->
        val x = stack.mallocInt(1); val y = stack.mallocInt(1); val w = stack.mallocInt(1); val h = stack.mallocInt(1)
        glfwGetWindowPos(window, x, y)
        glfwGetWindowSize(window, w, h)
        println("window at ${x.get(0)},${y.get(0)} size ${w.get(0)}x${h.get(0)}")
    }

    var opened = 0
    fun openPanel() {
        val hue = (opened * 0.17f) % 1f
        val texture = StaticTexture(vk, 1280, 800, TestPattern.pixels(1280, 800, "Panel ${opened + 1}", hue))
        val panel = DesktopPanel("panel-$opened", 1280, 800, texture, PanelPose(distanceMetres = optics.virtualImageDistanceMetres))
        panel.fitToFov(verticalFov, PANEL_VERTICAL_FILL)
        compositor.scene.open(panel)
        opened++
    }
    fun closePanel() {
        val panel = compositor.scene.focused ?: compositor.scene.panels.lastOrNull() ?: return
        compositor.scene.remove(panel)
        compositor.scene.layOutCylindrical(immediate = false)
        vkDeviceWaitIdle(vk.device)
        compositor.forget(panel.texture)
        panel.texture.close()
    }
    repeat(3) { openPanel() }

    var yaw = 0f
    var pitch = 0f
    var captured = false
    var lastX = Double.NaN
    var lastY = Double.NaN
    glfwSetFramebufferSizeCallback(window) { _, _, _ -> loop.onResize() }
    glfwSetMouseButtonCallback(window) { w, _, action, _ ->
        if (session == null && action == GLFW_PRESS && !captured) {
            captured = true
            lastX = Double.NaN
            glfwSetInputMode(w, GLFW_CURSOR, GLFW_CURSOR_DISABLED)
        }
    }
    glfwSetCursorPosCallback(window) { _, x, y ->
        if (captured && !lastX.isNaN()) {
            yaw -= ((x - lastX) * MOUSE_DEGREES_PER_PIXEL).toFloat()
            pitch = (pitch - ((y - lastY) * MOUSE_DEGREES_PER_PIXEL).toFloat()).coerceIn(-80f, 80f)
        }
        lastX = x
        lastY = y
    }
    glfwSetKeyCallback(window) { w, key, _, action, _ ->
        if (action != GLFW_PRESS) return@glfwSetKeyCallback
        when (key) {
            GLFW_KEY_ESCAPE -> if (session != null) {
                glfwSetWindowShouldClose(w, true)
            } else {
                captured = false
                glfwSetInputMode(w, GLFW_CURSOR, GLFW_CURSOR_NORMAL)
            }
            GLFW_KEY_R -> {
                yaw = 0f
                pitch = 0f
                session?.tracker?.recenter()
                compositor.scene.recentre()
            }
            GLFW_KEY_N -> openPanel()
            GLFW_KEY_BACKSPACE -> closePanel()
        }
    }

    val frameMs = 1000f / refreshHz
    val start = glfwGetTime()
    var last = start
    var reportAt = start + 2
    var frames = 0
    var worstMs = 0.0
    while (!glfwWindowShouldClose(window)) {
        glfwPollEvents()
        val now = glfwGetTime()
        val dt = (now - last).toFloat()
        worstMs = maxOf(worstMs, (now - last) * 1000)
        last = now
        if (options.sweep) yaw = 50f * sin((now - start) * 0.6).toFloat()
        val head = session?.tracker?.predictedPose(frameMs)?.orientation
            ?: (Quaternion.fromYawRadians(Math.toRadians(yaw.toDouble()).toFloat()) * Quaternion.fromPitchDegrees(pitch))
        compositor.advance(head, dt)
        if (loop.frame { compositor.record(it, head) }) frames++
        if (now >= reportAt) {
            val (headYaw, headPitch, _) = head.toEulerDegrees()
            println("%.1f fps, worst frame %.1f ms, head yaw %.1f° pitch %.1f°, panels at %s".format(
                frames / (now - reportAt + 2), worstMs, headYaw, headPitch,
                compositor.scene.panels.joinToString { "%.1f°".format(Math.toDegrees(it.pose.yawRadians.toDouble())) }))
            frames = 0
            worstMs = 0.0
            reportAt = now + 2
        }
        if (options.seconds != null && now - start >= options.seconds) break
    }

    vkDeviceWaitIdle(vk.device)
    compositor.scene.panels.forEach { it.texture.close() }
    compositor.close()
    loop.close()
    vk.close()
    glfwDestroyWindow(window)
    session?.close()
    glfwTerminate()
}

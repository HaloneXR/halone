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
import java.io.File
import kotlin.math.sin
import kotlin.system.exitProcess

private const val PANEL_VERTICAL_FILL = 0.75f
private const val MOUSE_DEGREES_PER_PIXEL = 0.08f
private const val PACE_TIMEOUT_NS = 100_000_000L

/** How frames are paced, aimed and warped. */
private data class Condition(
    val name: String,
    val images: Int?,
    val pace: Boolean,
    val aimed: Boolean,
    val reproject: Boolean,
    val scan: Float = 1f,
)

/** What the first wear check ran: three images, unpaced, the fixed 16.6 ms prediction and no warp. */
private val BASELINE = Condition("baseline", images = 3, pace = false, aimed = false, reproject = false)

/** Two images paced on present-wait, the photon aimed from measured presents, and the late warp. */
private val SMOOTH = Condition("smooth", images = 2, pace = true, aimed = true, reproject = true)

private class Options(args: Array<String>) {
    val seconds = value(args, "--seconds")?.toDouble()
    val sweep = "--sweep" in args
    val glasses = "--glasses" in args
    val condition = when (value(args, "--condition")) {
        "baseline" -> BASELINE
        else -> SMOOTH
    }
    val ab = "--ab" in args
    val scanSweep = "--scan-sweep" in args
    val scanValues = value(args, "--scan-values")?.split(",")?.map { it.toFloat() } ?: listOf(1f, 0f, -1f)
    val reveal = value(args, "--reveal")
    val abSeconds = value(args, "--ab-interval")?.toDouble() ?: 8.0

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
        // GLFW minimises a fullscreen window when it loses focus, which takes the
        // scene off the glasses as soon as the wearer clicks anything on the Mac.
        glfwWindowHint(GLFW_AUTO_ICONIFY, GLFW_FALSE)
        println("monitor: ${glfwGetMonitorName(monitor)} ${video.width()}x${video.height()} @${video.refreshRate()}")
        window = glfwCreateWindow(video.width(), video.height(), "Halone", monitor, 0)
        glfwFocusWindow(window)
    } else {
        window = glfwCreateWindow(1600, 900, "Halone", 0, 0)
    }

    val blind = options.ab || options.scanSweep
    val abLetters = when {
        options.scanSweep -> options.scanValues.map { SMOOTH.copy(name = "scan %+.2f".format(it), scan = it) }.shuffled()
        options.ab -> listOf(BASELINE, SMOOTH).shuffled()
        else -> emptyList()
    }
    fun letter(index: Int) = ('A' + index).toString()
    var condition = if (blind) abLetters[0] else options.condition

    val optics = session?.device?.optics ?: referenceOptics()
    val vk = VulkanContext(window)
    val loop = FrameLoop(vk, window, condition.images)
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
    println("presentation: display timing ${vk.hasDisplayTiming}, present id ${vk.hasPresentId}, present wait ${vk.hasPresentWait}")
    stackPush().use { stack ->
        val x = stack.mallocInt(1); val y = stack.mallocInt(1); val w = stack.mallocInt(1); val h = stack.mallocInt(1)
        glfwGetWindowPos(window, x, y)
        glfwGetWindowSize(window, w, h)
        println("window at ${x.get(0)},${y.get(0)} size ${w.get(0)}x${h.get(0)}")
    }

    var opened = 0
    fun panelTexture(index: Int, label: String) =
        StaticTexture(vk, 1280, 800, TestPattern.pixels(1280, 800, label, (index * 0.17f) % 1f))
    fun openPanel() {
        val texture = panelTexture(opened, "Panel ${opened + 1}")
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

    val aim = session?.displayClock?.let { FrameAim(it, session.tracker) }

    fun apply(next: Condition, letter: String?) {
        condition = next
        loop.setImages(next.images)
        aim?.scanoutCompensation = next.scan
        if (letter != null) {
            val panel = compositor.scene.find("panel-1") ?: return
            vkDeviceWaitIdle(vk.device)
            compositor.forget(panel.texture)
            panel.texture.close()
            panel.texture = panelTexture(1, letter)
            println("condition $letter active")
        } else {
            println("condition ${next.name} active")
        }
    }
    apply(condition, if (blind) "A" else null)
    var abShowing = 0

    val probe = TimingProbe(session?.displayClock)
    var refreshNs = loop.refreshDurationNs()
    val sampleById = HashMap<Long, Long>()
    loop.onPresented = { record ->
        probe.record(record)
        sampleById.remove(record.id)?.let { aim?.onPresented(it, record.actualNs, refreshNs) }
    }

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
            in GLFW_KEY_1..GLFW_KEY_9 -> {
                val index = key - GLFW_KEY_1
                if (blind && index < abLetters.size) {
                    abShowing = index
                    apply(abLetters[index], letter(index))
                }
            }
        }
    }

    val frameMs = 1000f / refreshHz
    val start = glfwGetTime()
    var lastSampleNs = System.nanoTime()
    var reportAt = start + 2
    var frames = 0
    var worstMs = 0.0
    var paceWaitNs = 0L
    var abSwitchAt = start + options.abSeconds
    while (!glfwWindowShouldClose(window)) {
        if (blind && glfwGetTime() >= abSwitchAt) {
            abShowing = (abShowing + 1) % abLetters.size
            apply(abLetters[abShowing], letter(abShowing))
            abSwitchAt = glfwGetTime() + options.abSeconds
        }
        if (condition.pace) {
            val waitStart = System.nanoTime()
            loop.waitForPresent(loop.lastPresentId, PACE_TIMEOUT_NS)
            paceWaitNs += System.nanoTime() - waitStart
        }
        glfwPollEvents()
        val now = glfwGetTime()
        if (options.sweep) yaw = 50f * sin((now - start) * 0.6).toFloat()
        val drawn = loop.frame { target ->
            // Sampled here, after the acquire and fence waits, so none of that
            // waiting sits between the pose and the frame reaching the display.
            val sampleNs = System.nanoTime()
            val dt = (sampleNs - lastSampleNs) / 1e9f
            worstMs = maxOf(worstMs, dt * 1000.0)
            lastSampleNs = sampleNs
            val id = loop.lastPresentId + 1
            sampleById[id] = sampleNs
            probe.sampled(id, sampleNs)
            val aiming = aim?.takeIf { condition.aimed && it.ready }
            val photon = aiming?.photonHostNs(sampleNs)
            val head = when {
                aiming != null && photon != null -> aiming.renderPose(photon).orientation
                session != null -> session.tracker.predictedPose(frameMs).orientation
                else -> Quaternion.fromYawRadians(Math.toRadians(yaw.toDouble()).toFloat()) * Quaternion.fromPitchDegrees(pitch)
            }
            if (photon != null) probe.aimed(id, photon)
            compositor.advance(head, dt)
            val warp = if (aiming != null && photon != null && condition.reproject) { { aiming.warpPoses(photon) } } else null
            compositor.record(target, head, warp)
        }
        if (drawn) frames++
        if (sampleById.size > 512) sampleById.keys.sorted().take(256).forEach { sampleById.remove(it) }
        if (now >= reportAt) {
            refreshNs = loop.refreshDurationNs()
            val head = session?.tracker?.pose?.orientation
            val (headYaw, headPitch, _) = head?.toEulerDegrees() ?: Triple(yaw, pitch, 0f)
            println("%.1f fps, worst frame %.1f ms, head yaw %.1f° pitch %.1f°, refreshes ahead %s, panels at %s".format(
                frames / (now - reportAt + 2), worstMs, headYaw, headPitch, aim?.refreshesAhead ?: "-",
                compositor.scene.panels.joinToString { "%.1f°".format(Math.toDegrees(it.pose.yawRadians.toDouble())) }))
            if (!blind) println(probe.report(refreshNs)) else probe.report(refreshNs)
            if (!blind) println("blocking: pace wait %.2f, prime %.2f, submit %.2f, present %.2f ms per frame".format(
                paceWaitNs / 1e6 / maxOf(frames, 1), loop.primeBlockNs / 1e6 / maxOf(frames, 1),
                loop.submitBlockNs / 1e6 / maxOf(frames, 1), loop.presentBlockNs / 1e6 / maxOf(frames, 1)))
            loop.primeBlockNs = 0
            loop.submitBlockNs = 0
            loop.presentBlockNs = 0
            frames = 0
            worstMs = 0.0
            paceWaitNs = 0
            reportAt = now + 2
        }
        if (options.seconds != null && now - start >= options.seconds) break
    }

    if (blind) {
        val mapping = abLetters.indices.joinToString(", ", postfix = "\n") { "${letter(it)} = ${abLetters[it].name}" }
        options.reveal?.let { File(it).writeText(mapping) } ?: println("reveal: $mapping")
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

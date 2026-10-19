package dev.halone.desktop.compositor

import dev.halone.compositor.PanelPose
import dev.halone.desktop.compositor.lease.DrmLease
import dev.halone.desktop.compositor.lease.InputWindow
import dev.halone.desktop.compositor.producer.InputRouter
import dev.halone.desktop.compositor.producer.PanelSpec
import dev.halone.compositor.ChromePress
import dev.halone.compositor.TitleBar
import dev.halone.desktop.compositor.producer.ProducerHost
import dev.halone.desktop.compositor.producer.ProducerTexture
import dev.halone.desktop.compositor.render.DesktopCompositor
import dev.halone.desktop.compositor.render.DesktopPanel
import dev.halone.desktop.compositor.render.StereoRenderer
import dev.halone.desktop.compositor.render.TestPattern
import dev.halone.desktop.compositor.shell.DesktopShell
import dev.halone.desktop.compositor.vk.DisplayOutput
import dev.halone.desktop.compositor.vk.FrameLoop
import dev.halone.desktop.compositor.vk.Output
import dev.halone.desktop.compositor.vk.WindowOutput
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
    /** Refreshes ahead on the glasses' grid, where present times are not reported; null for the default. */
    val ahead: Int? = null,
)

/** Where nothing reports present times. Blind sweep on RADV/KDE Wayland, 2026-10-18: 1 beat 2 and 3, and unaimed was worst. */
private const val DEFAULT_CLOCK_GRID_AHEAD = 1

/** What the first wear check ran: three images, unpaced, the fixed 16.6 ms prediction and no warp. */
private val BASELINE = Condition("baseline", images = 3, pace = false, aimed = false, reproject = false)

/** Two images paced on present-wait, the photon aimed from measured presents, and the late warp. */
private val SMOOTH = Condition("smooth", images = 2, pace = true, aimed = true, reproject = true)

private class Options(args: Array<String>) {
    val seconds = value(args, "--seconds")?.toDouble()
    val sweep = "--sweep" in args
    val glasses = "--glasses" in args
    val lease = "--lease" in args
    val condition = when (value(args, "--condition")) {
        "baseline" -> BASELINE
        else -> SMOOTH
    }
    val ab = "--ab" in args
    val producer = value(args, "--producer")
    val producerPanels = args.indices.filter { args[it] == "--panel" }.mapNotNull { args.getOrNull(it + 1) }.map { PanelSpec.parse(it) }
    val producerMarginMs = value(args, "--producer-margin")?.toFloat() ?: 10f
    val producerLog = value(args, "--producer-log") ?: "producer.log"

    /** The built system apps (web/apps/system/dist); with a producer, runs the shell on them. */
    val shellApps = value(args, "--shell")?.let(::File)
    val inputTest = "--input-test" in args
    val chromeTest = "--chrome-test" in args
    val pitch = value(args, "--pitch")?.toFloat() ?: 0f
    val noPresentWait = "--no-present-wait" in args
    val scanSweep = "--scan-sweep" in args
    val scanValues = value(args, "--scan-values")?.split(",")?.map { it.toFloat() } ?: listOf(1f, 0f, -1f)
    val aheadSweep = "--ahead-sweep" in args
    val aheadValues = value(args, "--ahead-values")?.split(",")?.map { it.toInt() } ?: listOf(1, 2, 3)
    val reveal = value(args, "--reveal")
    val abSeconds = value(args, "--ab-interval")?.toDouble() ?: 8.0

    private fun value(args: Array<String>, name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
}

/**
 * Drives the pointer through spike 2's test page (`site/spike/test.html`, 1280x720) the way the mouse
 * and keyboard would: point at the button and click it, focus the field and type, scroll the list.
 */
private fun inputScript(): List<Pair<Double, (InputRouter, DesktopPanel) -> Unit>> {
    val steps = mutableListOf<Pair<Double, (InputRouter, DesktopPanel) -> Unit>>()
    steps += 0.0 to { r, p -> r.pointAt(p, 100f, 100f) }
    steps += 0.3 to { r, p -> r.pointAt(p, 265f, 505f) }
    steps += 0.6 to { r, _ -> r.button(0, true, 0, 0.6, 0f) }
    steps += 0.65 to { r, _ -> r.button(0, false, 0, 0.65, 0f) }
    steps += 1.0 to { r, p -> r.pointAt(p, 117f, 505f) }
    steps += 1.1 to { r, _ -> r.button(0, true, 0, 1.1, 0f) }
    steps += 1.15 to { r, _ -> r.button(0, false, 0, 1.15, 0f) }
    val keys = listOf(GLFW_KEY_H, GLFW_KEY_E, GLFW_KEY_L, GLFW_KEY_L, GLFW_KEY_O).map { it to glfwGetKeyScancode(it) }
    keys.forEachIndexed { i, (key, scancode) ->
        val at = 1.5 + i * 0.1
        steps += at to { r, _ -> r.key(key, scancode, GLFW_PRESS, 0); r.character("hello"[i].code, 0) }
        steps += at + 0.05 to { r, _ -> r.key(key, scancode, GLFW_RELEASE, 0) }
    }
    steps += 2.3 to { r, p -> r.pointAt(p, 324f, 601f) }
    steps += 2.4 to { r, _ -> r.scroll(0.0, -3.0, 0) }
    return steps
}

/**
 * Drives the chrome of the second window the way the mouse would: drags its
 * title bar sideways, clicks its pin, then drags its bottom-right corner.
 */
private fun chromeScript(): List<Pair<Double, (InputRouter, DesktopPanel) -> Unit>> {
    val steps = mutableListOf<Pair<Double, (InputRouter, DesktopPanel) -> Unit>>()
    steps += 0.0 to { r, p -> r.pointAt(p, p.widthPx / 2f, p.heightPx + TitleBar.GAP_PX + TitleBar.HEIGHT_PX / 2f) }
    steps += 0.3 to { r, _ -> r.button(0, true, 0, 0.3, 0f) }
    for (i in 1..20) steps += (0.3 + i * 0.03) to { r, _ -> r.move(-12.0, 0.0, 0) }
    steps += 1.0 to { r, _ -> r.button(0, false, 0, 1.0, 0f) }
    steps += 1.5 to { r, p ->
        val pin = TitleBar.pin(p.widthPx, p.heightPx)
        r.pointAt(p, pin.left + pin.width / 2f, pin.top + pin.height / 2f)
    }
    steps += 1.7 to { r, _ -> r.button(0, true, 0, 1.7, 0f) }
    steps += 1.75 to { r, _ -> r.button(0, false, 0, 1.75, 0f) }
    steps += 2.2 to { r, p -> r.pointAt(p, p.widthPx + 20f, p.heightPx + 20f) }
    steps += 2.4 to { r, _ -> r.button(0, true, 0, 2.4, 0f) }
    for (i in 1..15) steps += (2.4 + i * 0.03) to { r, _ -> r.move(8.0, 5.0, 0) }
    steps += 3.0 to { r, _ -> r.button(0, false, 0, 3.0, 0f) }
    return steps
}

/** The Air 2 Pro's optics, from the driver's own model table, so the window shows what the glasses would. */
private fun referenceOptics(): Optics =
    GlassesRegistry.default().probe(UsbIdentity(vendorId = 0x3318, productId = 0x0432))!!.match.optics

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val options = Options(args)
    // With the glasses leased, GLFW's only window takes the keyboard and mouse. Plasma
    // shows accented characters for a held letter that has them (A, D, E, K...) and,
    // to a Wayland client without text-input, sends its release at once; XWayland
    // gets the hold and its repeats as typed.
    if (options.lease && System.getenv("WAYLAND_DISPLAY") != null && System.getenv("DISPLAY") != null &&
        glfwPlatformSupported(GLFW_PLATFORM_X11)) glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11)
    check(glfwInit()) { "glfwInit failed" }
    check(glfwVulkanSupported()) { "GLFW reports no Vulkan loader" }
    glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API)

    val session = if (options.glasses) GlassesSession.open(::println) else null
    val window: Long
    var lease: DrmLease? = null
    var leasedOutput: Output? = null
    var refreshHz = 60
    if (session != null) {
        val mode = session.enterStereo().getOrElse {
            session.close()
            println("could not enter stereo: ${it.message}")
            exitProcess(1)
        }
        println("stereo mode: $mode")
        if (options.lease) {
            val leased = DrmLease.acquire(session.device.displayNameHints, ::println).getOrElse {
                session.close()
                println("could not lease the glasses' display: ${it.message}")
                exitProcess(1)
            }
            lease = leased
            println("leased: ${leased.offer}")
            leasedOutput = DisplayOutput(leased.fd, leased.offer.connectorId, mode.frameWidth, mode.frameHeight, mode.refreshHz)
            window = InputWindow.create("Halone (on the glasses)")
        } else {
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
        }
    } else {
        window = glfwCreateWindow(1600, 900, "Halone", 0, 0)
    }
    glfwFocusWindow(window)

    val blind = options.ab || options.scanSweep || options.aheadSweep
    val abLetters = when {
        options.aheadSweep -> (options.aheadValues.map { SMOOTH.copy(name = "$it refreshes ahead", ahead = it) } +
            SMOOTH.copy(name = "not aimed", aimed = false, reproject = false)).shuffled()
        options.scanSweep -> options.scanValues.map { SMOOTH.copy(name = "scan %+.2f".format(it), scan = it) }.shuffled()
        options.ab -> listOf(BASELINE, SMOOTH).shuffled()
        else -> emptyList()
    }
    fun letter(index: Int) = ('A' + index).toString()
    var condition = if (blind) abLetters[0] else options.condition

    val optics = session?.device?.optics ?: referenceOptics()
    val vk = VulkanContext(leasedOutput ?: WindowOutput(window))
    vk.output.refreshHz?.let { refreshHz = it }
    val loop = FrameLoop(vk, condition.images)
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
    if (options.noPresentWait) loop.presentWaitEnabled = false
    // RADV (Mesa 26.2.3) segfaults inside vkWaitForPresentKHR on the first wait, on
    // KDE Wayland and on a leased display alike. HALONE_PRESENT_WAIT=1 forces it back
    // on, to check a newer Mesa.
    if ("RADV" in vk.deviceName && System.getenv("HALONE_PRESENT_WAIT") != "1") loop.presentWaitEnabled = false
    println("presentation: display timing ${vk.hasDisplayTiming}, present id ${vk.hasPresentId}, present wait ${vk.hasPresentWait}" +
        if (vk.hasPresentWait && !loop.presentWaitEnabled) " (turned off)" else "")
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
    fun closePanel(panel: DesktopPanel? = compositor.scene.focused ?: compositor.scene.panels.lastOrNull()) {
        panel ?: return
        compositor.scene.remove(panel)
        compositor.scene.layOutCylindrical(immediate = false)
        if (panel.texture is ProducerTexture) return
        vkDeviceWaitIdle(vk.device)
        compositor.forget(panel.texture)
        panel.texture.close()
    }
    var frameCount = 0L
    val shellApps = options.shellApps?.takeIf { options.producer != null }
    val host = options.producer?.let { executable ->
        val specs = if (shellApps != null) emptyList() else options.producerPanels
        ProducerHost(vk, executable, specs, options.producerMarginMs, File(options.producerLog), webRoot = shellApps).also { host ->
            host.onTextureClosed = compositor::forget
            if (shellApps != null) return@also
            val byProducer = HashMap<Int, DesktopPanel>()
            host.onPanelReady = { index, texture ->
                val spec = options.producerPanels[index]
                val panel = DesktopPanel("producer-$index", spec.width, spec.height, texture, PanelPose(distanceMetres = optics.virtualImageDistanceMetres))
                panel.fitToFov(verticalFov, PANEL_VERTICAL_FILL)
                panel.title = java.net.URI(spec.url).let { (it.host ?: "") + (it.path ?: "") }
                panel.tag = "web"
                panel.producerIndex = index
                panel.onResizeContent = { width, height -> host.resize(index, width, height) }
                byProducer[index] = panel
                compositor.scene.open(panel)
                println("producer panel $index ready: ${spec.width}x${spec.height} ${spec.url}")
            }
            host.onPanelResized = { index, texture -> byProducer[index]?.texture = texture }
            host.onCursorChanged = { index, shape -> byProducer[index]?.cursorShape = shape }
        }
    }
    val desktopShell = if (host != null && shellApps != null) {
        DesktopShell(
            compositor, host, shellApps, verticalFov,
            frame = { frameCount },
            onRecenter = {
                session?.tracker?.recenter()
                compositor.scene.recentre()
            },
            version = object {}.javaClass.getResource("/halone-version.txt")?.readText()?.trim() ?: "0.0.0-dev",
            deviceName = session?.device?.let { it.toString() } ?: "Desktop",
            log = ::println,
        )
    } else {
        null
    }
    if (host == null) repeat(3) { openPanel() }
    val chromeActions = object : ChromePress.Actions<DesktopPanel> {
        override fun resizeContent(panel: DesktopPanel, widthPx: Int, heightPx: Int) = panel.resizeContent(widthPx, heightPx)

        override fun pin(panel: DesktopPanel, pinned: Boolean): Boolean {
            val shell = desktopShell?.shell ?: return false
            if (shell.windows().none { it.id == panel.id }) return false
            shell.setPinned(panel.id, pinned)
            return true
        }

        override fun back(panel: DesktopPanel) = Unit

        override fun close(panel: DesktopPanel) {
            val shell = desktopShell?.shell
            if (shell != null && shell.windows().any { it.id == panel.id }) shell.close(panel.id) else closePanel(panel)
        }

        override fun dropped(panel: DesktopPanel) =
            println("dropped '${panel.id}' at %+.0f°, slot %+.0f°".format(
                Math.toDegrees(panel.pose.yawRadians.toDouble()), Math.toDegrees(panel.slotYawRadians.toDouble())))

        override fun resized(panel: DesktopPanel, oldWidthPx: Int, oldHeightPx: Int) =
            println("resized '${panel.id}' ${oldWidthPx}x$oldHeightPx -> ${panel.widthPx}x${panel.heightPx}")
    }

    val aim = session?.displayClock?.let { FrameAim(it, session.tracker) }

    fun apply(next: Condition, letter: String?) {
        condition = next
        loop.setImages(next.images)
        aim?.scanoutCompensation = next.scan
        aim?.clockGridAhead = if (vk.hasDisplayTiming) next.ahead else next.ahead ?: DEFAULT_CLOCK_GRID_AHEAD
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
    var pitch = options.pitch
    var captured = false
    var lastX = Double.NaN
    var lastY = Double.NaN
    if (lease == null) glfwSetFramebufferSizeCallback(window) { _, _, _ -> loop.onResize() }
    var headYawRadians = 0f
    var headPitchRadians = 0f
    var mods = 0
    val router = if (host != null || session != null) InputRouter(host, compositor.scene, chromeActions) else null
    var routerPlaced = false
    if (router != null) {
        captured = true
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED)
        if (glfwRawMouseMotionSupported()) glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE)
        println("pointer: mouse moves it, Ctrl+R recentres, Ctrl+Q quits, Ctrl+Esc releases the mouse; window focused ${glfwGetWindowAttrib(window, GLFW_FOCUSED) == GLFW_TRUE}")
    }
    glfwSetMouseButtonCallback(window) { w, button, action, modifiers ->
        mods = modifiers
        if (!captured && action == GLFW_PRESS) {
            captured = true
            lastX = Double.NaN
            glfwSetInputMode(w, GLFW_CURSOR, GLFW_CURSOR_DISABLED)
            return@glfwSetMouseButtonCallback
        }
        router?.button(button, action == GLFW_PRESS, modifiers, glfwGetTime(), compositor.headYawRadians)
    }
    glfwSetCursorPosCallback(window) { _, x, y ->
        if (captured && !lastX.isNaN()) {
            if (router != null) {
                router.move(x - lastX, y - lastY, mods)
            } else {
                yaw -= ((x - lastX) * MOUSE_DEGREES_PER_PIXEL).toFloat()
                pitch = (pitch - ((y - lastY) * MOUSE_DEGREES_PER_PIXEL).toFloat()).coerceIn(-80f, 80f)
            }
        }
        lastX = x
        lastY = y
    }
    glfwSetScrollCallback(window) { _, dx, dy -> router?.scroll(dx, dy, mods) }
    glfwSetWindowFocusCallback(window) { _, focused -> println("focus ${if (focused) "gained" else "lost"}") }
    glfwSetCharCallback(window) { _, codepoint -> router?.character(codepoint, mods) }
    glfwSetKeyCallback(window) { w, key, scancode, action, modifiers ->
        mods = modifiers
        if (desktopShell?.key(key, action, modifiers) == true) return@glfwSetKeyCallback
        if (router != null && modifiers and GLFW_MOD_CONTROL == 0) {
            router.key(key, scancode, action, modifiers)
            return@glfwSetKeyCallback
        }
        if (action != GLFW_PRESS) return@glfwSetKeyCallback
        when (key) {
            GLFW_KEY_Q -> if (router != null) glfwSetWindowShouldClose(w, true)
            GLFW_KEY_ESCAPE -> if (session != null && router == null) {
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
                router?.recentre(0f, 0f)
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

    fun byProducer(index: Int): DesktopPanel? = with(compositor.scene) {
        panels.firstOrNull { it.producerIndex == index } ?: listOfNotNull(dock, launcher, popup).firstOrNull { it.producerIndex == index }
    }
    desktopShell?.start()

    var scriptStart = 0.0
    val script = ArrayDeque(if (options.chromeTest) chromeScript() else inputScript())
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
            loop.waitForLastPresent(PACE_TIMEOUT_NS)
            paceWaitNs += System.nanoTime() - waitStart
        }
        glfwPollEvents()
        desktopShell?.tick(System.nanoTime())
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
            frameCount = id
            host?.update(id) { index -> byProducer(index)?.let(compositor.scene::isAnimatingResize) == true }
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
            val (headYawDegrees, headPitchDegrees, _) = head.toEulerDegrees()
            headYawRadians = Math.toRadians(headYawDegrees.toDouble()).toFloat()
            headPitchRadians = Math.toRadians(headPitchDegrees.toDouble()).toFloat()
            if (router != null) {
                if (!routerPlaced && compositor.scene.panels.isNotEmpty()) {
                    router.recentre(headYawRadians, headPitchRadians)
                    routerPlaced = true
                }
                router.clamp()
                router.followKeyTarget()
                compositor.pointerDirection = router.direction
            }
            val warp = if (aiming != null && photon != null && condition.reproject) { { aiming.warpPoses(photon) } } else null
            compositor.record(target, head, warp)
            host?.tick(sampleNs + (if (refreshNs > 0) refreshNs else (1e9 / refreshHz).toLong()))
        }
        if (drawn) frames++
        if ((options.inputTest || options.chromeTest) && router != null) {
            val page = compositor.scene.find("producer-1")
            if (page != null && page.texture.ready) {
                if (scriptStart == 0.0) scriptStart = now + 3.0
                while (script.isNotEmpty() && now >= scriptStart + script.first().first) script.removeFirst().second(router, page)
            }
        }
        if (sampleById.size > 512) sampleById.keys.sorted().take(256).forEach { sampleById.remove(it) }
        if (now >= reportAt) {
            refreshNs = loop.refreshDurationNs()
            val head = session?.tracker?.pose?.orientation
            val (headYaw, headPitch, _) = head?.toEulerDegrees() ?: Triple(yaw, pitch, 0f)
            println("%.1f fps, worst frame %.1f ms, head yaw %.1f° pitch %.1f°, refreshes ahead %s, panels at %s".format(
                frames / (now - reportAt + 2), worstMs, headYaw, headPitch, aim?.refreshesAhead ?: "-",
                compositor.scene.panels.joinToString { "%.1f°".format(Math.toDegrees(it.pose.yawRadians.toDouble())) }))
            if (!blind) println(probe.report(refreshNs)) else probe.report(refreshNs)
            if (loop.surfacesLost > 0) {
                println("surface lost and made again ${loop.surfacesLost} times")
                loop.surfacesLost = 0
            }
            if (loop.presentResults.isNotEmpty()) {
                println("presents not VK_SUCCESS: ${loop.presentResults.entries.joinToString { "VkResult ${it.key} x${it.value}" }}")
                loop.presentResults.clear()
            }
            host?.let { h ->
                val counts = h.takeFrameCounts()
                println("producer: ${if (h.alive) "running" else "exited"}, new frames per 2 s ${options.producerPanels.indices.joinToString { "$it: ${counts[it] ?: 0}" }}")
            }
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
    desktopShell?.close()
    host?.close()
    compositor.scene.panels.filter { it.texture !is ProducerTexture }.forEach { it.texture.close() }
    compositor.close()
    loop.close()
    vk.close()
    lease?.close()
    glfwDestroyWindow(window)
    session?.close()
    glfwTerminate()
}

package dev.halone.desktop.compositor.shell

import dev.halone.compositor.Notice
import dev.halone.compositor.PanelPose
import dev.halone.compositor.SizeLimits
import dev.halone.desktop.compositor.producer.ProducerHost
import dev.halone.desktop.compositor.render.DesktopCompositor
import dev.halone.desktop.compositor.render.DesktopPanel
import dev.halone.desktop.compositor.vk.TextureSource
import dev.halone.desktop.shell.BridgeSockets
import dev.halone.desktop.shell.FileStore
import dev.halone.shell.AppInfo
import dev.halone.shell.BridgeHost
import dev.halone.shell.Shell
import dev.halone.shell.ShellPlatform
import dev.halone.shell.ShellSettings
import dev.halone.shell.SystemInfo
import dev.halone.shell.applyTo
import org.lwjgl.glfw.GLFW.glfwGetClipboardString
import org.lwjgl.glfw.GLFW.glfwSetClipboardString
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * The shell on the desktop: what ShellSession is on Android. Windows are
 * pages in the producer, the system apps are served from [webRoot] as
 * https://halone.local/, and the bridge reaches them through [BridgeSockets].
 * Everything but the queue behind [post] runs on the render thread, the
 * shell's own; call [tick] once a frame.
 */
class DesktopShell(
    private val compositor: DesktopCompositor,
    private val host: ProducerHost,
    private val webRoot: File,
    private val verticalFovDegrees: Float,
    private val frame: () -> Long,
    private val onRecenter: () -> Unit,
    version: String,
    deviceName: String,
    private val log: (String) -> Unit,
) : ShellPlatform<DesktopPanel>, AutoCloseable {
    private val queue = ConcurrentLinkedQueue<Runnable>()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "shell-io").apply { isDaemon = true } }
    private val background = Executors.newFixedThreadPool(2) { Thread(it, "shell-background").apply { isDaemon = true } }
    private val sockets = BridgeSockets(::post, log)
    private val byProducer = HashMap<Int, DesktopPanel>()
    private var noticeUntilNs = 0L
    private var lastPollNs = 0L

    private val scene get() = compositor.scene

    val shell = Shell(
        scene = scene,
        systemApps = AppInfo.parseRegistry(File(webRoot, REGISTRY).readText()),
        settings = ShellSettings(FileStore("settings.json", io), File(webRoot, ShellSettings.SCHEMA_ASSET).readText()),
        platform = this,
    )

    val bridge = BridgeHost(
        shell,
        SystemInfo(
            version = version,
            deviceName = deviceName,
            verticalFovDegrees = verticalFovDegrees.toDouble(),
            displayWidthPx = EYE_WIDTH_PX,
            displayHeightPx = EYE_HEIGHT_PX,
            platform = "desktop",
        ),
        log,
        onMain = ::post,
        background = { background.execute(it) },
    )

    init {
        host.onPanelReady = { index, texture -> byProducer[index]?.texture = texture }
        host.onPanelResized = { index, texture -> byProducer[index]?.texture = texture }
        host.onCursorChanged = { index, shape -> byProducer[index]?.cursorShape = shape }
    }

    fun start() {
        applySettings(shell.settings, null)
        shell.openOverlays()
    }

    /** Runs what other threads posted, and polls the shell a few times a second, as ShellSession does. */
    fun tick(nowNs: Long) {
        while (true) (queue.poll() ?: break).run()
        if (nowNs - lastPollNs >= POLL_NS) {
            lastPollNs = nowNs
            shell.poll()
        }
        if (compositor.notice != null && nowNs >= noticeUntilNs) compositor.notice = null
    }

    private fun post(task: Runnable) {
        queue += task
    }

    /** A panel drawn by the producer, sized as Android sizes its windows; [origin] gets the bridge. */
    private fun producerPanel(id: String, widthPx: Int, heightPx: Int, url: String, origin: String?): DesktopPanel {
        val panel = DesktopPanel(id, widthPx, heightPx, NoTexture, PanelPose(distanceMetres = distance()))
        panel.fitToFov(verticalFovDegrees, fillFor(heightPx))
        val script = origin?.let { sockets.register(id, it) { channel -> bridge.connect(id, channel) } }.orEmpty()
        val index = host.open(widthPx, heightPx, url, origin.orEmpty(), script)
        panel.producerIndex = index
        panel.onResizeContent = { width, height -> host.resize(index, width, height) }
        byProducer[index] = panel
        return panel
    }

    private fun dispose(panel: DesktopPanel) {
        panel.producerIndex?.let {
            byProducer.remove(it)
            host.close(it, frame())
        }
        sockets.unregister(panel.id)
    }

    override fun openApp(windowId: String, app: AppInfo, url: String): DesktopPanel {
        val panel = producerPanel(windowId, app.widthPx, app.heightPx, url, Shell.ORIGIN)
        panel.sizeLimits = SizeLimits.defaultFor(app.widthPx, app.heightPx)
        scene.open(panel)
        log("  shell: opened ${app.id} as $windowId")
        return panel
    }

    override fun openWeb(windowId: String, url: String, private: Boolean): DesktopPanel {
        val panel = producerPanel(windowId, WEB_WIDTH_PX, WEB_HEIGHT_PX, url, null)
        scene.open(panel)
        return panel
    }

    override fun openOverlays(dock: AppInfo, dockUrl: String, launcher: AppInfo, launcherUrl: String) {
        val dockPanel = producerPanel(dock.id, dock.widthPx, dock.heightPx, dockUrl, Shell.ORIGIN)
        dockPanel.cornerRadiusPx = dock.heightPx / 2f
        val launcherPanel = producerPanel(launcher.id, launcher.widthPx, launcher.heightPx, launcherUrl, Shell.ORIGIN)
        scene.setOverlays(dockPanel, launcherPanel)
    }

    override fun openMenuOverlay(app: AppInfo, url: String) {
        val panel = producerPanel(app.id, app.widthPx, app.heightPx, url, Shell.ORIGIN)
        panel.cornerRadiusPx = MENU_CORNER_PX
        scene.setPopupPanel(panel)
    }

    override fun prepareMenu(widthPx: Int, heightPx: Int) {
        scene.popup?.resizeContent(widthPx, heightPx)
    }

    override fun showMenu(sourceId: String, xPx: Float, yPx: Float) {
        val parent = when (sourceId) {
            Shell.DOCK -> scene.dock
            Shell.LAUNCHER -> scene.launcher
            else -> runCatching { shell.find(sourceId).panel }.getOrNull()
        } ?: return
        scene.showPopup(parent, xPx, yPx)
    }

    override fun hideMenu() = scene.hidePopup()

    override fun close(panel: DesktopPanel) {
        scene.remove(panel)
        dispose(panel)
        scene.layOutCylindrical(immediate = false)
    }

    override fun recenter() = onRecenter()

    override fun headYawRadians() = compositor.headYawRadians

    override fun headPitchRadians() = scene.gazePitchRadians ?: 0f

    override fun applySettings(settings: ShellSettings, changedKey: String?) {
        settings.applyTo(scene)
        if (changedKey == ShellSettings.PANEL_DISTANCE_METRES) {
            val metres = distance()
            (scene.panels + listOfNotNull(scene.dock, scene.launcher)).forEach { panel ->
                panel.pose = panel.pose.copy(distanceMetres = metres)
                panel.fitToFov(verticalFovDegrees, fillFor(panel.heightPx))
            }
        }
    }

    override fun setWindowsAutoHide(enabled: Boolean) {
        scene.windowsAutoHide = enabled
    }

    override fun notice(text: String) {
        compositor.notice = Notice(text, 0f)
        noticeUntilNs = System.nanoTime() + NOTICE_NS
    }

    override fun clipboardText(): String? = glfwGetClipboardString(0L)

    override fun setClipboardText(text: String) = glfwSetClipboardString(0L, text)

    private fun distance() = shell.settings.number(ShellSettings.PANEL_DISTANCE_METRES).toFloat()

    private fun fillFor(heightPx: Int) = PANEL_VERTICAL_FILL * heightPx / REFERENCE_HEIGHT_PX

    override fun close() {
        sockets.close()
        io.shutdown()
        background.shutdown()
    }

    /** A panel's picture before the producer's first frame: nothing to draw yet. */
    private object NoTexture : TextureSource {
        override val view = 0L
        override val widthPx = 1
        override val heightPx = 1
        override val ready = false
        override fun close() = Unit
    }

    private companion object {
        const val REGISTRY = "apps.json"
        const val PANEL_VERTICAL_FILL = 0.75f
        const val REFERENCE_HEIGHT_PX = 800f
        const val WEB_WIDTH_PX = 1280
        const val WEB_HEIGHT_PX = 800
        const val MENU_CORNER_PX = 14f
        const val EYE_WIDTH_PX = 1920
        const val EYE_HEIGHT_PX = 1080
        const val POLL_NS = 200_000_000L
        const val NOTICE_NS = 2_500_000_000L
    }
}

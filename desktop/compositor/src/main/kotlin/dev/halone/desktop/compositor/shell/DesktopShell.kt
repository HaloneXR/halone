package dev.halone.desktop.compositor.shell

import dev.halone.compositor.Notice
import dev.halone.compositor.PanelPose
import dev.halone.compositor.SizeLimits
import dev.halone.desktop.compositor.producer.ProducerHost
import dev.halone.desktop.compositor.render.DesktopCompositor
import dev.halone.desktop.compositor.render.DesktopPanel
import dev.halone.desktop.compositor.vk.TextureSource
import dev.halone.desktop.shell.AppData
import dev.halone.desktop.shell.BridgeSockets
import dev.halone.desktop.shell.DevServer
import dev.halone.desktop.shell.DesktopFolders
import dev.halone.desktop.shell.DesktopStorageAccess
import dev.halone.desktop.shell.DesktopThumbnails
import dev.halone.desktop.shell.FileStore
import dev.halone.shell.AppInfo
import dev.halone.shell.BridgeHost
import dev.halone.shell.DevCommands
import dev.halone.shell.InstalledApp
import dev.halone.shell.PackageApps
import dev.halone.shell.FileServer
import dev.halone.shell.KeyChord
import dev.halone.shell.SharedFiles
import dev.halone.shell.Shell
import dev.halone.shell.ShellPlatform
import dev.halone.shell.ShellSettings
import dev.halone.shell.ShortcutKeys
import dev.halone.shell.Shortcuts
import dev.halone.shell.Sockets
import dev.halone.shell.SystemInfo
import dev.halone.shell.applyTo
import dev.halone.shell.runShortcut
import org.lwjgl.glfw.GLFW.*
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
        packageApps = PackageApps(File(AppData.dir, "apps"), FileStore("packages.json", io)),
    )

    private val devCommands = DevCommands(
        shell, version, source = "cli", devServerHint = "on this machine",
        background = { background.execute(it) }, onMain = ::post, log = log,
    )
    private val devServer = DevServer(devCommands::run, ::post, log)

    private val files = SharedFiles(DesktopFolders::roots, DesktopStorageAccess)
    private val fileServer = FileServer(files, thumbnailer = DesktopThumbnails()::of, log = log).also { files.urlBase = it.base }
    private val tcp = Sockets(onMain = ::post)

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
        files = files,
        onMain = ::post,
        background = { background.execute(it) },
        sockets = tcp,
    )

    private val shortcuts = Shortcuts(File(webRoot, Shortcuts.ASSET).readText())

    private val shortcutKeys = ShortcutKeys { chord ->
        runCatching { shell.runShortcut(chord, shortcuts, scene.keyTarget, captureOn = false, log = log) }
            .onFailure { log("  shortcut $chord failed: $it") }
            .getOrDefault(true)
    }

    /** A GLFW key event; true when a shortcut takes it, so it goes no further. */
    fun key(key: Int, action: Int, modifiers: Int): Boolean {
        val meta = key == GLFW_KEY_LEFT_SUPER || key == GLFW_KEY_RIGHT_SUPER
        val chord = chordOf(key)?.let {
            KeyChord(it, ctrl = modifiers and GLFW_MOD_CONTROL != 0, alt = modifiers and GLFW_MOD_ALT != 0,
                shift = modifiers and GLFW_MOD_SHIFT != 0, meta = modifiers and GLFW_MOD_SUPER != 0)
        }
        return shortcutKeys.onKey(key, meta, action != GLFW_RELEASE, action == GLFW_REPEAT, chord)
    }

    /** The keys `shortcuts.json` names, as MainActivity's chordOf names them. */
    private fun chordOf(key: Int): String? = when (key) {
        in GLFW_KEY_A..GLFW_KEY_Z -> ('A' + (key - GLFW_KEY_A)).toString()
        in GLFW_KEY_F1..GLFW_KEY_F12 -> "F${key - GLFW_KEY_F1 + 1}"
        GLFW_KEY_TAB -> "Tab"
        GLFW_KEY_GRAVE_ACCENT -> "`"
        GLFW_KEY_ESCAPE -> "Escape"
        GLFW_KEY_PRINT_SCREEN -> "PrintScreen"
        GLFW_KEY_LEFT -> "ArrowLeft"
        GLFW_KEY_RIGHT -> "ArrowRight"
        else -> null
    }

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
            bridge.checkFileAccess()
        }
        if (compositor.notice != null && nowNs >= noticeUntilNs) compositor.notice = null
    }

    private fun post(task: Runnable) {
        queue += task
    }

    /** A panel drawn by the producer, sized as Android sizes its windows; [origin] gets the bridge, and [root] serves it. */
    private fun producerPanel(id: String, widthPx: Int, heightPx: Int, url: String, origin: String?, root: File? = null): DesktopPanel {
        val panel = DesktopPanel(id, widthPx, heightPx, NoTexture, PanelPose(distanceMetres = distance()))
        panel.fitToFov(verticalFovDegrees, fillFor(heightPx))
        val script = origin?.let { sockets.register(id, it) { channel -> bridge.connect(id, channel) } }.orEmpty()
        val index = host.open(widthPx, heightPx, url, origin.orEmpty(), script, root)
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

    override fun openPackageApp(windowId: String, app: InstalledApp, files: File?, url: String): DesktopPanel {
        val origin = java.net.URI(url).let { "${it.scheme}://${it.rawAuthority}" }
        val panel = producerPanel(windowId, app.manifest.widthPx, app.manifest.heightPx, url, origin, files)
        panel.sizeLimits = SizeLimits.defaultFor(app.manifest.widthPx, app.manifest.heightPx)
        scene.open(panel)
        log("  shell: opened package ${app.id} as $windowId from $origin")
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
        devServer.close()
        fileServer.close()
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

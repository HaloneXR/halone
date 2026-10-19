package dev.halone.desktop.compositor.producer

import dev.halone.compositor.CursorShape
import dev.halone.desktop.compositor.vk.VulkanContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/** What the wearer asked for a context menu on, in the page's pixels; [media] is image, video or audio, or null. */
data class ContextTarget(
    val x: Float,
    val y: Float,
    val link: String?,
    val source: String?,
    val media: String?,
    val selection: String,
    val editable: Boolean,
    val canCut: Boolean,
    val canCopy: Boolean,
    val canPaste: Boolean,
)

/** A download of a page's, as the producer reports it; [state] is one of the DOWNLOAD_ constants. */
data class DownloadEvent(
    val id: Int,
    val state: Int,
    val received: Long,
    val total: Long,
    val url: String,
    val name: String,
    val mime: String,
    val path: String,
)

/** A permission a page asks for, by the protocol's PermissionKind; [gone] when it no longer waits for an answer. */
data class PromptEvent(val id: Int, val kind: String, val host: String, val rememberable: Boolean, val gone: Boolean)

/** Where a panel's page is, as the producer last said. */
data class PageState(val title: String, val url: String, val loading: Boolean, val canGoBack: Boolean, val canGoForward: Boolean)

data class PanelSpec(val width: Int, val height: Int, val url: String) {
    override fun toString() = "${width}x$height@$url"

    companion object {
        fun parse(spec: String): PanelSpec {
            val (size, url) = spec.split("@", limit = 2)
            val (w, h) = size.split("x")
            return PanelSpec(w.toInt(), h.toInt(), url)
        }
    }
}

/**
 * One producer process and its panels: launches it, takes its surfaces and
 * frames, and sends it ticks and input. Everything but [receive] runs on the
 * render thread.
 */
class ProducerHost(
    private val vk: VulkanContext,
    executable: String,
    specs: List<PanelSpec>,
    marginMs: Float,
    log: File,
    /** Served as https://halone.local/, where the system apps live; null serves nothing there. */
    webRoot: File? = null,
    /** Unpacked extensions every page runs with, such as the ad blocker; loaded only at start. */
    extensions: List<File> = emptyList(),
) : AutoCloseable {
    private val transport = ProducerTransport.forThisPlatform()
    private val process: Process
    private val events = ConcurrentLinkedQueue<ProducerEvent>()
    @Volatile private var running = true
    private val receiver: Thread
    private val textures = HashMap<Int, ProducerTexture>()
    private val pending = HashMap<Int, ProducerEvent>()
    private val resizes = HashMap<Int, ProducerEvent>()
    private val pendingResized = HashMap<Int, ProducerEvent>()
    private class Held(val panel: Int, val texture: ProducerTexture, val index: Int, val frame: Long)
    private val held = ArrayDeque<Held>()
    private val retired = ArrayDeque<Pair<ProducerTexture, Long>>()

    private val closed = HashSet<Int>()

    /** What was asked of the producer before it was connected. */
    private val waiting = mutableListOf<() -> Unit>()
    private val newFrames = HashMap<Int, Int>()
    private var nextPanel = specs.size

    /** Called on the render thread when a panel's surfaces arrive. */
    var onPanelReady: ((index: Int, texture: ProducerTexture) -> Unit)? = null

    /** Called on the render thread when a resized panel's page is ready at its new size. */
    var onPanelResized: ((index: Int, texture: ProducerTexture) -> Unit)? = null

    /** Called on the render thread when a panel's content asks for another cursor. */
    var onCursorChanged: ((index: Int, shape: CursorShape) -> Unit)? = null

    /** Called on the render thread when a panel's page changes its title, URL, or loading and history state. */
    var onPageChanged: ((index: Int, page: PageState) -> Unit)? = null

    /** Called on the render thread when a panel's page asks for [url] in a new window or tab. */
    var onPopup: ((index: Int, url: String, background: Boolean) -> Unit)? = null

    var onContextMenu: ((index: Int, target: ContextTarget) -> Unit)? = null
    var onDownload: ((index: Int, download: DownloadEvent) -> Unit)? = null
    var onPrompt: ((index: Int, prompt: PromptEvent) -> Unit)? = null
    var onFullScreen: ((index: Int, fullScreen: Boolean) -> Unit)? = null

    /** A page's vertical scroll offset, in its pixels. */
    var onScroll: ((index: Int, y: Float) -> Unit)? = null

    /** Called on the render thread just before a replaced texture is closed. */
    var onTextureClosed: ((ProducerTexture) -> Unit)? = null

    init {
        receiver = Thread({ receive() }, "producer-receive").apply { isDaemon = true; start() }
        process = ProcessBuilder(
            listOf(executable) + transport.producerArguments + listOf(
                "--spike-panels=${specs.joinToString(",")}",
                "--spike-margin=$marginMs",
                "--spike-surfaces=$SURFACES",
            ) + listOfNotNull(
                webRoot?.let { "--halone-web-root=${it.absolutePath}" },
                extensions.takeIf { it.isNotEmpty() }?.let { list -> "--halone-extensions=" + list.joinToString(",") { it.absolutePath } },
            ),
        ).redirectErrorStream(true).redirectOutput(log).start()
    }

    private fun receive() {
        while (running) transport.receive(500)?.let { events += it }
    }

    /**
     * Adopts what arrived since the last frame; [frame] counts frames, for
     * releasing surfaces the GPU is done with. A [frozen] panel keeps showing
     * the picture it has, as a window easing into a new size does on Android;
     * its page at the new size is taken up once it thaws and has painted.
     */
    fun update(frame: Long, frozen: (Int) -> Boolean = { false }) {
        if (transport.connected && waiting.isNotEmpty()) {
            waiting.forEach { it() }
            waiting.clear()
        }
        while (true) {
            val e = events.poll() ?: break
            when (e.kind) {
                ProducerEvent.HELLO -> {
                    val surfaces = e.surfaces ?: continue
                    if (e.panel in closed) {
                        surfaces.release()
                    } else if (e.panel in textures) {
                        resizes.put(e.panel, e)?.surfaces?.release()
                        pendingResized.remove(e.panel)
                    } else {
                        val texture = ProducerTexture(vk, surfaces)
                        textures[e.panel] = texture
                        onPanelReady?.invoke(e.panel, texture)
                    }
                }
                ProducerEvent.CURSOR -> onCursorChanged?.invoke(e.panel, CursorShape.ofCss(e.cursor))
                ProducerEvent.PAGE -> if (e.panel !in closed) onPageChanged?.invoke(e.panel, PageState(e.title, e.text,
                    loading = e.flags and PAGE_LOADING != 0, canGoBack = e.flags and PAGE_CAN_GO_BACK != 0,
                    canGoForward = e.flags and PAGE_CAN_GO_FORWARD != 0))
                ProducerEvent.POPUP -> if (e.panel !in closed) onPopup?.invoke(e.panel, e.text, e.flags != 0)
                ProducerEvent.CONTEXT -> if (e.panel !in closed) onContextMenu?.invoke(e.panel, ContextTarget(
                    e.x, e.y, e.text.ifEmpty { null }, e.extra.ifEmpty { null },
                    when (e.number) { MEDIA_IMAGE -> "image"; MEDIA_VIDEO -> "video"; MEDIA_AUDIO -> "audio"; else -> null },
                    e.title, editable = e.flags and CONTEXT_EDITABLE != 0, canCut = e.flags and CONTEXT_CAN_CUT != 0,
                    canCopy = e.flags and CONTEXT_CAN_COPY != 0, canPaste = e.flags and CONTEXT_CAN_PASTE != 0,
                ))
                ProducerEvent.DOWNLOAD -> onDownload?.invoke(e.panel, DownloadEvent(e.number, e.state, e.received, e.total, e.text, e.title, e.mime, e.path))
                ProducerEvent.PROMPT -> if (e.panel !in closed) onPrompt?.invoke(e.panel,
                    PromptEvent(e.number, e.cursor, e.title, rememberable = e.flags and PROMPT_REMEMBERABLE != 0, gone = e.flags and PROMPT_GONE != 0))
                ProducerEvent.FULLSCREEN -> if (e.panel !in closed) onFullScreen?.invoke(e.panel, e.flags != 0)
                ProducerEvent.SCROLL -> if (e.panel !in closed) onScroll?.invoke(e.panel, e.y)
                ProducerEvent.FRAME -> {
                    if (e.panel in closed) {
                        transport.skipped(e.panel, e.index, e.generation)
                        continue
                    }
                    val into = if (e.panel in resizes) pendingResized else pending
                    val previous = into[e.panel]
                    if ((previous?.seq ?: -1) < e.seq) {
                        into[e.panel] = e
                        previous?.let { transport.skipped(it.panel, it.index, it.generation) }
                    } else {
                        transport.skipped(e.panel, e.index, e.generation)
                    }
                }
            }
        }
        for (panel in resizes.keys.filter { it in pendingResized && !frozen(it) }) {
            val e = resizes.remove(panel)!!
            val texture = ProducerTexture(vk, e.surfaces!!)
            textures.put(panel, texture)?.let { retired.addLast(it to frame) }
            pending[panel] = pendingResized.remove(panel)!!
            onPanelResized?.invoke(panel, texture)
        }
        for (panel in pending.keys.filter { !frozen(it) }) {
            val e = pending.remove(panel)!!
            val texture = textures[panel] ?: continue
            if (e.index !in 0 until texture.surfaces.count || e.index == texture.current) continue
            transport.showing(panel, texture, e.index)
            if (texture.current >= 0) held.addLast(Held(panel, texture, texture.current, frame))
            texture.current = e.index
            newFrames[panel] = (newFrames[panel] ?: 0) + 1
        }
        while (held.isNotEmpty() && frame - held.first().frame > HOLD_FRAMES) {
            val h = held.removeFirst()
            transport.done(h.panel, h.texture, h.index)
        }
        while (retired.isNotEmpty() && frame - retired.first().second > HOLD_FRAMES) {
            close(retired.removeFirst().first)
        }
    }

    /**
     * Opens a panel on [url], answering its number; [onPanelReady] follows once
     * its surfaces arrive. [script] runs after each load of a page on [origin],
     * which [root] serves when given.
     */
    fun open(widthPx: Int, heightPx: Int, url: String, origin: String = "", script: String = "", root: File? = null, private: Boolean = false): Int {
        val panel = nextPanel++
        val flags = if (private) OPEN_PRIVATE else 0
        val send = { transport.open(panel, widthPx, heightPx, flags, url, origin, script, root?.absolutePath.orEmpty()) }
        if (transport.connected && waiting.isEmpty()) send() else waiting += send
        return panel
    }

    /** Closes [panel]; its texture is let go once no frame in flight can still be drawing it. */
    fun close(panel: Int, frame: Long) {
        val send = { transport.closePanel(panel) }
        if (transport.connected && waiting.isEmpty()) send() else waiting += send
        pending.remove(panel)
        pendingResized.remove(panel)
        resizes.remove(panel)?.surfaces?.release()
        textures.remove(panel)?.let { retired.addLast(it to frame) }
        closed += panel
    }

    /** Says where download [download] goes, or with [cancel] that it stops. */
    fun downloadControl(download: Int, cancel: Boolean, path: String = "") {
        val send = { transport.downloadControl(download, cancel, path) }
        if (transport.connected && waiting.isEmpty()) send() else waiting += send
    }

    fun answerPrompt(panel: Int, prompt: Int, allow: Boolean, remember: Boolean) {
        val send = { transport.answerPrompt(panel, prompt, allow, remember) }
        if (transport.connected && waiting.isEmpty()) send() else waiting += send
    }

    fun command(panel: Int, kind: Int, text: String = "") {
        val send = { transport.command(panel, kind, text) }
        if (transport.connected && waiting.isEmpty()) send() else waiting += send
    }

    /** Asks the producer to lay [panel]'s page out at a new size; [onPanelResized] follows. */
    fun resize(panel: Int, widthPx: Int, heightPx: Int) = transport.resize(panel, widthPx, heightPx)

    private fun close(texture: ProducerTexture) {
        onTextureClosed?.invoke(texture)
        val panel = textures.entries.firstOrNull { it.value === texture }?.key ?: -1
        if (texture.current >= 0) transport.done(panel, texture, texture.current)
        texture.close()
    }

    /** Asks the producer to begin its next frame so it is ready by [targetNs] (System.nanoTime). */
    fun tick(targetNs: Long) = transport.tick(targetNs)

    fun input(panel: Int, kind: Int, x: Float, y: Float, dx: Float = 0f, dy: Float = 0f, button: Int = 0, clicks: Int = 0,
              modifiers: Int = 0, keyCode: Int = 0, character: Int = 0, unmodified: Int = 0) =
        transport.input(panel, kind, x, y, dx, dy, button, clicks, modifiers, keyCode, character, unmodified)

    /** New frames per panel since the last call. */
    fun takeFrameCounts(): Map<Int, Int> = HashMap(newFrames).also { newFrames.clear() }

    val alive: Boolean get() = process.isAlive

    override fun close() {
        running = false
        process.destroy()
        process.waitFor()
        receiver.join(1000)
        held.forEach { transport.done(it.panel, it.texture, it.index) }
        held.clear()
        onTextureClosed = null
        retired.forEach { close(it.first) }
        retired.clear()
        textures.values.forEach(::close)
        textures.clear()
        resizes.values.forEach { it.surfaces?.release() }
        resizes.clear()
        transport.close()
    }

    companion object {
        const val SURFACES = 6
        private const val HOLD_FRAMES = 3

        const val INPUT_MOVE = 1
        const val INPUT_DOWN = 2
        const val INPUT_UP = 3
        const val INPUT_WHEEL = 4
        const val INPUT_LEAVE = 5
        const val INPUT_KEY_DOWN = 6
        const val INPUT_KEY_UP = 7
        const val INPUT_FOCUS = 8

        const val COMMAND_NAVIGATE = 1
        const val COMMAND_BACK = 2
        const val COMMAND_FORWARD = 3
        const val COMMAND_RELOAD = 4
        const val COMMAND_STOP = 5
        const val COMMAND_CUT = 6
        const val COMMAND_COPY = 7
        const val COMMAND_PASTE = 8
        const val COMMAND_SELECT_ALL = 9
        const val COMMAND_DOWNLOAD = 10
        const val COMMAND_SCROLL_BY = 11

        const val DOWNLOAD_STARTED = 1
        const val DOWNLOAD_PROGRESS = 2
        const val DOWNLOAD_DONE = 3
        const val DOWNLOAD_FAILED = 4
        const val DOWNLOAD_CANCELLED = 5

        private const val CONTEXT_EDITABLE = 1
        private const val CONTEXT_CAN_CUT = 2
        private const val CONTEXT_CAN_COPY = 4
        private const val CONTEXT_CAN_PASTE = 8
        private const val MEDIA_IMAGE = 1
        private const val MEDIA_VIDEO = 2
        private const val MEDIA_AUDIO = 3
        private const val PROMPT_REMEMBERABLE = 1
        private const val PROMPT_GONE = 2

        private const val OPEN_PRIVATE = 1
        private const val PAGE_LOADING = 1
        private const val PAGE_CAN_GO_BACK = 2
        private const val PAGE_CAN_GO_FORWARD = 4
    }
}

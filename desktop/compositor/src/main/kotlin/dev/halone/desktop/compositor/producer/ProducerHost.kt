package dev.halone.desktop.compositor.producer

import dev.halone.desktop.compositor.vk.VulkanContext
import com.sun.jna.ptr.IntByReference
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

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
 * One producer process and its panels: launches it, takes its IOSurfaces and
 * frames, and sends it ticks and input. Everything but [receive] runs on the
 * render thread.
 */
class ProducerHost(
    private val vk: VulkanContext,
    executable: String,
    specs: List<PanelSpec>,
    marginMs: Float,
    log: File,
) : AutoCloseable {
    private val bridge = Bridge.native
    private val port: Int
    private val process: Process
    private val events = ConcurrentLinkedQueue<Event>()
    @Volatile private var running = true
    private val receiver: Thread
    private var producerPort = 0
    private val textures = HashMap<Int, ProducerTexture>()
    private val pending = HashMap<Int, Event>()
    private val resizes = HashMap<Int, Event>()
    private val pendingResized = HashMap<Int, Event>()
    private val held = ArrayDeque<Pair<Long, Long>>()
    private val retired = ArrayDeque<Pair<ProducerTexture, Long>>()
    private val newFrames = HashMap<Int, Int>()

    /** Called on the render thread when a panel's surfaces arrive. */
    var onPanelReady: ((index: Int, texture: ProducerTexture) -> Unit)? = null

    /** Called on the render thread when a resized panel's page is ready at its new size. */
    var onPanelResized: ((index: Int, texture: ProducerTexture) -> Unit)? = null

    /** Called on the render thread just before a replaced texture is closed. */
    var onTextureClosed: ((ProducerTexture) -> Unit)? = null

    private data class Event(
        val kind: Int, val panel: Int, val index: Int, val count: Int, val width: Int, val height: Int,
        val reply: Int, val seq: Long, val readyNs: Long, val arrivedNs: Long, val surfaces: LongArray,
    )

    init {
        val service = "dev.halone.desktop.${ProcessHandle.current().pid()}"
        val pPort = IntByReference()
        val kr = bridge.halone_check_in(service, pPort)
        check(kr == 0) { "bootstrap_check_in failed: $kr" }
        port = pPort.value
        receiver = Thread({ receive() }, "producer-receive").apply { isDaemon = true; start() }
        process = ProcessBuilder(
            executable,
            "--spike-service=$service",
            "--spike-panels=${specs.joinToString(",")}",
            "--spike-margin=$marginMs",
            "--spike-surfaces=$SURFACES",
        ).redirectErrorStream(true).redirectOutput(log).start()
    }

    private fun receive() {
        val event = BridgeEvent()
        while (running) {
            if (bridge.halone_receive(port, event, 500) != 0 || event.kind == BridgeEvent.NONE) continue
            events += Event(
                event.kind, event.panel, event.index, event.count, event.width, event.height,
                event.reply, event.seq, event.readyNs, event.arrivedNs, event.surfaces.copyOf(event.count),
            )
        }
    }

    /**
     * Adopts what arrived since the last frame; [frame] counts frames, for
     * releasing surfaces the GPU is done with. A [frozen] panel keeps showing
     * the picture it has, as a window easing into a new size does on Android;
     * its page at the new size is taken up once it thaws and has painted.
     */
    fun update(frame: Long, frozen: (Int) -> Boolean = { false }) {
        while (true) {
            val e = events.poll() ?: break
            when (e.kind) {
                BridgeEvent.HELLO -> {
                    if (producerPort == 0) producerPort = e.reply else bridge.halone_release_port(e.reply)
                    if (e.panel in textures) {
                        resizes.put(e.panel, e)?.surfaces?.forEach(bridge::halone_surface_release)
                        pendingResized.remove(e.panel)
                    } else {
                        val texture = ProducerTexture(vk, e.width, e.height, e.surfaces)
                        textures[e.panel] = texture
                        onPanelReady?.invoke(e.panel, texture)
                    }
                }
                BridgeEvent.FRAME -> {
                    val into = if (e.panel in resizes) pendingResized else pending
                    if ((into[e.panel]?.seq ?: -1) < e.seq) into[e.panel] = e
                }
            }
        }
        for (panel in resizes.keys.filter { it in pendingResized && !frozen(it) }) {
            val e = resizes.remove(panel)!!
            val texture = ProducerTexture(vk, e.width, e.height, e.surfaces)
            textures.put(panel, texture)?.let { retired.addLast(it to frame) }
            pending[panel] = pendingResized.remove(panel)!!
            onPanelResized?.invoke(panel, texture)
        }
        for (panel in pending.keys.filter { !frozen(it) }) {
            val e = pending.remove(panel)!!
            val texture = textures[panel] ?: continue
            if (e.index !in texture.surfaces.indices || e.index == texture.current) continue
            bridge.halone_surface_use(texture.surfaces[e.index], 1)
            if (texture.current >= 0) held.addLast(texture.surfaces[texture.current] to frame)
            texture.current = e.index
            newFrames[panel] = (newFrames[panel] ?: 0) + 1
        }
        while (held.isNotEmpty() && frame - held.first().second > HOLD_FRAMES) {
            bridge.halone_surface_use(held.removeFirst().first, -1)
        }
        while (retired.isNotEmpty() && frame - retired.first().second > HOLD_FRAMES) {
            close(retired.removeFirst().first)
        }
    }

    /** Asks the producer to lay [panel]'s page out at a new size; [onPanelResized] follows. */
    fun resize(panel: Int, widthPx: Int, heightPx: Int) {
        if (producerPort != 0) bridge.halone_send_resize(producerPort, panel, widthPx, heightPx)
    }

    private fun close(texture: ProducerTexture) {
        onTextureClosed?.invoke(texture)
        if (texture.current >= 0) bridge.halone_surface_use(texture.surfaces[texture.current], -1)
        texture.close()
    }

    /** Asks the producer to begin its next frame so it is ready by [targetNs] (System.nanoTime). */
    fun tick(targetNs: Long) {
        if (producerPort != 0) bridge.halone_send_tick(producerPort, targetNs)
    }

    fun input(panel: Int, kind: Int, x: Float, y: Float, dx: Float = 0f, dy: Float = 0f, button: Int = 0, clicks: Int = 0,
              modifiers: Int = 0, keyCode: Int = 0, character: Int = 0, unmodified: Int = 0) {
        if (producerPort != 0) bridge.halone_send_input(producerPort, panel, kind, x, y, dx, dy, button, clicks, modifiers, keyCode, character, unmodified)
    }

    /** New frames per panel since the last call. */
    fun takeFrameCounts(): Map<Int, Int> = HashMap(newFrames).also { newFrames.clear() }

    val alive: Boolean get() = process.isAlive

    override fun close() {
        running = false
        process.destroy()
        process.waitFor()
        receiver.join(1000)
        held.forEach { bridge.halone_surface_use(it.first, -1) }
        held.clear()
        onTextureClosed = null
        retired.forEach { close(it.first) }
        retired.clear()
        textures.values.forEach(::close)
        textures.clear()
        resizes.values.forEach { it.surfaces.forEach(bridge::halone_surface_release) }
        resizes.clear()
        bridge.halone_release_port(producerPort)
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
    }
}

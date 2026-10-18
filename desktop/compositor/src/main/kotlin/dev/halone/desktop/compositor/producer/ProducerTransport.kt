package dev.halone.desktop.compositor.producer

import dev.halone.desktop.compositor.vk.VulkanContext

/** A producer's ring of surfaces for one panel, as its platform hands them over. */
interface ProducerSurfaces {
    val count: Int
    val width: Int
    val height: Int

    /** Bumped by every resize; frames and releases name it. */
    val generation: Int

    /** Creates surface [index]'s Vulkan image bound to its memory, returning (image, memory). */
    fun import(vk: VulkanContext, index: Int): Pair<Long, Long>

    /** Gives back whatever import did not take over. */
    fun release()
}

class ProducerEvent(
    val kind: Int,
    val panel: Int,
    val index: Int = 0,
    val generation: Int = 0,
    val seq: Long = 0,
    val readyNs: Long = 0,
    val arrivedNs: Long = 0,
    val surfaces: ProducerSurfaces? = null,
    val cursor: String = "",
) {
    companion object {
        const val HELLO = 1
        const val FRAME = 2
        const val CURSOR = 3
    }
}

/**
 * How the compositor and a producer process talk: Mach ports and IOSurfaces on
 * macOS, a Unix socket and DMA-BUFs elsewhere. [receive] runs on the receiving
 * thread, everything else on the render thread.
 */
interface ProducerTransport : AutoCloseable {
    /** Arguments telling the producer how to reach this compositor. */
    val producerArguments: List<String>

    /** Whether the producer can be sent messages yet. */
    val connected: Boolean

    /** The next event, or null if none came within [timeoutMs]. */
    fun receive(timeoutMs: Int): ProducerEvent?

    fun tick(targetNs: Long)

    fun input(panel: Int, kind: Int, x: Float, y: Float, dx: Float, dy: Float, button: Int, clicks: Int,
              modifiers: Int, keyCode: Int, character: Int, unmodified: Int)

    fun resize(panel: Int, width: Int, height: Int)

    /** A new panel on [url]; [script] runs after each load of a page on [origin], which [root] serves when not empty. */
    fun open(panel: Int, width: Int, height: Int, url: String, origin: String, script: String, root: String)

    fun closePanel(panel: Int)

    /** The compositor starts showing surface [index] of [texture]. */
    fun showing(panel: Int, texture: ProducerTexture, index: Int)

    /** The GPU is done with surface [index] of [texture], which was shown. */
    fun done(panel: Int, texture: ProducerTexture, index: Int)

    /** A frame was passed over for a newer one without being shown. */
    fun skipped(panel: Int, index: Int, generation: Int)

    companion object {
        fun forThisPlatform(): ProducerTransport =
            if (System.getProperty("os.name").lowercase().contains("mac")) MachTransport() else UnixTransport()
    }
}

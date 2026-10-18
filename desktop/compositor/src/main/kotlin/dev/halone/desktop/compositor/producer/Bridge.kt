package dev.halone.desktop.compositor.producer

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference

@Structure.FieldOrder(
    "kind", "panel", "index", "count", "width", "height", "reply", "pad",
    "seq", "begunNs", "paintedNs", "readyNs", "arrivedNs", "surfaces", "name",
)
class BridgeEvent : Structure() {
    @JvmField var kind = 0
    @JvmField var panel = 0
    @JvmField var index = 0
    @JvmField var count = 0
    @JvmField var width = 0
    @JvmField var height = 0
    @JvmField var reply = 0
    @JvmField var pad = 0
    @JvmField var seq = 0L
    @JvmField var begunNs = 0L
    @JvmField var paintedNs = 0L
    @JvmField var readyNs = 0L
    @JvmField var arrivedNs = 0L
    @JvmField var surfaces = LongArray(MAX_SURFACES)
    @JvmField var name = ByteArray(CURSOR_NAME_BYTES)

    companion object {
        const val NONE = 0
        const val HELLO = 1
        const val FRAME = 2
        const val CURSOR = 3
        const val MAX_SURFACES = 8
        const val CURSOR_NAME_BYTES = 32
    }
}

@Suppress("FunctionName")
interface Bridge : Library {
    fun halone_check_in(name: String, port: IntByReference): Int
    fun halone_receive(port: Int, out: BridgeEvent, timeoutMs: Int): Int
    fun halone_send_tick(producer: Int, targetNs: Long): Int
    fun halone_send_input(
        producer: Int, panel: Int, kind: Int, x: Float, y: Float, dx: Float, dy: Float,
        button: Int, clicks: Int, modifiers: Int, keyCode: Int, character: Int, unmodified: Int,
    ): Int
    fun halone_send_resize(producer: Int, panel: Int, width: Int, height: Int): Int
    fun halone_send_open(producer: Int, panel: Int, width: Int, height: Int, url: String, origin: String, script: String, root: String): Int
    fun halone_send_close(producer: Int, panel: Int): Int
    fun halone_surface_use(surface: Long, delta: Int)
    fun halone_surface_release(surface: Long)
    fun halone_release_port(port: Int)

    companion object {
        val native: Bridge by lazy { Native.load("halone_bridge", Bridge::class.java) }
    }
}

package dev.halone.desktop.compositor.producer

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference

@Structure.FieldOrder(
    "kind", "panel", "index", "count", "width", "height", "reply", "pad",
    "seq", "begunNs", "paintedNs", "readyNs", "arrivedNs", "surfaces", "name", "flags", "title", "text",
    "number", "state", "received", "total", "x", "y", "extra", "path", "mime",
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
    @JvmField var flags = 0
    @JvmField var title = ByteArray(TITLE_BYTES)
    @JvmField var text = ByteArray(URL_BYTES)
    @JvmField var number = 0
    @JvmField var state = 0
    @JvmField var received = 0L
    @JvmField var total = 0L
    @JvmField var x = 0f
    @JvmField var y = 0f
    @JvmField var extra = ByteArray(BridgeEvent.URL_BYTES)
    @JvmField var path = ByteArray(BridgeEvent.PATH_BYTES)
    @JvmField var mime = ByteArray(BridgeEvent.MIME_BYTES)

    companion object {
        const val NONE = 0
        const val HELLO = 1
        const val FRAME = 2
        const val CURSOR = 3
        const val PAGE = 4
        const val POPUP = 5
        const val TITLE_BYTES = 512
        const val URL_BYTES = 2048
        const val PATH_BYTES = 1024
        const val MIME_BYTES = 128
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
    fun halone_send_open(producer: Int, panel: Int, width: Int, height: Int, flags: Int, url: String, origin: String, script: String, root: String): Int
    fun halone_send_command(producer: Int, panel: Int, kind: Int, text: String): Int
    fun halone_send_download_control(producer: Int, download: Int, cancel: Int, path: String): Int
    fun halone_send_prompt_answer(producer: Int, panel: Int, prompt: Int, allow: Int, remember: Int): Int
    fun halone_send_close(producer: Int, panel: Int): Int
    fun halone_surface_use(surface: Long, delta: Int)
    fun halone_surface_release(surface: Long)
    fun halone_release_port(port: Int)

    companion object {
        val native: Bridge by lazy { Native.load("halone_bridge", Bridge::class.java) }
    }
}

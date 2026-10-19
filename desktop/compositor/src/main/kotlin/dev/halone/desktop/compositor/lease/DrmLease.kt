package dev.halone.desktop.compositor.lease

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Suppress("FunctionName")
private interface LibC : Library {
    fun socket(domain: Int, type: Int, protocol: Int): Int
    fun connect(fd: Int, address: Pointer, length: Int): Int
    fun write(fd: Int, buffer: ByteArray, count: Long): Long
    fun recvmsg(fd: Int, message: Pointer, flags: Int): Long
    fun poll(fds: Pointer, count: Long, timeoutMs: Int): Int
    fun close(fd: Int): Int

    companion object {
        val native: LibC by lazy { Native.load("c", LibC::class.java) }
    }
}

/** A connector the desktop compositor offers for leasing through wp_drm_lease_device_v1. */
data class LeaseOffer(val connectorId: Int, val name: String, val description: String)

/**
 * A display leased from the Wayland compositor: [fd] is a DRM master fd limited to
 * [offer]'s connector, for VK_EXT_acquire_drm_display. The lease lasts as long as
 * this connection, so close it only after Vulkan has released the display.
 */
class DrmLease private constructor(private val wire: Wire, val offer: LeaseOffer, val fd: Int) : AutoCloseable {
    /** True once the compositor has revoked the lease, e.g. because the display went away. */
    val finished get() = wire.pump(0).let { wire.leaseFinished }

    override fun close() {
        wire.close()
    }

    companion object {
        /**
         * Leases the first offered connector whose name or description contains one of
         * [hints], or explains why none could be had.
         */
        fun acquire(hints: List<String>, log: (String) -> Unit = {}): Result<DrmLease> = runCatching {
            val wire = Wire.connect()
            try {
                val device = wire.bindLeaseDevice() ?: error("the compositor offers no wp_drm_lease_device_v1")
                val offers = wire.offersFrom(device)
                log("lease offers: ${offers.values.joinToString().ifEmpty { "none" }}")
                val (connector, offer) = offers.entries.firstOrNull { (_, o) ->
                    hints.any { o.description.contains(it, ignoreCase = true) || o.name.contains(it, ignoreCase = true) }
                }?.toPair() ?: error("no leasable connector matches ${hints.joinToString()}; is the glasses' EDID marked non-desktop?")
                val fd = wire.lease(device, connector)
                DrmLease(wire, offer, fd)
            } catch (e: Throwable) {
                wire.close()
                throw e
            }
        }
    }
}

/** The compositor's keymap (xkb text) as it gives any client asking for a keyboard; null where there is no Wayland. */
fun compositorKeymap(): String? = runCatching { Wire.connect().use { it.keymap() } }.getOrNull()

/** Just enough of the Wayland wire protocol for wp_drm_lease_v1 and a keymap. */
private class Wire(private val socket: Int) : AutoCloseable {
    private var nextId = 2
    private val pending = ByteBuffer.allocate(1 shl 16).order(ByteOrder.nativeOrder()).limit(0)
    private val fds = ArrayDeque<Int>()
    private val handlers = HashMap<Int, (opcode: Int, args: ByteBuffer) -> Unit>()
    var leaseFinished = false

    fun bindLeaseDevice(): Int? {
        val registry = newId()
        var device: Int? = null
        handlers[registry] = { opcode, args ->
            if (opcode == 0 && device == null) {
                val name = args.int
                if (string(args) == LEASE_DEVICE) {
                    device = newId()
                    send(registry, 0) { putInt(name); putString(LEASE_DEVICE); putInt(1); putInt(device!!) }
                }
            }
        }
        send(DISPLAY, 1) { putInt(registry) }
        roundTrip()
        return device
    }

    fun keymap(): String? {
        val registry = newId()
        var seat: Int? = null
        handlers[registry] = { opcode, args ->
            if (opcode == 0 && seat == null) {
                val name = args.int
                if (string(args) == "wl_seat") {
                    seat = newId()
                    send(registry, 0) { putInt(name); putString("wl_seat"); putInt(1); putInt(seat!!) }
                }
            }
        }
        send(DISPLAY, 1) { putInt(registry) }
        roundTrip()
        val seatId = seat ?: return null
        val keyboard = newId()
        var keymap: String? = null
        handlers[keyboard] = { opcode, args ->
            if (opcode == 0) {
                val format = args.int
                val size = args.int
                fds.removeFirstOrNull()?.let { fd ->
                    if (format == XKB_V1) keymap = runCatching { File("/proc/self/fd/$fd").readBytes().copyOf(size).decodeToString().trimEnd('\u0000') }.getOrNull()
                    LibC.native.close(fd)
                }
            }
        }
        send(seatId, 1) { putInt(keyboard) }
        roundTrip()
        return keymap
    }

    fun offersFrom(device: Int): Map<Int, LeaseOffer> {
        val connectors = LinkedHashMap<Int, Triple<String?, String?, Int?>>()
        var done = false
        handlers[device] = { opcode, args ->
            when (opcode) {
                0 -> fds.removeFirstOrNull()?.let { LibC.native.close(it) }
                1 -> {
                    val id = args.int
                    connectors[id] = Triple(null, null, null)
                    handlers[id] = { op, a ->
                        val c = connectors[id]
                        if (c != null) when (op) {
                            0 -> connectors[id] = c.copy(first = string(a))
                            1 -> connectors[id] = c.copy(second = string(a))
                            2 -> connectors[id] = c.copy(third = a.int)
                            4 -> connectors.remove(id)
                        }
                    }
                }
                2 -> done = true
            }
        }
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!done && System.nanoTime() < deadline) pump(100)
        if (!done) error("the lease device never finished listing connectors")
        return connectors.mapNotNull { (id, c) -> c.third?.let { id to LeaseOffer(it, c.first ?: "", c.second ?: "") } }.toMap()
    }

    fun lease(device: Int, connector: Int): Int {
        val request = newId()
        send(device, 0) { putInt(request) }
        send(request, 0) { putInt(connector) }
        val lease = newId()
        var fd: Int? = null
        handlers[lease] = { opcode, _ ->
            when (opcode) {
                0 -> fd = fds.removeFirstOrNull()
                1 -> leaseFinished = true
            }
        }
        send(request, 1) { putInt(lease) }
        val deadline = System.nanoTime() + 3_000_000_000L
        while (fd == null && !leaseFinished && System.nanoTime() < deadline) pump(100)
        return fd ?: error(if (leaseFinished) "the compositor refused the lease" else "the lease never arrived")
    }

    private fun roundTrip() {
        val callback = newId()
        var done = false
        handlers[callback] = { _, _ -> done = true }
        send(DISPLAY, 0) { putInt(callback) }
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!done && System.nanoTime() < deadline) pump(100)
        if (!done) error("the Wayland compositor did not answer")
    }

    /** Reads and dispatches whatever has arrived, waiting up to [timeoutMs] for the first bytes. */
    fun pump(timeoutMs: Int) {
        Memory(8).use { pfd ->
            pfd.setInt(0, socket)
            pfd.setShort(4, POLLIN)
            pfd.setShort(6, 0)
            if (LibC.native.poll(pfd, 1, timeoutMs) <= 0) return
        }
        receive()
        dispatch()
    }

    private fun receive() {
        val data = Memory(4096)
        val control = Memory(CMSG_SPACE)
        val iov = Memory(16).apply { setPointer(0, data); setLong(8, data.size()) }
        val msg = Memory(56).apply {
            clear()
            setPointer(16, iov); setLong(24, 1)
            setPointer(32, control); setLong(40, CMSG_SPACE)
        }
        val n = LibC.native.recvmsg(socket, msg, MSG_CMSG_CLOEXEC)
        if (n <= 0) error("the Wayland connection closed")
        val controlLength = msg.getLong(40)
        var offset = 0L
        while (offset + 16 <= controlLength) {
            val length = control.getLong(offset)
            if (control.getInt(offset + 8) == SOL_SOCKET && control.getInt(offset + 12) == SCM_RIGHTS) {
                for (i in 0 until ((length - 16) / 4)) fds.addLast(control.getInt(offset + 16 + i * 4))
            }
            offset += (length + 7) and 7L.inv()
        }
        pending.compact()
        pending.put(data.getByteArray(0, n.toInt()))
        pending.flip()
    }

    private fun dispatch() {
        while (pending.remaining() >= 8) {
            val start = pending.position()
            val target = pending.getInt(start)
            val word = pending.getInt(start + 4)
            val size = word ushr 16
            if (pending.remaining() < size) return
            val args = pending.slice(start + 8, size - 8).order(ByteOrder.nativeOrder())
            pending.position(start + size)
            if (target == DISPLAY && word and 0xffff == 0) {
                val objectId = args.int
                val code = args.int
                error("Wayland protocol error on object $objectId, code $code: ${string(args)}")
            }
            handlers[target]?.invoke(word and 0xffff, args)
        }
    }

    private fun send(target: Int, opcode: Int, body: ByteBuffer.() -> Unit) {
        val buffer = ByteBuffer.allocate(512).order(ByteOrder.nativeOrder())
        buffer.position(8)
        buffer.body()
        val size = buffer.position()
        buffer.putInt(0, target).putInt(4, (size shl 16) or opcode)
        val bytes = buffer.array().copyOf(size)
        if (LibC.native.write(socket, bytes, size.toLong()) != size.toLong()) error("writing to the Wayland compositor failed")
    }

    private fun newId() = nextId++

    override fun close() {
        LibC.native.close(socket)
        fds.forEach { LibC.native.close(it) }
        fds.clear()
    }

    companion object {
        private const val DISPLAY = 1
        private const val LEASE_DEVICE = "wp_drm_lease_device_v1"
        private const val XKB_V1 = 1
        private const val AF_UNIX = 1
        private const val SOCK_STREAM_CLOEXEC = 1 or 0x80000
        private const val SOL_SOCKET = 1
        private const val SCM_RIGHTS = 1
        private const val MSG_CMSG_CLOEXEC = 0x40000000
        private const val POLLIN: Short = 1
        private const val CMSG_SPACE = 16L + 4 * 28

        fun connect(): Wire {
            val display = System.getenv("WAYLAND_DISPLAY") ?: "wayland-0"
            val path = if (display.startsWith("/")) display
                else File(System.getenv("XDG_RUNTIME_DIR") ?: error("XDG_RUNTIME_DIR is not set"), display).path
            val fd = LibC.native.socket(AF_UNIX, SOCK_STREAM_CLOEXEC, 0)
            if (fd < 0) error("socket() failed")
            val address = Memory(110).apply {
                clear()
                setShort(0, AF_UNIX.toShort())
                write(2, path.toByteArray(), 0, path.length)
            }
            if (LibC.native.connect(fd, address, 110) != 0) {
                LibC.native.close(fd)
                error("could not connect to the Wayland compositor at $path")
            }
            return Wire(fd)
        }

        private fun ByteBuffer.putString(value: String) {
            val bytes = value.toByteArray()
            putInt(bytes.size + 1)
            put(bytes).put(0)
            while (position() % 4 != 0) put(0)
        }

        private fun string(args: ByteBuffer): String {
            val length = args.int
            if (length == 0) return ""
            val bytes = ByteArray(length - 1).also { args.get(it) }
            args.position(args.position() + ((length + 3) and 3.inv()) - (length - 1))
            return String(bytes)
        }
    }
}

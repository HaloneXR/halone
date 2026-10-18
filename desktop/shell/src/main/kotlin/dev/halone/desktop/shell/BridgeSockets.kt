package dev.halone.desktop.shell

import dev.halone.shell.BridgeChannel
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The Halone bridge for pages in a browser the shell does not run in-process.
 *
 * Android hands a page a `MessagePort` whose other end the shell holds. Here
 * the page is given [script] once it loads, which makes the `MessageChannel`
 * the SDK waits for and relays its far port over a WebSocket to this
 * loopback server. Each window gets its own unguessable path, and a socket
 * is only taken from the origin the window was registered with. The first
 * message down the socket is the connect message, posted to the page with
 * the port; everything after is the bridge's own traffic.
 *
 * [onMain] is where channels are opened and receive their messages, the
 * thread the shell runs on.
 */
class BridgeSockets(private val onMain: (Runnable) -> Unit, private val log: (String) -> Unit = {}) : AutoCloseable {
    private class Registration(val windowId: String, val origin: String, val onConnect: (BridgeChannel) -> String)

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val registrations = ConcurrentHashMap<String, Registration>()
    private val random = SecureRandom()
    @Volatile private var running = true

    val port: Int get() = server.localPort

    init {
        Thread({ accept() }, "bridge-sockets").apply { isDaemon = true; start() }
    }

    /**
     * Lets window [windowId], whose pages are on [origin], connect: answers
     * the script to run in its page after each load. [onConnect] is given each
     * new channel on [onMain] and answers the connect message to send first.
     */
    fun register(windowId: String, origin: String, onConnect: (BridgeChannel) -> String): String {
        unregister(windowId)
        val token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        registrations[token] = Registration(windowId, origin, onConnect)
        return script(token)
    }

    fun unregister(windowId: String) {
        registrations.entries.removeIf { it.value.windowId == windowId }
    }

    private fun script(token: String) = """
        (() => {
          if (window.__haloneBridge) return;
          window.__haloneBridge = true;
          const socket = new WebSocket('ws://127.0.0.1:$port/$token');
          const channel = new MessageChannel();
          let connected = false;
          socket.onmessage = (event) => {
            if (connected) { channel.port1.postMessage(event.data); return; }
            connected = true;
            window.postMessage(JSON.parse(event.data), location.origin, [channel.port2]);
          };
          channel.port1.onmessage = (event) => { if (socket.readyState === WebSocket.OPEN) socket.send(event.data); };
          socket.onclose = () => channel.port1.close();
        })();
    """.trimIndent()

    private fun accept() {
        while (running) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (running) log("  bridge sockets: $e")
                return
            }
            Thread({ serve(socket) }, "bridge-socket").apply { isDaemon = true; start() }
        }
    }

    private fun serve(socket: Socket) = socket.use {
        val input = DataInputStream(socket.getInputStream().buffered())
        val output = socket.getOutputStream()
        val request = readHeaders(input) ?: return
        val token = request.path.trim('/')
        val registration = registrations[token]
        val key = request.headers["sec-websocket-key"]
        if (registration == null || key == null || request.headers["origin"] != registration.origin) {
            log("  bridge sockets: refused a socket from ${request.headers["origin"]}${if (registration == null) " with an unknown path" else ""}")
            output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        output.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: ${accept(key)}\r\n\r\n").toByteArray(),
        )
        output.flush()

        val channel = SocketChannel(output, onMain)
        onMain(Runnable { channel.send(registration.onConnect(channel)) })
        try {
            while (true) {
                val message = readMessage(input, output) ?: break
                channel.received(message)
            }
        } catch (_: IOException) {
        } finally {
            channel.closedByPage()
        }
    }

    private class Request(val path: String, val headers: Map<String, String>)

    private fun readHeaders(input: DataInputStream): Request? {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                if (text.isEmpty()) break
                lines += text
                line.clear()
                if (lines.size > MAX_HEADER_LINES) return null
            } else {
                line.append(b.toChar())
                if (line.length > MAX_HEADER_LINE) return null
            }
        }
        val path = lines.firstOrNull()?.split(' ')?.getOrNull(1) ?: return null
        val headers = lines.drop(1).mapNotNull { header ->
            val colon = header.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
            header.substring(0, colon).trim().lowercase() to header.substring(colon + 1).trim()
        }.toMap()
        return Request(path, headers)
    }

    /** The next whole text message, answering pings on the way; null once the page closes. */
    private fun readMessage(input: DataInputStream, output: OutputStream): String? {
        val message = ByteArrayOutputStream()
        while (true) {
            val first = input.readUnsignedByte()
            val second = input.readUnsignedByte()
            val final = first and 0x80 != 0
            val opcode = first and 0x0F
            var length = (second and 0x7F).toLong()
            if (length == 126L) length = input.readUnsignedShort().toLong()
            else if (length == 127L) length = input.readLong()
            if (length < 0 || message.size() + length > MAX_MESSAGE_BYTES) return null
            val mask = ByteArray(4)
            if (second and 0x80 != 0) input.readFully(mask)
            val payload = ByteArray(length.toInt())
            input.readFully(payload)
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (opcode) {
                OP_CLOSE -> return null
                OP_PING -> synchronized(output) { writeFrame(output, OP_PONG, payload) }
                OP_PONG -> Unit
                else -> {
                    message.write(payload)
                    if (final) return message.toString(Charsets.UTF_8)
                }
            }
        }
    }

    private class SocketChannel(private val output: OutputStream, private val onMain: (Runnable) -> Unit) : BridgeChannel {
        private var handler: ((String) -> Unit)? = null
        @Volatile private var open = true

        fun send(message: String) = post(message)

        override fun post(message: String) {
            if (!open) return
            try {
                synchronized(output) { writeFrame(output, OP_TEXT, message.toByteArray(Charsets.UTF_8)) }
            } catch (_: IOException) {
                open = false
            }
        }

        override fun onReceive(handler: (String) -> Unit) {
            this.handler = handler
        }

        fun received(message: String) = onMain(Runnable { if (open) handler?.invoke(message) })

        fun closedByPage() {
            open = false
        }

        override fun close() {
            if (!open) return
            open = false
            runCatching { synchronized(output) { writeFrame(output, OP_CLOSE, ByteArray(0)) } }
        }
    }

    override fun close() {
        running = false
        registrations.clear()
        server.close()
    }

    private companion object {
        const val OP_TEXT = 0x1
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA
        const val MAX_HEADER_LINES = 64
        const val MAX_HEADER_LINE = 8192
        const val MAX_MESSAGE_BYTES = 64L * 1024 * 1024
        const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        fun accept(key: String): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray()))

        fun writeFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
            output.write(0x80 or opcode)
            when {
                payload.size < 126 -> output.write(payload.size)
                payload.size < 65536 -> {
                    output.write(126)
                    output.write(payload.size ushr 8)
                    output.write(payload.size and 0xFF)
                }
                else -> {
                    output.write(127)
                    for (shift in 56 downTo 0 step 8) output.write(((payload.size.toLong() ushr shift) and 0xFF).toInt())
                }
            }
            output.write(payload)
            output.flush()
        }
    }
}

package dev.halone.desktop.shell

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Where `halone dev --desktop` reaches the shell: `POST /dev` on 127.0.0.1
 * with a JSON body `{ "command": ..., ...args }`, answered by [run].
 *
 * What adb's `DUMP` permission is on Android, a token is here: it is written
 * with the port to [FILE] in [AppData], readable by this user alone, and every
 * request carries it as a bearer token. A request with an `Origin` is a
 * browser's, which no page may send, and is refused before the token is read.
 */
class DevServer(
    private val run: (command: String, args: Map<String, Any?>, done: (JSONObject) -> Unit) -> Unit,
    private val onMain: (Runnable) -> Unit,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private val token = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val file = File(AppData.dir, FILE)

    init {
        server.executor = Executors.newSingleThreadExecutor { Thread(it, "dev-server").apply { isDaemon = true } }
        server.createContext("/dev") { exchange -> exchange.use { handle(it) } }
        server.start()
        writePrivately(file, JSONObject().put("port", server.address.port).put("token", token).toString())
        log("  dev: listening on 127.0.0.1:${server.address.port}")
    }

    private fun handle(exchange: HttpExchange) {
        val headers = exchange.requestHeaders
        if (exchange.requestMethod != "POST" || headers.getFirst("Origin") != null ||
            headers.getFirst("Authorization") != "Bearer $token"
        ) {
            return answer(exchange, 403, JSONObject().put("ok", false).put("code", "forbidden").put("message", "not allowed"))
        }
        val body = runCatching { JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8)) }.getOrNull()
            ?: return answer(exchange, 400, JSONObject().put("ok", false).put("code", "invalid-params").put("message", "the body is not JSON"))
        val args = body.keySet().filter { it != "command" }.associateWith { body.opt(it) }
        val result = CompletableFuture<JSONObject>()
        onMain(Runnable { run(body.optString("command"), args) { result.complete(it) } })
        val answer = runCatching { result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrElse {
            JSONObject().put("ok", false).put("code", "timeout").put("message", "the shell did not answer")
        }
        answer(exchange, 200, answer)
    }

    private fun answer(exchange: HttpExchange, status: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    override fun close() {
        server.stop(0)
        file.delete()
    }

    private fun writePrivately(file: File, text: String) {
        file.delete()
        val path = file.toPath()
        runCatching { Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) }
            .onFailure { Files.createFile(path) }
        file.writeText(text)
    }

    companion object {
        /** `{ "port": ..., "token": ... }`, for the CLI. */
        const val FILE = "dev.json"
        private const val TIMEOUT_SECONDS = 30L
    }
}

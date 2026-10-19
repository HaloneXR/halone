package dev.halone.desktop.compositor.linux

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * halone-helper (desktop/native/helper_linux.c), run as root through pkexec, so the
 * desktop's polkit agent asks once: it holds the keyboards for raw input and patches
 * the glasses' EDID. It dies with this process, releasing and reverting what it held.
 */
class LinuxHelper private constructor(private val process: Process) : AutoCloseable {
    private val input = process.outputStream.bufferedWriter()

    /** A raw key event: evdev code, value (0 up, 1 down, 2 repeat), and when the kernel saw it (System.nanoTime's clock). */
    class Key(val code: Int, val value: Int, val atNs: Long, val receivedNs: Long = System.nanoTime())

    val keys = ConcurrentLinkedQueue<Key>()

    @Volatile var ready = false
        private set

    @Volatile var holding = false
        private set

    /** Connectors whose EDID the helper has patched, as "DP-2". */
    val patched: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    var log: (String) -> Unit = ::println

    val alive: Boolean get() = process.isAlive

    init {
        Thread({
            process.inputStream.bufferedReader().forEachLine { line ->
                val words = line.split(' ')
                when (words[0]) {
                    "ready" -> ready = true
                    "key" -> if (words.size == 4) keys += Key(words[1].toInt(), words[2].toInt(), words[3].toLong() * 1000)
                    "released" -> {
                        holding = false
                        log("helper: gave the keyboard back (${words.drop(1).joinToString(" ")})")
                    }
                    "edid" -> {
                        if (words.size == 3) if (words[1] == "applied") patched += words[2] else patched -= words[2]
                        log("helper: $line")
                    }
                    else -> log("helper: $line")
                }
            }
            holding = false
            ready = false
        }, "halone-helper").apply { isDaemon = true; start() }
    }

    private fun send(command: String) = runCatching {
        synchronized(input) {
            input.write(command)
            input.newLine()
            input.flush()
        }
    }.isSuccess

    /** Takes every keyboard from the desktop; [heartbeat] must follow at least every second or it gives them back. */
    fun grab() {
        if (!holding && send("grab")) holding = true
    }

    fun release() {
        if (holding) send("release")
        holding = false
    }

    fun heartbeat() {
        if (holding) send("alive")
    }

    fun patchEdid(on: Boolean) = send(if (on) "edid on" else "edid off")

    override fun close() {
        runCatching { input.close() }
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) process.destroy()
    }

    companion object {
        /** The helper beside this install's launcher (bin/halone-helper), or null where there is none. */
        fun executable(): File? {
            val jar = runCatching { File(LinuxHelper::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull() ?: return null
            return File(jar.parentFile.parentFile, "bin/halone-helper").takeIf { it.canExecute() }
        }

        /**
         * Starts the helper through pkexec, waiting up to [timeoutMs] for the wearer to
         * answer the polkit prompt; null if it was refused or there is no helper.
         */
        fun start(timeoutMs: Long = 60_000, log: (String) -> Unit = ::println): LinuxHelper? {
            val path = executable() ?: run {
                log("helper: not installed beside the launcher")
                return null
            }
            // For testing: halone.helper.sudo=true starts it with sudo -n, halone.helper.only=<prefix> takes only keyboards so named.
            val launcher = if (System.getProperty("halone.helper.sudo") == "true") listOf("sudo", "-n") else listOf("pkexec")
            val only = System.getProperty("halone.helper.only")?.let { listOf("--only=$it") }.orEmpty()
            val process = runCatching {
                ProcessBuilder(launcher + path.absolutePath + only).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            }.getOrElse { error ->
                log("helper: could not run pkexec: ${error.message}")
                return null
            }
            val helper = LinuxHelper(process).also { it.log = log }
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (!helper.ready && process.isAlive && System.nanoTime() < deadline) Thread.sleep(50)
            if (!helper.ready) {
                log("helper: not started (${if (process.isAlive) "no answer" else "pkexec exited ${process.exitValue()}"})")
                process.destroy()
                return null
            }
            return helper
        }
    }
}

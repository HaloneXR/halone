package dev.halone.desktop.compositor.shell

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.ByteByReference
import com.sun.jna.ptr.PointerByReference
import dev.halone.shell.Battery
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.roundToInt

/** DesktopSystem's Linux side: sysfs for the battery, NetworkManager for the network, the freedesktop thumbnail cache. */
internal object LinuxSystem {
    /** The internal battery; plugged in counts as charging, as a laptop held at its charge limit reports "Not charging". */
    fun battery(): Battery? {
        val supplies = File("/sys/class/power_supply").listFiles().orEmpty()
        val battery = supplies
            .filter { read(it, "type") == "Battery" && read(it, "scope") != "Device" }
            .minByOrNull { it.name } ?: return null
        val capacity = read(battery, "capacity")?.toDoubleOrNull() ?: return null
        val plugged = supplies.any { read(it, "type") in setOf("Mains", "USB") && read(it, "scope") != "Device" && read(it, "online") == "1" }
        return Battery((capacity / 100.0).coerceIn(0.0, 1.0), plugged || read(battery, "status") == "Charging")
    }

    /** The route NetworkManager uses, as the Mac reports the system's primary interface; sysfs where it is not running. */
    fun network(): NetworkState = NetworkManager.state() ?: sysfsNetwork()

    private fun sysfsNetwork(): NetworkState {
        val up = File("/sys/class/net").listFiles().orEmpty()
            .filter { it.name != "lo" && read(it, "carrier") == "1" && File(it, "device").exists() && read(it, "type") == "1" }
        return when {
            up.any { File(it, "wireless").exists() } -> NetworkState(NetworkState.Kind.WIFI, 4)
            up.isNotEmpty() -> NetworkState(NetworkState.Kind.WIRED)
            else -> NetworkState(NetworkState.Kind.OFFLINE)
        }
    }

    private fun read(dir: File, name: String) = runCatching { File(dir, name).readText().trim() }.getOrNull()

    /** NetworkManager over sd-bus: the primary connection's type, and the strength of a Wi-Fi device's access point. */
    private object NetworkManager {
        private const val SERVICE = "org.freedesktop.NetworkManager"
        private const val PATH = "/org/freedesktop/NetworkManager"

        @Suppress("FunctionName")
        private interface SdBus : Library {
            fun sd_bus_open_system(bus: PointerByReference): Int
            fun sd_bus_get_property(bus: Pointer, destination: String, path: String, iface: String, member: String,
                                    error: Pointer?, reply: PointerByReference, type: String): Int
            fun sd_bus_message_read(message: Pointer, types: String, vararg out: Any): Int
            fun sd_bus_message_enter_container(message: Pointer, type: Byte, contents: String): Int
            fun sd_bus_message_unref(message: Pointer): Pointer?
            fun sd_bus_flush_close_unref(bus: Pointer): Pointer?
        }

        private val sd by lazy { runCatching { Native.load("systemd", SdBus::class.java) }.getOrNull() }

        fun state(): NetworkState? {
            val sd = sd ?: return null
            val pBus = PointerByReference()
            if (sd.sd_bus_open_system(pBus) < 0) return null
            val bus = pBus.value
            try {
                fun <T> property(path: String, iface: String, member: String, type: String, read: (Pointer) -> T?): T? {
                    val reply = PointerByReference()
                    if (sd.sd_bus_get_property(bus, SERVICE, path, iface, member, null, reply, type) < 0) return null
                    return try { read(reply.value) } finally { sd.sd_bus_message_unref(reply.value) }
                }
                fun objectPath(m: Pointer): String? = PointerByReference().let { if (sd.sd_bus_message_read(m, "o", it) > 0) it.value.getString(0) else null }
                fun string(m: Pointer): String? = PointerByReference().let { if (sd.sd_bus_message_read(m, "s", it) > 0) it.value.getString(0) else null }
                fun objectPaths(m: Pointer): List<String> {
                    if (sd.sd_bus_message_enter_container(m, 'a'.code.toByte(), "o") < 0) return emptyList()
                    return generateSequence { objectPath(m) }.toList()
                }
                val active = "$SERVICE.Connection.Active"

                val primary = property(PATH, SERVICE, "PrimaryConnection", "o", ::objectPath) ?: return null
                if (primary == "/") return NetworkState(NetworkState.Kind.OFFLINE)
                // A VPN is the primary route without being a link; the link under it is an active wired or wireless one.
                val connections = listOf(primary) + (property(PATH, SERVICE, "ActiveConnections", "ao", ::objectPaths).orEmpty() - primary)
                for (connection in connections) {
                    when (property(connection, active, "Type", "s", ::string)) {
                        "802-11-wireless" -> {
                            val device = property(connection, active, "Devices", "ao", ::objectPaths)?.firstOrNull()
                            val ap = device?.let { property(it, "$SERVICE.Device.Wireless", "ActiveAccessPoint", "o", ::objectPath) }
                            val strength = ap?.takeIf { it != "/" }?.let {
                                property(it, "$SERVICE.AccessPoint", "Strength", "y") { m ->
                                    ByteByReference().let { b -> if (sd.sd_bus_message_read(m, "y", b) > 0) b.value.toInt() and 0xFF else null }
                                }
                            }
                            return NetworkState(NetworkState.Kind.WIFI, strength?.let(::bars) ?: 4)
                        }
                        "802-3-ethernet", "bridge", "bond", "vlan" -> return NetworkState(NetworkState.Kind.WIRED)
                    }
                }
                return NetworkState(NetworkState.Kind.WIRED)
            } finally {
                sd.sd_bus_flush_close_unref(bus)
            }
        }

        /** nmcli's own steps from signal strength (0–100) to bars. */
        private fun bars(strength: Int) = when {
            strength > 80 -> 4
            strength > 55 -> 3
            strength > 30 -> 2
            strength > 5 -> 1
            else -> 0
        }
    }
}

/**
 * Thumbnails as the Linux desktop makes them: the freedesktop cache that file
 * managers fill, then the installed `.thumbnailer` entries, then ffmpeg for a
 * video's frame or a song's embedded cover. Null when none of them has one.
 */
internal object LinuxThumbnails {
    private val cache = File(System.getenv("XDG_CACHE_HOME") ?: "${System.getProperty("user.home")}/.cache", "thumbnails")
    private val flavours = listOf("normal" to 128, "large" to 256, "x-large" to 512, "xx-large" to 1024)
    private val videos = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "wmv", "flv", "3gp", "ts", "mpg", "mpeg", "ogv")
    private val songs = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "alac", "aiff")

    fun thumbnail(file: File, sizePx: Int, quality: Float): ByteArray? {
        val image = cached(file, sizePx) ?: fromThumbnailer(file, sizePx)
        if (image != null) return jpeg(fit(image, sizePx), quality)
        return fromFfmpeg(file, sizePx, quality)
    }

    /** The freedesktop key: the file:// URI, escaped as GLib and KIO escape it. */
    fun uri(file: File): String {
        val safe = "-._~!$&'()*+,;=:@/"
        val path = file.absoluteFile.toPath().normalize().toString().toByteArray()
        return "file://" + path.joinToString("") { b ->
            val c = (b.toInt() and 0xFF).toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c in safe) c.toString() else "%%%02X".format(b.toInt() and 0xFF)
        }
    }

    private fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** The smallest cached flavour at least [sizePx], or the largest smaller one, if it is not older than the file. */
    private fun cached(file: File, sizePx: Int): BufferedImage? {
        val name = md5(uri(file)) + ".png"
        val mtime = file.lastModified() / 1000
        val valid = flavours.mapNotNull { (dir, px) ->
            val png = File(File(cache, dir), name)
            if (!png.isFile || pngText(png)["Thumb::MTime"]?.toLongOrNull() != mtime) null else png to px
        }
        val chosen = valid.firstOrNull { it.second >= sizePx } ?: valid.lastOrNull() ?: return null
        return runCatching { ImageIO.read(chosen.first) }.getOrNull()
    }

    private fun pngText(png: File): Map<String, String> = runCatching {
        val bytes = png.readBytes()
        val text = HashMap<String, String>()
        var i = 8
        while (i + 8 <= bytes.size) {
            val length = ((bytes[i].toInt() and 0xFF) shl 24) or ((bytes[i + 1].toInt() and 0xFF) shl 16) or
                ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            val type = String(bytes, i + 4, 4, Charsets.ISO_8859_1)
            if (type == "tEXt") {
                val body = String(bytes, i + 8, length, Charsets.ISO_8859_1)
                text[body.substringBefore('\u0000')] = body.substringAfter('\u0000')
            }
            if (type == "IDAT" || type == "IEND") break
            i += 12 + length
        }
        text
    }.getOrDefault(emptyMap())

    /** Runs the `.thumbnailer` entry for the file's MIME type, as GNOME's and tumbler's thumbnailers are installed. */
    private fun fromThumbnailer(file: File, sizePx: Int): BufferedImage? {
        val mime = runCatching { Files.probeContentType(file.toPath()) }.getOrNull() ?: return null
        val dirs = listOf("${System.getProperty("user.home")}/.local/share/thumbnailers") +
            (System.getenv("XDG_DATA_DIRS") ?: "/usr/local/share:/usr/share").split(':').map { "$it/thumbnailers" }
        val exec = dirs.asSequence().flatMap { File(it).listFiles().orEmpty().asSequence() }
            .filter { it.name.endsWith(".thumbnailer") }
            .mapNotNull { entry ->
                val lines = runCatching { entry.readLines() }.getOrNull() ?: return@mapNotNull null
                val types = lines.firstOrNull { it.startsWith("MimeType=") }?.substringAfter('=')?.split(';').orEmpty()
                lines.firstOrNull { it.startsWith("Exec=") }?.substringAfter('=')?.takeIf { mime in types }
            }.firstOrNull() ?: return null
        val out = Files.createTempFile("halone-thumb", ".png").toFile()
        return try {
            val args = exec.split(' ').filter { it.isNotEmpty() }.map {
                it.replace("%i", file.absolutePath).replace("%u", uri(file)).replace("%o", out.path).replace("%s", sizePx.toString())
            }
            if (!run(args)) null else runCatching { ImageIO.read(out) }.getOrNull()
        } finally {
            out.delete()
        }
    }

    /** A representative frame of a video, or the cover embedded in a song; ffmpeg writes the JPEG itself. */
    private fun fromFfmpeg(file: File, sizePx: Int, quality: Float): ByteArray? {
        val extension = file.extension.lowercase()
        val video = extension in videos
        if (!video && extension !in songs) return null
        val scale = "scale=w=$sizePx:h=$sizePx:force_original_aspect_ratio=decrease"
        val q = (2 + (1 - quality.coerceIn(0f, 1f)) * 29).roundToInt().toString()
        val args = listOf("ffmpeg", "-nostdin", "-loglevel", "error", "-i", file.absolutePath) +
            (if (video) listOf("-vf", "thumbnail,$scale") else listOf("-map", "0:v:0", "-vf", scale)) +
            listOf("-frames:v", "1", "-f", "image2pipe", "-c:v", "mjpeg", "-q:v", q, "-")
        val out = Files.createTempFile("halone-thumb", ".jpg").toFile()
        return try {
            if (!run(args.dropLast(1) + listOf("-y", out.path))) null else out.readBytes().takeIf { it.isNotEmpty() }
        } finally {
            out.delete()
        }
    }

    private fun run(args: List<String>): Boolean = runCatching {
        val process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            false
        } else process.exitValue() == 0
    }.getOrDefault(false)

    /** Scaled down to fit [sizePx], never up, onto white where it was transparent. */
    private fun fit(image: BufferedImage, sizePx: Int): BufferedImage {
        val scale = minOf(1.0, sizePx.toDouble() / maxOf(image.width, image.height))
        val w = maxOf(1, (image.width * scale).roundToInt())
        val h = maxOf(1, (image.height * scale).roundToInt())
        return BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { out ->
            val g = out.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.color = Color.WHITE
            g.fillRect(0, 0, w, h)
            g.drawImage(image, 0, 0, w, h, null)
            g.dispose()
        }
    }

    private fun jpeg(image: BufferedImage, quality: Float): ByteArray? {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull() ?: return null
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val params = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality.coerceIn(0f, 1f)
            }
            writer.write(null, IIOImage(image, null, null), params)
            writer.dispose()
        }
        return out.toByteArray()
    }

    private const val TIMEOUT_S = 10L
}

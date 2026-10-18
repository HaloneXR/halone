package dev.halone.desktop.shell

import dev.halone.shell.FileRoot
import dev.halone.shell.FileTypes
import dev.halone.shell.StorageAccess
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max

/** The user's folders as `halone.files` roots, the ones the phone has, where this OS keeps them. */
object DesktopFolders {
    fun roots(): List<FileRoot> {
        val home = File(System.getProperty("user.home"))
        val xdg = xdgUserDirs(home)
        fun folder(xdgKey: String, vararg names: String): String =
            xdg[xdgKey] ?: names.map { File(home, it) }.firstOrNull { it.isDirectory }?.path ?: File(home, names.first()).path
        return listOf(
            FileRoot("storage", "Home", home.path),
            FileRoot("pictures", "Pictures", folder("XDG_PICTURES_DIR", "Pictures")),
            FileRoot("movies", "Movies", folder("XDG_VIDEOS_DIR", "Movies", "Videos")),
            FileRoot("music", "Music", folder("XDG_MUSIC_DIR", "Music")),
            FileRoot("downloads", "Downloads", folder("XDG_DOWNLOAD_DIR", "Downloads")),
            FileRoot("documents", "Documents", folder("XDG_DOCUMENTS_DIR", "Documents")),
        )
    }

    /** `~/.config/user-dirs.dirs`, where Linux desktops keep the user's folders, possibly localised. */
    private fun xdgUserDirs(home: File): Map<String, String> {
        val file = File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotEmpty() } ?: "${home.path}/.config", "user-dirs.dirs")
        if (!file.isFile) return emptyMap()
        return file.readLines().mapNotNull { line ->
            val match = Regex("""^\s*(XDG_\w+_DIR)\s*=\s*"(.*)"\s*$""").find(line) ?: return@mapNotNull null
            match.groupValues[1] to match.groupValues[2].replace("\$HOME", home.path)
        }.toMap()
    }
}

/** No permission to ask for: the shell reads what its user can. */
object DesktopStorageAccess : StorageAccess {
    override val granted = true
    override fun request() = Unit
}

/**
 * Small JPEGs of pictures, videos and songs for the Files grid, cached in
 * [AppData] by path, size and modification time, as on Android. [system] is
 * the OS's own thumbnailer where there is one, as ThumbnailUtils is on
 * Android: pictures turned upright, video frames, cover art. Without it,
 * pictures are decoded here and the rest have none. Any thread.
 */
class DesktopThumbnails(private val system: ((File, Int, Float) -> ByteArray?)? = null) {
    private val cache = File(AppData.dir, "cache/thumbnails").apply { mkdirs() }

    fun of(file: File): ByteArray? {
        val type = FileTypes.mimeTypeOf(file.name).orEmpty()
        val cached = File(cache, key(file) + ".jpg")
        if (cached.isFile) return cached.readBytes()
        val bytes = when {
            system != null -> system.invoke(file, if (type.startsWith("audio/")) COVER_PX else SIZE_PX, QUALITY)
            type.startsWith("image/") -> decode(file)
            else -> null
        } ?: return null
        val partial = File(cache, cached.name + ".part")
        partial.writeBytes(bytes)
        java.nio.file.Files.move(partial.toPath(), cached.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return bytes
    }

    private fun decode(file: File): ByteArray? {
        val source = runCatching { ImageIO.read(file) }.getOrNull() ?: return null
        val scale = SIZE_PX.toDouble() / max(source.width, source.height)
        val image = if (scale >= 1.0) toRgb(source, source.width, source.height) else toRgb(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1))
        return ByteArrayOutputStream().use { out ->
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = QUALITY
            }
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(image, null, null), param)
            }
            writer.dispose()
            out.toByteArray()
        }
    }

    private fun toRgb(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(source, 0, 0, width, height, null)
        g.dispose()
        return image
    }

    private fun key(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("${file.path}|${file.length()}|${file.lastModified()}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SIZE_PX = 320
        const val COVER_PX = 640
        const val QUALITY = 0.82f
    }
}

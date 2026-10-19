package dev.halone.app.shell

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import dev.halone.shell.DownloadNames
import dev.halone.shell.Downloads
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse

/**
 * Writes what Gecko hands over as a download into the phone's Downloads
 * folder, through MediaStore so it needs no permission and the gallery and
 * other apps see it. Progress goes to [downloads] on the UI thread, a few
 * times a second.
 */
class Downloader(
    private val context: Context,
    private val downloads: Downloads,
    private val log: (String) -> Unit,
    /** Told of a download starting, finishing or failing, in words for the wearer. UI thread. */
    private val announce: (String) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(MAX_PARALLEL)

    /** Saves a response Gecko would not show. UI thread. */
    fun save(response: WebResponse) {
        val headers = response.headers.mapKeys { it.key.lowercase() }
        val mimeType = headers["content-type"]?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
        val name = sanitise(URLUtil.guessFileName(response.uri, headers["content-disposition"], mimeType))
        val total = headers["content-length"]?.toLongOrNull()
        val body = response.body
        if (body == null) {
            val id = downloads.start(response.uri, name, mimeType, total) {}
            downloads.fail(id, "the server sent nothing")
            return
        }
        val stopped = AtomicBoolean(false)
        val id = downloads.start(response.uri, name, mimeType, total) {
            stopped.set(true)
            workers.execute { runCatching { body.close() } }
        }
        announce("Downloading $name")
        workers.execute { write(id, name, mimeType ?: guessType(name), body, stopped) }
    }

    /** Fetches [url] with the browser's cookies, or a private tab's, and saves it. UI thread. */
    fun fetch(runtime: GeckoRuntime, url: String, referrer: String?, private: Boolean) {
        val request = WebRequest.Builder(url).apply { referrer?.let(::referrer) }.build()
        val flags = if (private) GeckoWebExecutor.FETCH_FLAGS_PRIVATE else GeckoWebExecutor.FETCH_FLAGS_NONE
        GeckoWebExecutor(runtime).fetch(request, flags).accept(
            { response -> main.post { if (response != null) save(response) } },
            { error ->
                main.post {
                    val id = downloads.start(url, sanitise(URLUtil.guessFileName(url, null, null)), null, null) {}
                    downloads.fail(id, error?.message ?: "could not fetch $url")
                    announce("Download failed")
                }
            },
        )
    }

    private fun write(id: Int, name: String, mimeType: String?, body: java.io.InputStream, stopped: AtomicBoolean) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            mimeType?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull()
        if (uri == null) {
            main.post { downloads.fail(id, "could not create the file") }
            return
        }
        try {
            var received = 0L
            var reportedAt = 0L
            resolver.openOutputStream(uri)!!.use { output ->
                body.use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (!stopped.get()) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        val now = SystemClock.uptimeMillis()
                        if (now - reportedAt >= PROGRESS_MS) {
                            reportedAt = now
                            val sofar = received
                            main.post { downloads.progress(id, sofar) }
                        }
                    }
                }
            }
            if (stopped.get()) {
                resolver.delete(uri, null, null)
                return
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            val (path, finalName) = locate(uri) ?: (null to name)
            val total = received
            main.post {
                downloads.progress(id, total)
                downloads.finish(id, path ?: "", finalName)
                announce("Downloaded $finalName")
            }
            log("  downloads: saved $finalName ($total bytes) to $path")
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            if (stopped.get()) return
            log("  downloads: $name failed: $error")
            main.post {
                downloads.fail(id, error.message ?: error.toString())
                announce("Download failed: $name")
            }
        }
    }

    /** Where MediaStore put the file, and what it called it, which differs when the name was taken. */
    @Suppress("DEPRECATION")
    private fun locate(uri: android.net.Uri): Pair<String?, String>? {
        val columns = arrayOf(MediaStore.Downloads.DATA, MediaStore.Downloads.DISPLAY_NAME)
        return context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0) to cursor.getString(1)
        }
    }

    fun close() = workers.shutdownNow()

    private fun guessType(name: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

    private fun sanitise(name: String) = DownloadNames.sanitise(name)

    private companion object {
        const val MAX_PARALLEL = 3
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_MS = 250L
    }
}

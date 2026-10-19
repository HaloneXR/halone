package dev.halone.desktop.shell

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * The browser's ad blocker: uBlock Origin Lite, which Chromium can still run
 * now that it has dropped the Manifest V2 that uBlock Origin itself needs. As
 * Android installs uBlock Origin from addons.mozilla.org, this installs the
 * latest Chromium build from its GitHub releases into [AppData], one folder
 * per version. The producer loads it at start; a newer one is fetched for the
 * next start.
 */
class AdBlocker(private val log: (String) -> Unit = {}) {
    private val root = File(AppData.dir, "extensions/ublock-origin-lite")
    private val checked = File(root, "checked")

    /** The newest version installed, without the half-unpacked. */
    fun installed(): File? = root.listFiles { file -> file.isDirectory && !file.name.endsWith(PARTIAL) }
        ?.maxWithOrNull(compareBy({ it.name.length }, { it.name }))

    /**
     * The folder the producer loads: what is installed, or with nothing yet,
     * the latest release fetched now, when it comes within [timeoutMs]. Older
     * versions go, as nothing loads them any more.
     */
    fun prepare(timeoutMs: Long): File? {
        val current = installed() ?: run {
            val fetch = Thread { runCatching { install() }.onFailure { log("  ad blocker: could not install: $it") } }
            fetch.isDaemon = true
            fetch.start()
            fetch.join(timeoutMs)
            installed()
        } ?: return null
        root.listFiles { file -> file.isDirectory && file != current }?.forEach { it.deleteRecursively() }
        return current
    }

    /** Installs a newer release for the next start, checking at most once a day. Any thread but the shell's. */
    fun update() {
        val last = runCatching { checked.readText().trim().toLong() }.getOrDefault(0L)
        if (System.currentTimeMillis() - last < CHECK_MS && installed() != null) return
        runCatching { install() }.onFailure { log("  ad blocker: could not check for an update: $it") }
    }

    private fun install() {
        val release = JSONObject(get(LATEST_RELEASE).toString(Charsets.UTF_8))
        val version = release.getString("tag_name").replace(Regex("[^0-9A-Za-z._-]"), "_")
        root.mkdirs()
        checked.writeText(System.currentTimeMillis().toString())
        if (File(root, version).isDirectory) return
        val assets = release.getJSONArray("assets")
        val zip = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".chromium.zip") }
            ?.getString("browser_download_url") ?: error("release $version has no Chromium build")
        val partial = File(root, version + PARTIAL).apply { deleteRecursively(); mkdirs() }
        unzip(get(zip), partial)
        check(File(partial, "manifest.json").isFile) { "release $version has no manifest" }
        check(partial.renameTo(File(root, version))) { "could not install $version" }
        log("  ad blocker: installed uBlock Origin Lite $version")
    }

    private fun unzip(bytes: ByteArray, into: File) {
        val base = into.canonicalPath + File.separator
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(into, entry.name)
                check(target.canonicalPath.startsWith(base)) { "an entry outside the extension: ${entry.name}" }
                if (entry.isDirectory) target.mkdirs() else target.apply { parentFile.mkdirs() }.outputStream().use { zip.copyTo(it) }
            }
        }
    }

    private fun get(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        return try {
            check(connection.responseCode == 200) { "$url answered ${connection.responseCode}" }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val LATEST_RELEASE = "https://api.github.com/repos/uBlockOrigin/uBOL-home/releases/latest"
        const val PARTIAL = ".partial"
        const val CHECK_MS = 24L * 60 * 60 * 1000
        const val TIMEOUT_MS = 15_000
    }
}

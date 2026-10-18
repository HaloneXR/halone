package dev.halone.shell

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * What `@halone/cli` asks of the shell, however it reaches it: a broadcast
 * over adb on Android, a loopback request on the desktop. Each command
 * answers a JSON object with `ok`. Nothing runs unless developer mode is on.
 *
 * - `install` with `path` (and `launch`): a `.halone` package on this machine.
 * - `dev` with `url`: the app a dev server on localhost serves.
 * - `uninstall` or `launch` with `id` (and `route`).
 * - `list`, `plugins`, and `plugin` with `package` and `enabled`.
 *
 * [run] is called on the shell's thread; slow work goes to [background] and
 * comes back through [onMain].
 */
class DevCommands(
    private val shell: Shell<*>,
    private val version: String,
    /** Where installed packages say they came from, such as `adb`. */
    private val source: String,
    /** How a dev server is reached from here, for when it is not. */
    private val devServerHint: String,
    private val background: (Runnable) -> Unit,
    private val onMain: (Runnable) -> Unit,
    private val log: (String) -> Unit = {},
) {
    fun run(command: String, args: Map<String, Any?>, done: (JSONObject) -> Unit) {
        fun finish(result: Result<JSONObject>) {
            val answer = result.fold(
                { it.put("ok", true) },
                { error ->
                    JSONObject().put("ok", false)
                        .put("code", (error as? HaloneException)?.code ?: "internal")
                        .put("message", error.message ?: error.toString())
                },
            )
            log("  dev: $command -> $answer")
            done(answer)
        }
        if (!shell.settings.boolean(ShellSettings.DEVELOPER_ENABLED)) {
            return finish(Result.failure(HaloneException("developer-mode-off", "turn on developer mode in Settings → Developer")))
        }
        when (command) {
            "install" -> background(Runnable {
                val read = runCatching { HalonePackage.read(File(args["path"] as? String ?: ""), shell.reservedIds, version) }
                onMain(Runnable {
                    finish(read.mapCatching { pkg ->
                        val app = shell.installPackage(pkg, source)
                        if (args["launch"] == true) shell.launch(app.id)
                        JSONObject().put("id", app.id).put("version", app.manifest.version)
                    })
                })
            })
            "dev" -> {
                val url = args["url"] as? String ?: ""
                background(Runnable {
                    val manifest = runCatching { PackageManifest.parse(fetch(manifestUrl(url)), shell.reservedIds, version) }
                    onMain(Runnable {
                        finish(manifest.mapCatching {
                            val app = shell.installDevApp(it, url)
                            shell.launch(app.id)
                            JSONObject().put("id", app.id)
                        })
                    })
                })
            }
            "uninstall" -> finish(runCatching { shell.uninstall(id(args)); JSONObject() })
            "launch" -> finish(runCatching { JSONObject().put("windowId", shell.launch(id(args), args["route"] as? String).id) })
            "list" -> finish(runCatching {
                JSONObject().put("apps", JSONArray(shell.packageApps?.list().orEmpty().map { it.toJson() }))
            })
            "plugins" -> finish(runCatching {
                JSONObject().put("plugins", JSONArray(shell.nativeApps?.plugins().orEmpty().map { info ->
                    info.toJson().put("appIds", JSONArray(info.apps.map { it.id }))
                }))
            })
            "plugin" -> finish(runCatching {
                val packageName = args["package"] as? String ?: throw HaloneException("invalid-params", "package is missing")
                shell.setPluginEnabled(packageName, args["enabled"] != false)
                JSONObject()
            })
            else -> finish(Result.failure(HaloneException("unknown-command", "no such command: $command")))
        }
    }

    private fun id(args: Map<String, Any?>) = args["id"] as? String ?: throw HaloneException("invalid-params", "id is missing")

    /** `halone.json` sits at the dev server's root, beside the entry page. */
    private fun manifestUrl(url: String): String {
        val parsed = runCatching { URL(url) }.getOrElse { throw HaloneException("invalid-url", "not a URL: $url") }
        if (parsed.protocol != "http" || parsed.host !in LOCAL_HOSTS) {
            throw HaloneException("invalid-url", "a dev app loads from localhost $devServerHint: $url")
        }
        return URL(parsed, "/${PackageManifest.FILE}").toString()
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        return try {
            if (connection.responseCode != 200) throw HaloneException("no-manifest", "$url answered ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (error: java.io.IOException) {
            throw HaloneException("no-dev-server", "cannot reach $url; is `halone dev` running $devServerHint? ($error)")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        val LOCAL_HOSTS = setOf("localhost", "127.0.0.1")
        const val TIMEOUT_MS = 5_000
    }
}

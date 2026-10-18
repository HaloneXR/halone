package dev.halone.app.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import dev.halone.shell.HaloneException
import dev.halone.shell.HalonePackage
import dev.halone.shell.PackageManifest
import dev.halone.shell.Shell
import dev.halone.shell.ShellSettings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject

/**
 * What `@halone/cli` asks over adb, as an ordered broadcast whose result data
 * is the answer, a JSON object with `ok`:
 *
 * ```
 * am broadcast -a dev.halone.DEV --es command install --es path <file> [--ez launch true]
 * am broadcast -a dev.halone.DEV --es command dev --es url http://localhost:5180/index.html
 * am broadcast -a dev.halone.DEV --es command uninstall|launch --es id <app id> [--es route <route>]
 * am broadcast -a dev.halone.DEV --es command list
 * am broadcast -a dev.halone.DEV --es command plugins
 * am broadcast -a dev.halone.DEV --es command plugin --es package <name> --ez enabled true|false
 * ```
 *
 * Only a sender holding `DUMP` reaches it, which adb's shell does and apps do
 * not, and it does nothing unless developer mode is on.
 */
class DevReceiver(
    private val context: Context,
    private val shell: Shell<*>,
    private val background: Executor,
    private val log: (String) -> Unit,
) : BroadcastReceiver() {
    private val main = Handler(Looper.getMainLooper())

    fun register() {
        context.registerReceiver(this, IntentFilter(ACTION), android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(this) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val command = intent.getStringExtra("command").orEmpty()
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
            pending.resultCode = if (result.isSuccess) 0 else 1
            pending.resultData = answer.toString()
            pending.finish()
        }
        if (!shell.settings.boolean(ShellSettings.DEVELOPER_ENABLED)) {
            return finish(Result.failure(HaloneException("developer-mode-off", "turn on developer mode in Settings → Developer")))
        }
        when (command) {
            "install" -> background.execute {
                val read = runCatching { HalonePackage.read(File(intent.getStringExtra("path").orEmpty()), shell.reservedIds, dev.halone.app.BuildConfig.VERSION_NAME) }
                main.post {
                    finish(read.mapCatching { pkg ->
                        val app = shell.installPackage(pkg, "adb")
                        if (intent.getBooleanExtra("launch", false)) shell.launch(app.id)
                        JSONObject().put("id", app.id).put("version", app.manifest.version)
                    })
                }
            }
            "dev" -> {
                val url = intent.getStringExtra("url").orEmpty()
                background.execute {
                    val manifest = runCatching { PackageManifest.parse(fetch(manifestUrl(url)), shell.reservedIds, dev.halone.app.BuildConfig.VERSION_NAME) }
                    main.post {
                        finish(manifest.mapCatching {
                            val app = shell.installDevApp(it, url)
                            shell.launch(app.id)
                            JSONObject().put("id", app.id)
                        })
                    }
                }
            }
            "uninstall" -> finish(runCatching { shell.uninstall(id(intent)); JSONObject() })
            "launch" -> finish(runCatching { JSONObject().put("windowId", shell.launch(id(intent), intent.getStringExtra("route")).id) })
            "list" -> finish(runCatching {
                JSONObject().put("apps", JSONArray(shell.packageApps?.list().orEmpty().map { it.toJson() }))
            })
            "plugins" -> finish(runCatching {
                JSONObject().put("plugins", JSONArray(shell.nativeApps?.plugins().orEmpty().map { info ->
                    info.toJson().put("appIds", JSONArray(info.apps.map { it.id }))
                }))
            })
            "plugin" -> finish(runCatching {
                val packageName = intent.getStringExtra("package") ?: throw HaloneException("invalid-params", "package is missing")
                shell.setPluginEnabled(packageName, intent.getBooleanExtra("enabled", true))
                JSONObject()
            })
            else -> finish(Result.failure(HaloneException("unknown-command", "no such command: $command")))
        }
    }

    private fun id(intent: Intent) = intent.getStringExtra("id") ?: throw HaloneException("invalid-params", "id is missing")

    /** `halone.json` sits at the dev server's root, beside the entry page. */
    private fun manifestUrl(url: String): String {
        val parsed = URL(url)
        if (parsed.protocol != "http" || parsed.host !in LOCAL_HOSTS) {
            throw HaloneException("invalid-url", "a dev app loads from localhost over adb reverse: $url")
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
            throw HaloneException("no-dev-server", "cannot reach $url; is `halone dev` running and adb reverse set? ($error)")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val ACTION = "dev.halone.DEV"
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1")
        private const val TIMEOUT_MS = 5_000
    }
}

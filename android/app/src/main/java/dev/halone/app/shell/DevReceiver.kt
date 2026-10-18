package dev.halone.app.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import dev.halone.shell.DevCommands
import dev.halone.shell.Shell
import java.util.concurrent.Executor

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

    private val commands = DevCommands(
        shell,
        dev.halone.app.BuildConfig.VERSION_NAME,
        source = "adb",
        devServerHint = "over adb reverse",
        background = background::execute,
        onMain = { main.post(it) },
        log = log,
    )

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val args = intent.extras?.let { extras -> extras.keySet().associateWith { @Suppress("DEPRECATION") extras.get(it) } }.orEmpty()
        commands.run(intent.getStringExtra("command").orEmpty(), args) { answer ->
            pending.resultCode = if (answer.optBoolean("ok")) 0 else 1
            pending.resultData = answer.toString()
            pending.finish()
        }
    }

    companion object {
        const val ACTION = "dev.halone.DEV"
    }
}

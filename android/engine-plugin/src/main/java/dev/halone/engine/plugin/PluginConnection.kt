package dev.halone.engine.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import dev.halone.plugin.IPanelProvider
import dev.halone.plugin.IProviderHost
import dev.halone.shell.PluginPackage
import dev.halone.shell.ProvidedApp
import dev.halone.shell.ProvidedStatusItem
import java.util.concurrent.Executor

/**
 * The shell's binding to one enabled plugin, kept for as long as it stays
 * enabled. Binder calls that return something run on [io], never on the main
 * thread, so a plugin that hangs cannot freeze the glasses; everything this
 * class tells its listeners arrives on the main thread.
 *
 * When the plugin's process dies, Android restarts it and connects again, and
 * the apps are listed afresh; open panels hear [addStopListener] and do not
 * come back by themselves.
 */
class PluginConnection(
    private val context: Context,
    val plugin: PluginPackage,
    private val io: Executor,
    private val log: (String) -> Unit,
) {
    interface Listener {
        fun onApps(plugin: PluginPackage, apps: List<ProvidedApp>)
        fun onOpenRequested(plugin: PluginPackage, appId: String, args: Bundle)

        /** Focus the app's window, or open one with [args]. */
        fun onLaunchRequested(plugin: PluginPackage, appId: String, args: Bundle)

        /** [item] is null when the plugin removed it. */
        fun onStatusItem(plugin: PluginPackage, id: String, item: ProvidedStatusItem?)

        /** The plugin's process went, or it was unbound: what it showed goes with it. */
        fun onStopped(plugin: PluginPackage)
    }

    var listener: Listener? = null

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var provider: IPanelProvider? = null
    private var bound = false
    private val waiting = ArrayList<(IPanelProvider) -> Unit>()
    private val stopListeners = LinkedHashSet<() -> Unit>()

    private val host = object : IProviderHost.Stub() {
        override fun appsChanged() {
            main.post { refreshApps() }
        }

        override fun openPanel(appId: String?, args: Bundle?) {
            appId ?: return
            main.post { if (bound) listener?.onOpenRequested(plugin, appId, args ?: Bundle()) }
        }

        override fun launchApp(appId: String?, args: Bundle?) {
            appId ?: return
            main.post { if (bound) listener?.onLaunchRequested(plugin, appId, args ?: Bundle()) }
        }

        override fun setStatusItem(id: String?, item: Bundle?) {
            val parsed = id?.let { item?.let { bundle -> providedStatusItemOf(id, bundle) } }
            if (parsed == null) {
                log("  plugins: ${plugin.packageName} set an unusable status item '$id'")
                return
            }
            main.post { if (bound) listener?.onStatusItem(plugin, parsed.id, parsed) }
        }

        override fun removeStatusItem(id: String?) {
            id ?: return
            main.post { if (bound) listener?.onStatusItem(plugin, id.trim(), null) }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connected = IPanelProvider.Stub.asInterface(binder)
            io.execute {
                val ready = runCatching {
                    val version = connected.contractVersion()
                    check(version >= 1) { "contract version $version" }
                    connected.setHost(host)
                }
                main.post {
                    if (!bound) return@post
                    ready.onFailure {
                        log("  plugins: ${plugin.packageName} refused: $it")
                        return@post
                    }
                    log("  plugins: ${plugin.packageName} connected")
                    provider = connected
                    val queued = waiting.toList()
                    waiting.clear()
                    queued.forEach { block -> io.execute { block(connected) } }
                    refreshApps()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            log("  plugins: ${plugin.packageName} disconnected")
            stopped()
        }

        override fun onBindingDied(name: ComponentName) {
            log("  plugins: ${plugin.packageName} binding died; binding again")
            stopped()
            context.unbindService(this)
            bound = false
            bind()
        }

        override fun onNullBinding(name: ComponentName) {
            log("  plugins: ${plugin.packageName} returned no binder")
        }
    }

    fun bind() {
        if (bound) return
        val intent = Intent().setComponent(ComponentName(plugin.packageName, plugin.serviceClass))
        bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT) }
            .onFailure { log("  plugins: binding ${plugin.packageName} failed: $it") }
            .getOrDefault(false)
    }

    fun unbind() {
        if (!bound) return
        bound = false
        stopped()
        waiting.clear()
        runCatching { context.unbindService(connection) }
    }

    /** Runs [block] on the binder thread with the plugin once it is connected. Main thread. */
    fun whenConnected(block: (IPanelProvider) -> Unit) {
        val current = provider
        if (current != null) io.execute { block(current) } else waiting += block
    }

    /** The plugin, waiting up to [timeoutMs] for it to connect; for threads that may block. */
    fun awaitProvider(timeoutMs: Long): IPanelProvider? {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            provider?.let { return it }
            if (SystemClock.uptimeMillis() >= until) return null
            Thread.sleep(POLL_MS)
        }
    }

    /** [onStop] runs on the main thread whenever the plugin's process goes. */
    fun addStopListener(onStop: () -> Unit) {
        stopListeners += onStop
    }

    fun removeStopListener(onStop: () -> Unit) {
        stopListeners -= onStop
    }

    fun clickStatusItem(id: String) = whenConnected { connected ->
        runCatching { connected.statusItemClicked(id) }
    }

    fun refreshApps() {
        whenConnected { connected ->
            val listed = runCatching { connected.listApps().orEmpty().mapNotNull { it?.let(::providedAppOf) }.distinctBy { it.id } }
            main.post {
                if (!bound || provider !== connected) return@post
                listed.onSuccess { listener?.onApps(plugin, it) }.onFailure { log("  plugins: ${plugin.packageName} could not list apps: $it") }
            }
        }
    }

    private fun stopped() {
        val hadProvider = provider != null
        provider = null
        if (hadProvider) {
            stopListeners.toList().forEach { it() }
            listener?.onStopped(plugin)
        }
    }

    private companion object {
        const val POLL_MS = 50L
    }
}

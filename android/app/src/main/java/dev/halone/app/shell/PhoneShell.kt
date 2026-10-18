package dev.halone.app.shell

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dev.halone.compositor.Panel
import dev.halone.compositor.Scene
import dev.halone.engine.plugin.PluginDirectory
import dev.halone.plugin.PluginContract
import dev.halone.shell.AppInfo
import dev.halone.shell.BridgeHost
import dev.halone.shell.NativeApps
import dev.halone.shell.Shell
import dev.halone.shell.ShellPlatform
import dev.halone.shell.ShellSettings
import dev.halone.shell.SystemInfo
import java.util.concurrent.Executors

/**
 * The shell without the glasses, for Settings on the phone's own screen: the
 * same settings and plugins the environment reads, and a bridge for the page,
 * but no scene to open windows in.
 */
class PhoneShell(private val context: Context, deviceName: String, log: (String) -> Unit) : ShellPlatform<Panel> {
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val nativeApps = NativeApps(FileStore(context, "plugins.json", io), PluginContract.VERSION)
    private val pluginDirectory = PluginDirectory(context, log)

    private val shell = Shell(
        scene = Scene(),
        systemApps = AppInfo.parseRegistry(asset("apps.json")),
        settings = ShellSettings(PreferencesStore(context), asset(ShellSettings.SCHEMA_ASSET)),
        platform = this,
        nativeApps = nativeApps,
    )

    val bridge = BridgeHost(
        shell,
        SystemInfo(dev.halone.app.BuildConfig.VERSION_NAME, deviceName, 0.0, 0, 0),
        log,
        onMain = { handler.post(it) },
    )

    init {
        io.execute {
            val found = runCatching { pluginDirectory.scan() }.getOrNull() ?: return@execute
            handler.post { nativeApps.update(found) }
        }
    }

    fun close() = io.shutdown()

    private fun asset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }

    override fun openApp(windowId: String, app: AppInfo, url: String): Panel? = null

    override fun openWeb(windowId: String, url: String, private: Boolean): Panel? = null

    override fun openOverlays(dock: AppInfo, dockUrl: String, launcher: AppInfo, launcherUrl: String) = Unit

    override fun close(panel: Panel) = Unit

    override fun recenter() = Unit

    override fun headYawRadians() = 0f

    override fun headPitchRadians() = 0f

    override fun applySettings(settings: ShellSettings, changedKey: String?) = Unit

    override fun keyCaptureOn() = KeyCaptureService.running

    override fun openKeyCaptureSettings() {
        context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

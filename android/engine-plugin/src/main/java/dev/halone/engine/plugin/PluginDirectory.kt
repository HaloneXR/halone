package dev.halone.engine.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import dev.halone.plugin.PluginContract
import dev.halone.shell.PluginPackage
import java.security.MessageDigest

/**
 * Finds plugins among the installed packages and hears when one is installed,
 * updated or removed. Reads only what the package manager says; binding is
 * [PluginConnection]'s, and only once the wearer has enabled the plugin.
 */
class PluginDirectory(private val context: Context, private val log: (String) -> Unit) {
    private var receiver: BroadcastReceiver? = null

    @Suppress("DEPRECATION")
    fun scan(): List<PluginPackage> {
        val packages = context.packageManager
        val services = packages.queryIntentServices(Intent(PluginContract.ACTION_PANEL_PROVIDER), PackageManager.GET_META_DATA)
        return services.mapNotNull { resolved ->
            val service = resolved.serviceInfo ?: return@mapNotNull null
            if (service.packageName == context.packageName) return@mapNotNull null
            if (service.permission != PluginContract.PERMISSION_BIND || !service.exported) {
                log("  plugins: ${service.packageName} ignored: its service must be exported and require ${PluginContract.PERMISSION_BIND}")
                return@mapNotNull null
            }
            val info = runCatching { packages.getPackageInfo(service.packageName, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull()
                ?: return@mapNotNull null
            val signers = info.signingInfo?.apkContentsSigners.orEmpty()
            if (signers.isEmpty()) return@mapNotNull null
            PluginPackage(
                packageName = service.packageName,
                serviceClass = service.name,
                label = service.loadLabel(packages).toString(),
                versionName = info.versionName.orEmpty(),
                contract = service.metaData?.getInt(PluginContract.META_CONTRACT, 0) ?: 0,
                certificate = signers.map { sha256(it.toByteArray()) }.sorted().joinToString(","),
                updatedAt = info.lastUpdateTime,
            )
        }.distinctBy { it.packageName }
    }

    /** Calls [onChange] on the main thread whenever a package is added, updated or removed for good. */
    fun watch(onChange: () -> Unit) {
        unwatch()
        val listening = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_PACKAGE_REMOVED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                onChange()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        context.registerReceiver(listening, filter, Context.RECEIVER_EXPORTED)
        receiver = listening
    }

    fun unwatch() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

package dev.halone.shell

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Whether a plugin found on the phone may run. */
enum class PluginState(val wire: String) {
    /** Found, never enabled: nothing of it runs. */
    DISABLED("disabled"),
    ENABLED("enabled"),

    /** Enabled once, but now signed by someone else: refused until the wearer enables it again. */
    CERTIFICATE_CHANGED("certificate-changed"),

    /** Built for a contract this shell does not speak. */
    INCOMPATIBLE("incompatible"),
}

/** An app of an enabled plugin, as the launcher lists it. */
data class NativeApp(val packageName: String, val app: ProvidedApp) {
    val id: String get() = "$packageName/${app.id}"

    /** Served by the shell from what [packageName] gives, at the system apps' origin. */
    val iconUrl: String
        get() = "${Shell.ORIGIN}/${NativeApps.ICON_PATH}/$packageName/${encode(app.id)}/${encode(app.iconVersion.ifEmpty { "0" })}.png"

    fun toAppInfo() = AppInfo(
        id = id,
        name = app.name,
        description = app.description,
        icon = "",
        entry = "",
        widthPx = app.widthPx,
        heightPx = app.heightPx,
        hidden = app.hidden,
        kind = AppKind.NATIVE,
        iconUrl = iconUrl,
        provider = packageName,
        color = app.color,
    )

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}

/** A plugin as Settings shows it. */
data class PluginInfo(val plugin: PluginPackage, val state: PluginState, val apps: List<NativeApp>) {
    fun toJson(): JSONObject = JSONObject()
        .put("packageName", plugin.packageName)
        .put("label", plugin.label)
        .put("version", plugin.versionName)
        .put("state", state.wire)
        .put("apps", JSONArray(apps.filter { !it.app.hidden }.map { it.app.name }))
}

/**
 * The plugins on the phone and the apps of those the wearer enabled. A plugin
 * found for the first time is disabled; enabling it records its signing
 * certificate, and a different certificate later refuses it until the wearer
 * enables it again. The apps each plugin listed last are kept, so the
 * launcher and the dock have them before the plugin connects.
 *
 * [contractVersion] is the newest plugin contract this host speaks. Call on
 * one thread.
 */
class NativeApps(private val store: SettingsStore, private val contractVersion: Int) {
    private class Record(var certificate: String?, var apps: List<ProvidedApp>)

    private val records = LinkedHashMap<String, Record>()
    private var found: Map<String, PluginPackage> = emptyMap()

    /** The apps, or a plugin's state, changed. */
    var onChange: (() -> Unit)? = null

    /** A plugin was found that was never seen before. */
    var onDiscovered: ((PluginPackage) -> Unit)? = null

    init {
        store.load()?.let { text ->
            runCatching {
                val array = JSONArray(text)
                for (i in 0 until array.length()) runCatching {
                    val json = array.getJSONObject(i)
                    val apps = json.optJSONArray("apps")?.let { list -> (0 until list.length()).mapNotNull { appFromJson(list.optJSONObject(it)) } }.orEmpty()
                    records[json.getString("packageName")] = Record(json.optString("certificate").takeIf { it.isNotEmpty() && !json.isNull("certificate") }, apps)
                }
            }
        }
    }

    /** What a scan of the installed packages found; plugins no longer there are forgotten. */
    fun update(plugins: List<PluginPackage>) {
        val previous = found
        found = plugins.associateBy { it.packageName }
        val newcomers = plugins.filter { it.packageName !in records }
        newcomers.forEach { records[it.packageName] = Record(null, emptyList()) }
        val gone = records.keys - found.keys
        gone.forEach { records.remove(it) }
        if (newcomers.isEmpty() && gone.isEmpty() && previous == found) return
        changed()
        newcomers.forEach { onDiscovered?.invoke(it) }
    }

    fun stateOf(packageName: String): PluginState? {
        val plugin = found[packageName] ?: return null
        val certificate = records[packageName]?.certificate
        return when {
            !compatible(plugin) -> PluginState.INCOMPATIBLE
            certificate == null -> PluginState.DISABLED
            certificate != plugin.certificate -> PluginState.CERTIFICATE_CHANGED
            else -> PluginState.ENABLED
        }
    }

    fun setEnabled(packageName: String, enabled: Boolean) {
        val plugin = found[packageName] ?: throw HaloneException("no-such-plugin", "no such plugin: $packageName")
        if (enabled && !compatible(plugin)) throw HaloneException("incompatible", "${plugin.label} needs a newer Halone")
        val record = records.getOrPut(packageName) { Record(null, emptyList()) }
        val certificate = if (enabled) plugin.certificate else null
        if (record.certificate == certificate) return
        record.certificate = certificate
        record.apps = emptyList()
        changed()
    }

    /** What the plugin listed, once connected; ignored unless it is enabled. */
    fun setApps(packageName: String, apps: List<ProvidedApp>) {
        if (stateOf(packageName) != PluginState.ENABLED) return
        val record = records[packageName] ?: return
        if (record.apps == apps) return
        record.apps = apps
        changed()
    }

    /** The plugins that may run, which the platform keeps connected. */
    private fun compatible(plugin: PluginPackage) = plugin.contract in 1..contractVersion

    fun runnable(): List<PluginPackage> = found.values.filter { stateOf(it.packageName) == PluginState.ENABLED }

    fun plugins(): List<PluginInfo> = found.values.map { plugin ->
        val state = stateOf(plugin.packageName)!!
        PluginInfo(plugin, state, if (state == PluginState.ENABLED) appsOf(plugin.packageName) else emptyList())
    }

    fun list(): List<NativeApp> = runnable().flatMap { appsOf(it.packageName) }

    fun find(id: String?): NativeApp? = id?.let { wanted -> list().firstOrNull { it.id == wanted } }

    private fun appsOf(packageName: String) = records[packageName]?.apps.orEmpty().map { NativeApp(packageName, it) }

    private fun changed() {
        store.save(JSONArray(records.map { (packageName, record) ->
            JSONObject()
                .put("packageName", packageName)
                .put("certificate", record.certificate ?: JSONObject.NULL)
                .put("apps", JSONArray(record.apps.map(::appToJson)))
        }).toString())
        onChange?.invoke()
    }

    private fun appToJson(app: ProvidedApp) = JSONObject()
        .put("id", app.id)
        .put("name", app.name)
        .put("description", app.description)
        .put("widthPx", app.widthPx)
        .put("heightPx", app.heightPx)
        .put("hidden", app.hidden)
        .put("iconVersion", app.iconVersion)
        .put("color", app.color ?: JSONObject.NULL)

    private fun appFromJson(json: JSONObject?): ProvidedApp? {
        json ?: return null
        return ProvidedApp(
            id = json.optString("id").takeIf { it.isNotEmpty() } ?: return null,
            name = json.optString("name").takeIf { it.isNotEmpty() } ?: return null,
            description = json.optString("description"),
            widthPx = json.optInt("widthPx", PackageManifest.DEFAULT_WIDTH_PX),
            heightPx = json.optInt("heightPx", PackageManifest.DEFAULT_HEIGHT_PX),
            hidden = json.optBoolean("hidden"),
            iconVersion = json.optString("iconVersion"),
            color = json.optString("color").takeIf { it.isNotEmpty() && !json.isNull("color") },
        )
    }

    companion object {
        const val ICON_PATH = "native-icons"
    }
}

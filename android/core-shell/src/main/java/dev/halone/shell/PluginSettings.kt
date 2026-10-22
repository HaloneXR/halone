package dev.halone.shell

import dev.halone.engine.plugin.PluginPackage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Each plugin's own settings, from the list it declares (the plugin SDK's
 * README has the format): the wearer's values, kept here and validated as the
 * shell's own are, so they change while the plugin is not running too.
 *
 * Call on one thread.
 */
class PluginSettings(private val store: SettingsStore) {

    /** What Settings shows for one plugin, and its values. */
    class Page internal constructor(internal val source: String, val items: JSONArray, val values: ShellSettings) {
        fun toJson(): JSONObject = JSONObject().put("items", items).put("values", values.toJson())
    }

    private val saved: JSONObject = store.load()?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
    private val pages = HashMap<String, Page>()

    /** [plugin]'s page, or null when it declares no settings or an unusable list. */
    fun page(plugin: PluginPackage): Page? {
        val source = plugin.settingsSchema ?: return null
        pages[plugin.packageName]?.takeIf { it.source == source }?.let { return it }
        val page = runCatching { parse(plugin.packageName, source) }.getOrNull() ?: return null
        pages[plugin.packageName] = page
        return page
    }

    private fun parse(packageName: String, source: String): Page {
        val declared = JSONArray(source)
        require(declared.length() in 1..MAX_ITEMS) { "between 1 and $MAX_ITEMS settings" }
        val items = JSONArray()
        val rules = JSONObject()
        for (i in 0 until declared.length()) {
            val item = declared.getJSONObject(i)
            val key = item.getString("key")
            require(KEY.matches(key) && !rules.has(key)) { "bad or repeated key '$key'" }
            val shown = JSONObject()
                .put("key", key)
                .put("label", item.getString("label").take(MAX_LABEL))
                .put("help", item.optString("help").take(MAX_HELP))
            when (val type = item.getString("type")) {
                "boolean" -> {
                    rules.put(key, JSONObject().put("type", type).put("default", item.getBoolean("default")))
                    shown.put("kind", "toggle")
                }
                "number" -> {
                    val min = item.getDouble("min")
                    val max = item.getDouble("max")
                    val default = item.getDouble("default")
                    require(min < max && default in min..max) { "'$key' range" }
                    rules.put(key, JSONObject().put("type", type).put("default", default).put("min", min).put("max", max))
                    val step = item.optDouble("step", Double.NaN).takeIf { it > 0 } ?: ((max - min) / 100)
                    shown.put("kind", "slider").put("min", min).put("max", max).put("step", step)
                        .put("unit", item.optString("unit").take(MAX_UNIT))
                }
                "enum" -> {
                    val values = item.getJSONArray("values")
                    require(values.length() in 1..MAX_CHOICES) { "'$key' choices" }
                    val labels = item.optJSONObject("labels") ?: JSONObject()
                    val options = JSONArray()
                    for (j in 0 until values.length()) {
                        val value = values.getString(j).take(MAX_LABEL)
                        options.put(JSONObject().put("value", value).put("label", labels.optString(value, value).take(MAX_LABEL)))
                    }
                    rules.put(key, JSONObject().put("type", type).put("default", item.getString("default")).put("values", values))
                    shown.put("kind", "choice").put("options", options)
                }
                else -> error("'$key' has unknown type $type")
            }
            items.put(shown)
        }
        val values = ShellSettings(object : SettingsStore {
            override fun load(): String? = saved.optJSONObject(packageName)?.toString()
            override fun save(json: String) {
                saved.put(packageName, JSONObject(json))
                store.save(saved.toString())
            }
        }, rules.toString())
        return Page(source, items, values)
    }

    private companion object {
        val KEY = Regex("[A-Za-z0-9._-]{1,64}")
        const val MAX_ITEMS = 32
        const val MAX_CHOICES = 16
        const val MAX_LABEL = 80
        const val MAX_HELP = 400
        const val MAX_UNIT = 12
    }
}

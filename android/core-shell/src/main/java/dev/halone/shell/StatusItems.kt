package dev.halone.shell

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** A plugin's item in the dock's status area. */
data class StatusEntry(val provider: String, val item: ProvidedStatusItem) {
    val id: String get() = "$provider/${item.id}"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("provider", provider)
        .put("label", item.label)
        .put("text", item.text)
        .put("tinted", item.tinted)
        .put("iconUrl", "data:image/png;base64," + Base64.getEncoder().encodeToString(item.iconPng))
}

/** What plugins show in the dock's status area, in the order they first showed it. Call on one thread. */
class StatusItems {
    private val items = LinkedHashMap<String, StatusEntry>()

    var onChange: (() -> Unit)? = null

    fun list(): List<StatusEntry> = items.values.toList()

    fun find(id: String): StatusEntry? = items[id]

    /** Replaces the item with its id; a provider showing [MAX_PER_PROVIDER] already cannot add another. */
    fun set(provider: String, item: ProvidedStatusItem) {
        val entry = StatusEntry(provider, item)
        val existing = items[entry.id]
        if (existing == entry) return
        if (existing == null && items.values.count { it.provider == provider } >= MAX_PER_PROVIDER) return
        items[entry.id] = entry
        onChange?.invoke()
    }

    fun remove(provider: String, id: String) {
        if (items.remove("$provider/$id") != null) onChange?.invoke()
    }

    /** Takes away everything of the providers [keep] does not accept. */
    fun retain(keep: (String) -> Boolean) {
        if (items.values.removeAll { !keep(it.provider) }) onChange?.invoke()
    }

    fun toJson() = JSONArray(items.values.map { it.toJson() })

    companion object {
        const val MAX_PER_PROVIDER = 4
    }
}

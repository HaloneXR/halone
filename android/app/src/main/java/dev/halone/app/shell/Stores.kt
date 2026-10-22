package dev.halone.app.shell

import android.content.Context
import dev.halone.shell.SettingsStore

/** Each plugin's settings, shared by the environment's shell and the phone's. */
internal const val PLUGIN_SETTINGS = "plugin-settings.json"

/** A JSON file in the app's own storage, written on [writer] so the UI thread never waits on the disk. */
internal class FileStore(context: Context, name: String, private val writer: java.util.concurrent.Executor) : SettingsStore {
    private val file = java.io.File(context.filesDir, name)

    override fun load(): String? = runCatching { file.readText() }.getOrNull()

    override fun save(json: String) = writer.execute {
        val temporary = java.io.File(file.path + ".tmp")
        runCatching {
            temporary.writeText(json)
            temporary.renameTo(file)
        }
    }
}

/** Each app's chosen window size, as `WIDTHxHEIGHT` under its key. */
internal class PreferencesWindowSizes(context: Context) : dev.halone.shell.WindowSizeStore {
    private val preferences = context.getSharedPreferences("window-sizes", Context.MODE_PRIVATE)

    override fun load(key: String): Pair<Int, Int>? {
        val (width, height) = preferences.getString(key, null)?.split('x')?.takeIf { it.size == 2 } ?: return null
        return (width.toIntOrNull() ?: return null) to (height.toIntOrNull() ?: return null)
    }

    override fun save(key: String, widthPx: Int, heightPx: Int) =
        preferences.edit().putString(key, "${widthPx}x$heightPx").apply()
}

internal class PreferencesStore(context: Context) : SettingsStore {
    private val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    override fun load(): String? = preferences.getString(KEY, null)
    override fun save(json: String) = preferences.edit().putString(KEY, json).apply()

    companion object {
        const val NAME = "halone-shell"
        const val KEY = "settings"
    }
}

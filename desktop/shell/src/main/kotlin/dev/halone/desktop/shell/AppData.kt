package dev.halone.desktop.shell

import dev.halone.shell.SettingsStore
import java.io.File
import java.util.concurrent.Executor

/** Where Halone keeps its state on this machine, as each OS expects. */
object AppData {
    val dir: File by lazy {
        val os = System.getProperty("os.name").lowercase()
        val home = System.getProperty("user.home")
        val base = when {
            os.contains("mac") -> File(home, "Library/Application Support/Halone")
            os.contains("win") -> File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "Halone")
            else -> File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotEmpty() } ?: "$home/.config", "halone")
        }
        base.apply { mkdirs() }
    }
}

/** A JSON file in [AppData], written on [writer] so the shell's thread never waits on the disk. */
class FileStore(name: String, private val writer: Executor) : SettingsStore {
    private val file = File(AppData.dir, name)

    override fun load(): String? = runCatching { file.readText() }.getOrNull()

    override fun save(json: String) = writer.execute {
        val temporary = File(file.path + ".tmp")
        runCatching {
            temporary.writeText(json)
            java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
